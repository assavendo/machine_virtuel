#!/usr/bin/env python3
import copy
import unittest

import bench_compare as B

DOC = {
    "schema": 1,
    "device": {"model": "X", "soc": "S", "android": "16", "kernel": "6.12"},
    "backend": "QEMU_TCG", "vm_config": {}, "initial_temp_c": 30.0,
    "workload": "cpu-int", "unit": "ops/s", "higher_is_better": True,
    "runs": [100, 101, 99, 100, 100],
}


def doc(**over):
    d = copy.deepcopy(DOC)
    d.update(over)
    return d


class CompareTest(unittest.TestCase):
    def test_proven(self):
        r = B.compare(doc(), doc(runs=[130, 131, 129, 130, 130]))
        self.assertEqual(r["verdict"], "PROVEN")
        self.assertGreaterEqual(r["gain_pct"], 20)

    def test_gain_too_small(self):
        self.assertEqual(B.compare(doc(), doc(runs=[110] * 5))["verdict"], "NOT PROVEN")

    def test_too_few_runs(self):
        r = B.compare(doc(), doc(runs=[200, 200, 200]))
        self.assertEqual(r["verdict"], "NOT PROVEN")

    def test_noise_rejected(self):
        noisy_base = doc(runs=[50, 150, 100, 60, 140])
        noisy_cand = doc(runs=[70, 190, 120, 80, 180])
        r = B.compare(noisy_base, noisy_cand, threshold=10)
        self.assertEqual(r["verdict"], "NOT PROVEN")

    def test_lower_is_better(self):
        base = doc(higher_is_better=False, unit="s", runs=[10, 10.1, 9.9, 10, 10])
        cand = doc(higher_is_better=False, unit="s", runs=[7, 7.1, 6.9, 7, 7])
        self.assertEqual(B.compare(base, cand)["verdict"], "PROVEN")

    def test_different_device(self):
        other = doc()
        other["device"]["soc"] = "Z"
        self.assertEqual(B.compare(doc(), other)["verdict"], "NOT PROVEN")

    def test_short_sustained_run(self):
        r = B.compare(doc(sustained_minutes=5), doc(runs=[200] * 5, sustained_minutes=5))
        self.assertEqual(r["verdict"], "NOT PROVEN")

    def test_invalid_input(self):
        bad = doc()
        del bad["runs"]
        self.assertEqual(B.compare(bad, doc())["verdict"], "INVALID")

    def test_negative_runs_invalid(self):
        self.assertEqual(B.compare(doc(runs=[-1, 2, 3, 4, 5]), doc())["verdict"], "INVALID")


if __name__ == "__main__":
    unittest.main()
