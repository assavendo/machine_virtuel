#!/usr/bin/env python3
"""Offline linter for PhoenixVM VM packages (format_version 1..2).

Mirrors the import-time rules of VmPackageManager.kt so a package can be
checked in CI or on a workstation without an Android device. It never
extracts anything to disk: every entry is streamed and hashed in memory.

Usage: vmpack_lint.py PACKAGE [--json]
Exit code 0 = no problem found, 1 = at least one problem, 2 = unreadable input.
"""
import hashlib
import json
import re
import sys
import zipfile
from pathlib import Path

FORMAT_VERSION = 2
MAX_ENTRIES = 50_000
MAX_MANIFEST_BYTES = 1_048_576
MAX_TOTAL_BYTES = 4_398_046_511_104
MAX_SIG_BYTES = 4096
HEX64 = re.compile(r"[A-Fa-f0-9]{64}")
VM_ID = re.compile(r"[A-Za-z0-9-]{1,64}")
SNAPSHOT_NAME = re.compile(r"[A-Za-z0-9._-]{1,160}")
DRIVE = re.compile(r"^[A-Za-z]:.*")
BOOT_FIELDS = {
    "kernelPath", "initrdPath", "dtbPath",
    "androidSystemPath", "androidVendorPath", "androidUserdataPath",
}


def sha256_of(zf: zipfile.ZipFile, name: str, limit: int):
    h = hashlib.sha256()
    total = 0
    with zf.open(name) as f:
        while True:
            chunk = f.read(1 << 20)
            if not chunk:
                break
            total += len(chunk)
            if total > limit:
                raise ValueError("entry exceeds declared limit")
            h.update(chunk)
    return h.hexdigest(), total


def check_entry_names(infos, problems):
    seen = set()
    if len(infos) > MAX_ENTRIES:
        problems.append("too many entries")
    total = 0
    for info in infos:
        name = info.filename
        total += info.file_size
        if name.startswith("/") or "\\" in name or chr(0) in name or DRIVE.match(name):
            problems.append(f"absolute or invalid ZIP path: {name!r}")
            continue
        normalized = name.rstrip("/")
        parts = normalized.split("/")
        if not normalized or any(p in ("", ".", "..") for p in parts):
            problems.append(f"directory traversal in ZIP path: {name!r}")
            continue
        if normalized in seen:
            problems.append(f"duplicate ZIP entry: {normalized}")
        seen.add(normalized)
    if total > MAX_TOTAL_BYTES:
        problems.append("uncompressed package exceeds the global size limit")
    return seen


def check_hash(zf, name, expected_size, expected_hash, label, problems):
    if not isinstance(expected_hash, str) or not HEX64.fullmatch(expected_hash):
        problems.append(f"{label}: invalid SHA-256 in manifest")
        return
    try:
        digest, size = sha256_of(zf, name, max(expected_size, 0) + 1 if expected_size is not None else MAX_TOTAL_BYTES)
    except (KeyError, ValueError) as exc:
        problems.append(f"{label}: {exc}")
        return
    if expected_size is not None and size != expected_size:
        problems.append(f"{label}: size {size} differs from manifest {expected_size}")
    if digest.lower() != expected_hash.lower():
        problems.append(f"{label}: SHA-256 mismatch")


