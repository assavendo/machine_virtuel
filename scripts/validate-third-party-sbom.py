#!/usr/bin/env python3
import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
HASH_FILE = ROOT / "THIRD_PARTY" / "DroidVM-Prebuilts.json"
LICENSE_FILE = ROOT / "THIRD_PARTY" / "DroidVM-license.yaml"
SBOM_FILE = ROOT / "THIRD_PARTY" / "SBOM.json"

def parse_license_inventory(text: str):
    lines = text.splitlines()
    components = []
    i = 0
    while i < len(lines):
        match = re.fullmatch(r"  - name: (.+)", lines[i])
        if not match:
            i += 1
            continue
        item = {"name": match.group(1).strip(), "description": "", "license": "", "urls": []}
        i += 1
        while i < len(lines) and not re.fullmatch(r"  - name: (.+)", lines[i]):
            line = lines[i]
            m = re.fullmatch(r"    description: (.+)", line)
            if m:
                item["description"] = m.group(1).strip()
            m = re.fullmatch(r"    license: (.+)", line)
            if m:
                item["license"] = m.group(1).strip()
            m = re.fullmatch(r"      - (.+)", line)
            if m:
                item["urls"].append(m.group(1).strip())
            i += 1
        components.append(item)
    return components

def main():
    hashes = json.loads(HASH_FILE.read_text(encoding="utf-8")).get("hash", [])
    licenses = parse_license_inventory(LICENSE_FILE.read_text(encoding="utf-8"))
    if not hashes:
        raise SystemExit("empty prebuilt hash inventory")
    if not licenses:
        raise SystemExit("empty license inventory")

    expected = {
        "bomFormat": "PhoenixVM-ThirdPartyInventory",
        "specVersion": "1.0",
        "generatedFrom": {
            "hashInventory": "THIRD_PARTY/DroidVM-Prebuilts.json",
            "licenseInventory": "THIRD_PARTY/DroidVM-license.yaml",
        },
        "completeness": {
            "fileHashes": True,
            "declaredComponentLicenses": True,
            "fileToComponentLicenseMapping": False,
            "releaseReady": False,
        },
        "files": [
            {"path": x["file"], "source": x["source"], "sha256": x["sha256"]}
            for x in hashes
        ],
        "components": licenses,
    }

    existing = json.loads(SBOM_FILE.read_text(encoding="utf-8"))
    comparable = {k: existing.get(k) for k in expected if k != "generatedAt"}
    if comparable != expected:
        raise SystemExit("THIRD_PARTY/SBOM.json is stale; regenerate it from the source inventories")
    if len(existing.get("files", [])) != len(hashes):
        raise SystemExit("SBOM file count differs from prebuilt hash inventory")
    if len(existing.get("components", [])) != len(licenses):
        raise SystemExit("SBOM component count differs from license inventory")
    print(f"SBOM OK: {len(hashes)} files, {len(licenses)} declared components")

if __name__ == "__main__":
    main()
