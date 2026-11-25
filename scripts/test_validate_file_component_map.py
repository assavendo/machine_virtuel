#!/usr/bin/env python3
import copy
import unittest

import validate_file_component_map as V


SBOM = {
    "components": [{"name": "declared", "license": "Apache-2.0"}],
    "files": [{"path": "usr/bin/tool"}, {"path": "usr/lib/libx.so"}],
}

MAPPING = {
    "status": "reviewed",
    "additionalComponents": [],
    "rules": [
        {"pattern": "usr/bin/tool", "components": ["declared"], "confidence": "declared"},
        {"pattern": "usr/lib/libx.so", "components": ["declared"], "confidence": "inferred"},
    ],
}


class FileComponentMapTest(unittest.TestCase):
    def test_valid_mapping(self):
        errors, resolved, additional = V.validate(SBOM, MAPPING)
        self.assertEqual(errors, [])
        self.assertEqual(set(resolved), {"usr/bin/tool", "usr/lib/libx.so"})
        self.assertEqual(additional, {})

    def test_unmapped_file_is_reported(self):
        sbom = copy.deepcopy(SBOM)
        sbom["files"].append({"path": "usr/lib/missing.so"})
        errors, _, _ = V.validate(sbom, MAPPING)
        self.assertTrue(any("unmapped file" in e for e in errors))

    def test_dead_rule_is_reported(self):
        mapping = copy.deepcopy(MAPPING)
        mapping["rules"].append({"pattern": "never/*", "components": ["declared"], "confidence": "declared"})
        errors, _, _ = V.validate(SBOM, mapping)
        self.assertTrue(any("rule matches no file" in e for e in errors))

    def test_overlapping_rules_are_reported(self):
        mapping = copy.deepcopy(MAPPING)
        mapping["rules"].insert(0, {"pattern": "usr/bin/*", "components": ["declared"], "confidence": "declared"})
        errors, _, _ = V.validate(SBOM, mapping)
        self.assertTrue(any("several rules" in e for e in errors))

    def test_unknown_component_is_reported(self):
        mapping = copy.deepcopy(MAPPING)
        mapping["rules"][0]["components"] = ["missing"]
        errors, _, _ = V.validate(SBOM, mapping)
        self.assertTrue(any("unknown component" in e for e in errors))

    def test_declared_rule_cannot_use_unverified_component(self):
        mapping = copy.deepcopy(MAPPING)
        mapping["additionalComponents"] = [
            {"name": "inferred-component", "license": "MIT", "verified": False}
        ]
        mapping["rules"][0]["components"] = ["inferred-component"]
        errors, _, _ = V.validate(SBOM, mapping)
        self.assertTrue(any("declared" in e and "non-declared" in e for e in errors))


if __name__ == "__main__":
    unittest.main()