def lint(package: Path):
    problems = []
    try:
        zf = zipfile.ZipFile(package)
    except (zipfile.BadZipFile, OSError) as exc:
        return [f"unreadable package: {exc}"], None
    with zf:
        infos = zf.infolist()
        names = check_entry_names(infos, problems)
        if "manifest.json" not in names:
            return problems + ["manifest.json missing"], None
        minfo = zf.getinfo("manifest.json")
        if not 0 < minfo.file_size <= MAX_MANIFEST_BYTES:
            return problems + ["manifest.json empty or larger than 1 MiB"], None
        try:
            manifest = json.loads(zf.read("manifest.json"))
        except ValueError as exc:
            return problems + [f"manifest.json is not valid JSON: {exc}"], None
        if not isinstance(manifest, dict):
            return problems + ["manifest.json must be a JSON object"], None

        version = manifest.get("format_version")
        if not isinstance(version, int) or isinstance(version, bool) or not 1 <= version <= FORMAT_VERSION:
            problems.append("unsupported format_version")
            return problems, manifest

        vm_id = manifest.get("vm_id", "")
        if not isinstance(vm_id, str) or not VM_ID.fullmatch(vm_id):
            problems.append("invalid source vm_id")

        disks = [i for i in infos if i.filename.startswith("disk/") and not i.is_dir() and i.filename.count("/") == 1]
        if len(disks) != 1:
            problems.append("exactly one disk image is expected under disk/")
        else:
            size = manifest.get("disk_size_bytes")
            if not isinstance(size, int) or size <= 0:
                problems.append("disk_size_bytes must be a positive integer")
            else:
                check_hash(zf, disks[0].filename, size, manifest.get("sha256_disk"), "disk", problems)

        if manifest.get("nvram_present", "firmware/nvram.fd" in names):
            if "firmware/nvram.fd" not in names:
                problems.append("NVRAM referenced by manifest but absent")
            else:
                nsize = manifest.get("nvram_size_bytes", zf.getinfo("firmware/nvram.fd").file_size)
                nhash = manifest.get("sha256_nvram")
                if nhash not in (None, "", "null"):
                    check_hash(zf, "firmware/nvram.fd", nsize, nhash, "nvram", problems)
                elif zf.getinfo("firmware/nvram.fd").file_size != nsize:
                    problems.append("nvram: size differs from manifest")

        for art in manifest.get("boot_artifacts") or []:
            field = art.get("field") if isinstance(art, dict) else None
            if field not in BOOT_FIELDS:
                problems.append(f"boot artifact with unknown field: {field!r}")
                continue
            path = art.get("package_path", "")
            if path not in names:
                problems.append(f"boot artifact {field}: file missing in package")
                continue
            check_hash(zf, path, art.get("size_bytes"), art.get("sha256"), f"boot artifact {field}", problems)

        snaps = {}
        for name in names:
            parts = name.split("/")
            if parts[0] == "snapshots" and len(parts) >= 3:
                snaps.setdefault(parts[1], set()).add("/".join(parts[2:]))
        for snap, files in sorted(snaps.items()):
            label = f"snapshot {snap}"
            if not SNAPSHOT_NAME.fullmatch(snap):
                problems.append(f"{label}: unsafe name")
                continue
            if "manifest.json" not in files:
                problems.append(f"{label}: manifest missing")
                continue
            try:
                meta = json.loads(zf.read(f"snapshots/{snap}/manifest.json"))
            except ValueError:
                problems.append(f"{label}: manifest is not valid JSON")
                continue
            if meta.get("vmId") != vm_id:
                problems.append(f"{label}: belongs to another VM")
            disk_name = meta.get("diskFileName") or next((f for f in sorted(files) if f.endswith(".snapshot")), "")
            if not disk_name or "/" in disk_name or disk_name not in files:
                problems.append(f"{label}: snapshot disk missing or invalid")
                continue
            check_hash(zf, f"snapshots/{snap}/{disk_name}", None, meta.get("diskSha256"), f"{label} disk", problems)

        sig = package.with_name(package.name + ".sig")
        pub = package.with_name(package.name + ".pub")
        if version >= 2:
            fp = manifest.get("signing_public_key_sha256", "")
            if not isinstance(fp, str) or not HEX64.fullmatch(fp):
                problems.append("signing_public_key_sha256 missing or invalid")
            if not (sig.is_file() and pub.is_file()):
                problems.append("v2 package needs its .sig and .pub sidecar files")
        if sig.is_file() != pub.is_file():
            problems.append("incomplete signature pair")
        if sig.is_file() and pub.is_file():
            if not (0 < sig.stat().st_size <= MAX_SIG_BYTES and 0 < pub.stat().st_size <= MAX_SIG_BYTES):
                problems.append("signature files have an abnormal size")
            elif version >= 2 and HEX64.fullmatch(str(manifest.get("signing_public_key_sha256", ""))):
                actual = hashlib.sha256(pub.read_bytes()).hexdigest()
                if actual != manifest["signing_public_key_sha256"].lower():
                    problems.append("public key does not match the fingerprint signed in the manifest")
    return problems, manifest


def main(argv):
    args = [a for a in argv[1:] if not a.startswith("--")]
    if len(args) != 1:
        print(__doc__, file=sys.stderr)
        return 2
    path = Path(args[0])
    if not path.is_file():
        print(f"not a file: {path}", file=sys.stderr)
        return 2
    problems, manifest = lint(path)
    if "--json" in argv:
        print(json.dumps({"package": str(path), "ok": not problems, "problems": problems}, indent=2))
    elif problems:
        print("\n".join(f"- {p}" for p in problems), file=sys.stderr)
    else:
        print(f"OK: {path.name}")
    print("Note: Ed25519 signature is NOT verified here; use the app import path.", file=sys.stderr)
    return 1 if problems else 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
