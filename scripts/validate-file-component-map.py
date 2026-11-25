#!/usr/bin/env python3
"""Validate THIRD_PARTY/FILE_COMPONENT_MAP.json against THIRD_PARTY/SBOM.json.

Checks that every SBOM file is matched by exactly one rule, that every rule
matches at least one file, that every referenced component is declared (in the
SBOM components or in additionalComponents), and that inferred or unverified
entries are reported instead of being silently treated as verified.
"""
import fnmatch
import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SBOM_FILE = ROOT / "THIRD_PARTY" / "SBOM.json"
MAP_FILE = ROOT / "THIRD_PARTY" / "FILE_COMPONENT_MAP.json"
CONFIDENCES = {"declared", "inferred"}


def validate(sbom: dict, mapping: dict):
    errors = []
    declared = {c["name"] for c in sbom.get("components", [])}
    additional = {}
    for comp in mapping.get("additionalComponents", []):
        name = comp.get("name")
        if not name or not comp.get("license"):
            errors.append(f"additional component without name/license: {comp}")
            continue
        if name in declared or name in additional:
            errors.append(f"duplicate component name: {name}")
        additional[name] = comp
    known = declared | set(additional)

    rules = mapping.get("rules", [])
    hits = [0] * len(rules)
    files = [f["path"] for f in sbom.get("files", [])]
    resolved = {}
    for path in files:
        matched = [i for i, r in enumerate(rules) if fnmatch.fnmatchcase(path, r["pattern"])]
        if not matched:
            errors.append(f"unmapped file: {path}")
            continue
        if len(matched) > 1:
            pats = ", ".join(rules[i]["pattern"] for i in matched)
            errors.append(f"file matched by several rules: {path} ({pats})")
            continue
        hits[matched[0]] += 1
        resolved[path] = rules[matched[0]]

    for i, rule in enumerate(rules):
        if hits[i] == 0:
            errors.append(f"rule matches no file: {rule['pattern']}")
        if rule.get("confidence") not in CONFIDENCES:
            errors.append(f"rule without valid confidence: {rule['pattern']}")
        if not rule.get("components"):
            errors.append(f"rule without components: {rule['pattern']}")
        for name in rule.get("components", []):
            if name not in known:
                errors.append(f"unknown component '{name}' in rule {rule['pattern']}")
            if rule.get("confidence") == "declared" and name in additional:
                errors.append(f"rule {rule['pattern']} is 'declared' but uses non-declared component '{name}'")
    return errors, resolved, additional


def main():
    sbom = json.loads(SBOM_FILE.read_text(encoding="utf-8"))
    mapping = json.loads(MAP_FILE.read_text(encoding="utf-8"))
    errors, resolved, additional = validate(sbom, mapping)
    if errors:
        print("
".join(errors), file=sys.stderr)
        raise SystemExit(f"FILE_COMPONENT_MAP invalid: {len(errors)} problem(s)")
    inferred = sum(1 for r in resolved.values() if r["confidence"] == "inferred")
    unverified = sum(1 for c in additional.values() if not c.get("verified"))
    print(
        f"FILE_COMPONENT_MAP OK: {len(resolved)} files mapped, "
        f"{inferred} inferred (need human review), {unverified} unverified additional components"
    )
    if mapping.get("status") != "reviewed":
        print("status is not 'reviewed': release remains NOT supply-chain complete")


if __name__ == "__main__":
    main()
