#!/usr/bin/env python3
"""Validate PhoenixVM benchmark result files and compare a candidate to a baseline.

A result file is JSON:
{
  "schema": 1,
  "device": {"model": str, "soc": str, "android": str, "kernel": str},
  "backend": str, "vm_config": {...}, "initial_temp_c": number,
  "workload": str, "unit": str, "higher_is_better": bool,
  "sustained_minutes": number, "runs": [number, ...]
}

The +20 % claim of the cahier (sections 28, 56) is only reported as PROVEN when:
 - both files describe the same device, workload and unit,
 - each has at least MIN_RUNS runs,
 - the candidate median beats the baseline median by the threshold,
 - the improvement exceeds the pooled run-to-run noise (so it is not noise),
 - for sustained workloads both ran at least MIN_SUSTAINED_MINUTES.
Anything else is reported as NOT PROVEN with the reason; no number is invented.
"""
import json
import statistics
import sys
from pathlib import Path

MIN_RUNS = 5
MIN_SUSTAINED_MINUTES = 30
THRESHOLD_PCT = 20.0
REQUIRED = ["schema", "device", "backend", "vm_config", "initial_temp_c", "workload", "unit", "higher_is_better", "runs"]
DEVICE_KEYS = ["model", "soc", "android", "kernel"]


def validate(doc, label):
    errors = []
    for key in REQUIRED:
        if key not in doc:
            errors.append(f"{label}: missing '{key}'")
    if errors:
        return errors
    if doc["schema"] != 1:
        errors.append(f"{label}: unsupported schema")
    for k in DEVICE_KEYS:
        if not isinstance(doc["device"].get(k), str) or not doc["device"][k].strip():
            errors.append(f"{label}: device.{k} required")
    runs = doc["runs"]
    if not isinstance(runs, list) or not all(isinstance(r, (int, float)) and not isinstance(r, bool) for r in runs):
        errors.append(f"{label}: runs must be a list of numbers")
    elif any(r <= 0 for r in runs):
        errors.append(f"{label}: runs must be positive")
    if not isinstance(doc["higher_is_better"], bool):
        errors.append(f"{label}: higher_is_better must be boolean")
    return errors


def gain_pct(base, cand, higher_is_better):
    if higher_is_better:
        return (cand - base) / base * 100.0
    return (base - cand) / base * 100.0


def compare(baseline, candidate, threshold=THRESHOLD_PCT):
    errors = validate(baseline, "baseline") + validate(candidate, "candidate")
    if errors:
        return {"verdict": "INVALID", "reasons": errors}
    reasons = []
    for key in ("workload", "unit", "higher_is_better"):
        if baseline[key] != candidate[key]:
            reasons.append(f"{key} differs between baseline and candidate")
    if baseline["device"] != candidate["device"]:
        reasons.append("device differs between baseline and candidate")
    for label, doc in (("baseline", baseline), ("candidate", candidate)):
        if len(doc["runs"]) < MIN_RUNS:
            reasons.append(f"{label} has fewer than {MIN_RUNS} runs")
        if doc.get("sustained_minutes") is not None and doc["sustained_minutes"] < MIN_SUSTAINED_MINUTES:
            reasons.append(f"{label} sustained run shorter than {MIN_SUSTAINED_MINUTES} minutes")
    if reasons:
        return {"verdict": "NOT PROVEN", "reasons": reasons}
    hib = baseline["higher_is_better"]
    b_med, c_med = statistics.median(baseline["runs"]), statistics.median(candidate["runs"])
    gain = gain_pct(b_med, c_med, hib)
    noise = max(statistics.pstdev(baseline["runs"]), statistics.pstdev(candidate["runs"]))
    delta = abs(c_med - b_med)
    result = {"baseline_median": b_med, "candidate_median": c_med, "gain_pct": round(gain, 2), "noise_abs": round(noise, 4)}
    if gain < threshold:
        result.update(verdict="NOT PROVEN", reasons=[f"median gain {gain:.1f}% is below {threshold}%"])
    elif delta <= noise:
        result.update(verdict="NOT PROVEN", reasons=["gain is within run-to-run noise"])
    else:
        result.update(verdict="PROVEN", reasons=[])
    return result


def main(argv):
    if len(argv) != 3:
        print("usage: bench_compare.py BASELINE.json CANDIDATE.json", file=sys.stderr)
        return 2
    base = json.loads(Path(argv[1]).read_text(encoding="utf-8"))
    cand = json.loads(Path(argv[2]).read_text(encoding="utf-8"))
    out = compare(base, cand)
    print(json.dumps(out, indent=2))
    return 0 if out["verdict"] == "PROVEN" else 1


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
