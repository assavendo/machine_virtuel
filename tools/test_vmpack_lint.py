#!/usr/bin/env python3
import hashlib
import json
import tempfile
import unittest
import zipfile
from pathlib import Path

import vmpack_lint as L


class LintTest(unittest.TestCase):
    def make_package(self, version=1, disk=b"disk", vm_id="vm1", nvram=None, snapshots=None, boot=None, sig=True, pub=None):
        d = tempfile.TemporaryDirectory()
        path = Path(d.name) / "test.vmxpack"
        entries = {
            "manifest.json": None,
            "disk/disk.raw": disk,
        }
        manifest = {
            "format_version": version,
            "vm_id": vm_id,
            "disk_size_bytes": len(disk),
            "sha256_disk": hashlib.sha256(disk).hexdigest(),
        }
        if nvram is not None:
            entries["firmware/nvram.fd"] = nvram
            manifest.update(
                nvram_present=True,
                nvram_size_bytes=len(nvram),
                sha256_nvram=hashlib.sha256(nvram).hexdigest(),
            )
        if boot:
            manifest["boot_artifacts"] = []
            for field, (name, content) in boot.items():
                path_name = f"boot/{name}"
                entries[path_name] = content
                manifest["boot_artifacts"].append({
                    "field": field,
                    "package_path": path_name,
                    "size_bytes": len(content),
                    "sha256": hashlib.sha256(content).hexdigest(),
                })
        if snapshots:
            snap_name, snap_disk, snap_vm = snapshots
            snap_disk_name = "disk.snapshot"
            snap_manifest = {
                "vmId": snap_vm,
                "diskFileName": snap_disk_name,
                "diskSha256": hashlib.sha256(snap_disk).hexdigest(),
            }
            entries[f"snapshots/{snap_name}/manifest.json"] = json.dumps(snap_manifest).encode()
            entries[f"snapshots/{snap_name}/{snap_disk_name}"] = snap_disk
        if version >= 2:
            key = pub or b"PUBLIC-KEY"
            manifest["signing_public_key_sha256"] = hashlib.sha256(key).hexdigest()
        with zipfile.ZipFile(path, "w") as z:
            for name, content in entries.items():
                if name == "manifest.json":
                    z.writestr(name, json.dumps(manifest).encode())
                else:
                    z.writestr(name, content)
        if version >= 2 and sig:
            path.with_name(path.name + ".sig").write_bytes(b"SIG")
            path.with_name(path.name + ".pub").write_bytes(pub or b"PUBLIC-KEY")
        return d, path

    def test_valid_package(self):
        d, p = self.make_package(version=1)
        with d:
            self.assertEqual(L.lint(p)[0], [])

    def test_not_a_zip(self):
        d = tempfile.TemporaryDirectory()
        p = Path(d.name) / "bad"
        p.write_bytes(b"nope")
        self.assertTrue(L.lint(p)[0])
        d.cleanup()

    def test_traversal_entry(self):
        d, p = self.make_package(version=1)
        with d:
            with zipfile.ZipFile(p, "a") as z:
                z.writestr("../evil", b"x")
            self.assertTrue(any("traversal" in x for x in L.lint(p)[0]))

    def test_absolute_entry(self):
        d, p = self.make_package(version=1)
        with d:
            with zipfile.ZipFile(p, "a") as z:
                z.writestr("/evil", b"x")
            self.assertTrue(any("absolute" in x for x in L.lint(p)[0]))

    def test_backslash_entry(self):
        d, p = self.make_package(version=1)
        with d:
            with zipfile.ZipFile(p, "a") as z:
                z.writestr(r"direvil", b"x")
            self.assertTrue(any("invalid ZIP path" in x for x in L.lint(p)[0]))

    def test_duplicate_entry(self):
        d, p = self.make_package(version=1)
        with d:
            with zipfile.ZipFile(p, "a") as z:
                z.writestr("disk/disk.raw", b"other")
            self.assertTrue(any("duplicate" in x for x in L.lint(p)[0]))

    def test_missing_nvram(self):
        d, p = self.make_package(version=1, nvram=b"vars")
        with d:
            p2 = Path(d.name) / "missing.vmxpack"
            with zipfile.ZipFile(p) as src, zipfile.ZipFile(p2, "w") as dst:
                for info in src.infolist():
                    if info.filename != "firmware/nvram.fd":
                        dst.writestr(info, src.read(info.filename))
            self.assertTrue(any("NVRAM" in x for x in L.lint(p2)[0]))

    def test_disk_hash_mismatch(self):
        d, p = self.make_package(version=1)
        with d:
            with zipfile.ZipFile(p) as z:
                m = json.loads(z.read("manifest.json"))
            m["sha256_disk"] = "0" * 64
            p2 = Path(d.name) / "bad.vmxpack"
            with zipfile.ZipFile(p2, "w") as z:
                z.writestr("manifest.json", json.dumps(m).encode())
                z.writestr("disk/disk.raw", b"disk")
            self.assertTrue(any("SHA-256 mismatch" in x for x in L.lint(p2)[0]))

    def test_disk_size_mismatch(self):
        d, p = self.make_package(version=1)
        with d:
            with zipfile.ZipFile(p) as z:
                m = json.loads(z.read("manifest.json"))
            m["disk_size_bytes"] = 99
            p2 = Path(d.name) / "bad.vmxpack"
            with zipfile.ZipFile(p2, "w") as z:
                z.writestr("manifest.json", json.dumps(m).encode())
                z.writestr("disk/disk.raw", b"disk")
            self.assertTrue(any("size" in x for x in L.lint(p2)[0]))

    def test_two_disks(self):
        d, p = self.make_package(version=1)
        with d:
            p2 = Path(d.name) / "two.vmxpack"
            with zipfile.ZipFile(p) as src, zipfile.ZipFile(p2, "w") as dst:
                for info in src.infolist():
                    dst.writestr(info, src.read(info.filename))
                dst.writestr("disk/other.raw", b"x")
            self.assertTrue(any("exactly one disk" in x for x in L.lint(p2)[0]))

    def test_manifest_not_json(self):
        d, p = self.make_package(version=1)
        with d:
            p2 = Path(d.name) / "bad.vmxpack"
            with zipfile.ZipFile(p) as src, zipfile.ZipFile(p2, "w") as dst:
                for info in src.infolist():
                    if info.filename == "manifest.json":
                        dst.writestr(info.filename, b"{")
                    else:
                        dst.writestr(info, src.read(info.filename))
            self.assertTrue(any("not valid JSON" in x for x in L.lint(p2)[0]))

    def test_bad_vm_id(self):
        d, p = self.make_package(version=1, vm_id="../escape")
        with d:
            self.assertTrue(any("invalid source vm_id" in x for x in L.lint(p)[0]))

    def test_bad_format_version(self):
        d, p = self.make_package(version=1)
        with d:
            with zipfile.ZipFile(p) as z:
                m = json.loads(z.read("manifest.json"))
            m["format_version"] = 3
            p2 = Path(d.name) / "bad.vmxpack"
            with zipfile.ZipFile(p2, "w") as z:
                z.writestr("manifest.json", json.dumps(m).encode())
                z.writestr("disk/disk.raw", b"disk")
            self.assertTrue(any("unsupported" in x for x in L.lint(p2)[0]))

    def test_unknown_boot_field(self):
        d, p = self.make_package(version=1, boot={"unknown": ("x", b"x")})
        with d:
            self.assertTrue(any("unknown field" in x for x in L.lint(p)[0]))

    def test_boot_artifact_ok_and_tampered(self):
        d, p = self.make_package(version=1, boot={"kernelPath": ("kernel", b"kernel-data")})
        with d:
            self.assertEqual(L.lint(p)[0], [])
            p2 = Path(d.name) / "tampered.vmxpack"
            with zipfile.ZipFile(p) as src, zipfile.ZipFile(p2, "w") as dst:
                for info in src.infolist():
                    data = src.read(info.filename)
                    if info.filename == "boot/kernel":
                        data = b"tampered"
                    dst.writestr(info, data)
            self.assertTrue(any("boot artifact kernelPath" in x and "SHA-256 mismatch" in x for x in L.lint(p2)[0]))

    def test_snapshot_ok(self):
        d, p = self.make_package(version=1, snapshots=("good", b"snap", "vm1"))
        with d:
            self.assertEqual(L.lint(p)[0], [])

    def test_snapshot_of_other_vm(self):
        d, p = self.make_package(version=1, snapshots=("foreign", b"snap", "other-vm"))
        with d:
            self.assertTrue(any("belongs to another VM" in x for x in L.lint(p)[0]))

    def test_missing_sidecars_for_v2(self):
        d, p = self.make_package(version=2, sig=False)
        with d:
            self.assertTrue(any("needs its .sig" in x for x in L.lint(p)[0]))

    def test_public_key_mismatch(self):
        d, p = self.make_package(version=2)
        with d:
            p.with_name(p.name + ".pub").write_bytes(b"DIFFERENT")
            self.assertTrue(any("public key does not match" in x for x in L.lint(p)[0]))


if __name__ == "__main__":
    unittest.main()
