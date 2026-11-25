#!/usr/bin/env python3
import copy
import unittest

import device_matrix_check as D

CAPS = {c: "untested" for c in D.CAPS}
DEV = {"model": "M", "soc": "S", "soc_family": "snapdragon", "android": "16", "kernel": "6.12",
       "root_status": "non-root", "capabilities": CAPS}


def doc(dev=None):
    return {"schema": 1, "devices": [dev or copy.deepcopy(DEV)]}


class MatrixTest(unittest.TestCase):
    def test_empty_matrix_is_valid(self):
        self.assertEqual(D.check({"schema": 1, "devices": []}), [])

    def test_all_untested_is_valid(self):
        self.assertEqual(D.check(doc()), [])

    def test_claim_without_evidence_rejected(self):
        d = copy.deepcopy(DEV)
        d["capabilities"]["kvm"] = "works"
        self.assertTrue(any("without evidence" in e for e in D.check(doc(d))))

    def test_claim_with_evidence_accepted(self):
        d = copy.deepcopy(DEV)
        d["capabilities"]["kvm"] = "works"
        d["evidence"] = [{"file": "results/m-kvm.json", "date": "2026-12-01"}]
        self.assertEqual(D.check(doc(d)), [])

    def test_unknown_capability_rejected(self):
        d = copy.deepcopy(DEV)
        d["capabilities"]["magic"] = "works"
        self.assertTrue(D.check(doc(d)))

    def test_duplicate_rejected(self):
        two = {"schema": 1, "devices": [copy.deepcopy(DEV), copy.deepcopy(DEV)]}
        self.assertTrue(any("duplicate" in e for e in D.check(two)))


if __name__ == "__main__":
    unittest.main()
