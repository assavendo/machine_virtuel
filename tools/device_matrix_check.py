#!/usr/bin/env python3
"""Validate docs/device-matrix/devices.json (cahier sections 32 and 50).

Rule: a capability may only be 'works' or 'fails' when the entry carries evidence
(a measured result file or a dated test log). Otherwise it must be 'untested'.
This keeps the matrix honest: no capability is claimed without a measurement.
"""
import json
import sys
from pathlib import Path

CAPS = ["kvm", "gunyah", "geniezone", "halla", "avf", "qemu_tcg", "virtio_gpu_accel", "vulkan", "usb_passthrough"]
STATES = {"works", "fails", "untested"}
ROOT_STATES = {"root", "non-root"}
FAMILIES = {"snapdragon", "dimensity", "exynos", "tensor", "other-arm64"}


def check(doc):
    errors = []
    if doc.get("schema") != 1:
        errors.append("schema must be 1")
    seen = set()
    for i, dev in enumerate(doc.get("devices", [])):
        tag = f"devices[{i}]"
        for key in ("model", "soc", "soc_family", "android", "kernel", "root_status", "capabilities"):
            if key not in dev:
                errors.append(f"{tag}: missing '{key}'")
        if errors and any(e.startswith(tag) for e in errors):
            continue
        ident = (dev["model"], dev["android"], dev["kernel"], dev["root_status"])
        if ident in seen:
            errors.append(f"{tag}: duplicate device entry {ident}")
        seen.add(ident)
        if dev["soc_family"] not in FAMILIES:
            errors.append(f"{tag}: unknown soc_family")
        if dev["root_status"] not in ROOT_STATES:
            errors.append(f"{tag}: root_status must be root or non-root")
        caps = dev["capabilities"]
        evidence = dev.get("evidence", [])
        for cap in CAPS:
            state = caps.get(cap)
            if state not in STATES:
                errors.append(f"{tag}: capability '{cap}' must be one of {sorted(STATES)}")
            elif state != "untested" and not evidence:
                errors.append(f"{tag}: capability '{cap}' is '{state}' without evidence")
        for cap in caps:
            if cap not in CAPS:
                errors.append(f"{tag}: unknown capability '{cap}'")
        for ev in evidence:
            if not isinstance(ev, dict) or not ev.get("file") or not ev.get("date"):
                errors.append(f"{tag}: each evidence needs 'file' and 'date'")
    return errors


def main(argv):
    path = Path(argv[1]) if len(argv) > 1 else Path(__file__).resolve().parents[1] / "docs" / "device-matrix" / "devices.json"
    errors = check(json.loads(path.read_text(encoding="utf-8")))
    if errors:
        print("\n".join(errors), file=sys.stderr)
        return 1
    n = len(json.loads(path.read_text(encoding="utf-8")).get("devices", []))
    print(f"device matrix OK: {n} device(s)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
