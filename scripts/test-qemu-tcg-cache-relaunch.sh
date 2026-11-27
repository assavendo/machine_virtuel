#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 2 ]]; then
  echo "usage: $0 <qemu-system-x86_64> <libphoenixvm_core.so>" >&2
  exit 2
fi

QEMU="$(cd "$(dirname "$1")" && pwd)/$(basename "$1")"
NATIVE_LIB="$(cd "$(dirname "$2")" && pwd)/$(basename "$2")"
ROOT="$(mktemp -d)"
CACHE="$ROOT/l2"
ROM="$ROOT/phoenixvm-tcg-cache-test-bios.bin"
COLD_LOG="$ROOT/cold.log"
WARM_LOG="$ROOT/warm.log"
VM_ID="phoenixvm-qemu-11.1.3-ci-relaunch"
EXPECTED_EXIT=33

cleanup() {
  rm -rf "$ROOT"
}
trap cleanup EXIT

test -x "$QEMU"
test -f "$NATIVE_LIB"

python3 - "$ROM" <<'PY'
from pathlib import Path
import sys

rom = bytearray([0x90] * 0x10000)

# A deterministic 16-bit BIOS entry. A NOP block ends in a short direct
# relative jump, which is represented by ordinary TCG branch/exit operations.
code = bytearray([0x90] * 128)
code += b"\xeb\x0e"  # jump to the I/O exit routine at offset 144
code += bytearray([0x90] * 14)
code += b"\xba\xf4\x00"  # mov dx, 0xf4
code += b"\xb0\x10"      # mov al, 0x10
code += b"\xee"            # out dx, al (isa-debug-exit)
code += b"\xf4"            # hlt if the debug-exit device did not terminate

rom[:len(code)] = code
rom[0xfff0:0xfff5] = b"\xea\x00\x00\x00\xf0"  # far jump to F000:0000
Path(sys.argv[1]).write_bytes(rom)
PY

export PHOENIXVM_TCG_CACHE_LIBRARY="$NATIVE_LIB"
export PHOENIXVM_TCG_CACHE_DIR="$CACHE"
export PHOENIXVM_TCG_CACHE_VM_ID="$VM_ID"
export PHOENIXVM_TCG_CACHE_MAX_ENTRIES=128
export PHOENIXVM_TCG_CACHE_MAX_BYTES=16777216
export PHOENIXVM_GUEST_ARCH=x86_64
export PHOENIXVM_GUEST_CPU_MODEL=qemu64
export PHOENIXVM_GUEST_BUILD_ID=phoenixvm-qemu-tcg-relaunch-test-v1
export PHOENIXVM_TCG_HOST_ABI=linux-glibc-ci
export PHOENIXVM_TCG_HOST_FEATURES=ci-generic
export PHOENIXVM_TCG_CACHE_TRACE=1

run_guest() {
  local logfile="$1"
  local status=0
  "$QEMU" \
    -machine pc,accel=tcg \
    -cpu qemu64 \
    -smp 1 \
    -m 16M \
    -bios "$ROM" \
    -device isa-debug-exit,iobase=0xf4,iosize=0x04 \
    -display none \
    -serial none \
    -monitor none \
    -no-reboot \
    >"$logfile" 2>&1 || status=$?

  if [[ "$status" -ne "$EXPECTED_EXIT" ]]; then
    cat "$logfile" >&2
    echo "QEMU guest exited with status $status; expected $EXPECTED_EXIT" >&2
    return 1
  fi
}

# Cold start: the cache starts empty, so this run must translate and enqueue.
run_guest "$COLD_LOG"
if ! grep -q "PVTCG: queued" "$COLD_LOG"; then
  cat "$COLD_LOG" >&2
  echo "Cold run did not persist an eligible TCG block." >&2
  exit 1
fi
if grep -q "PVTCG: hit" "$COLD_LOG"; then
  echo "Cold run unexpectedly reported an L2/L1 restore hit." >&2
  exit 1
fi

# Warm start: the entire QEMU process has exited. Only the persisted L2 remains.
run_guest "$WARM_LOG"
if ! grep -q "PVTCG: hit" "$WARM_LOG"; then
  cat "$COLD_LOG" >&2
  cat "$WARM_LOG" >&2
  echo "Warm relaunch did not restore a TCG block from persistent L2." >&2
  exit 1
fi

echo "PASS: QEMU 11.1.3 cold/warm process-relaunch cache test"
grep "PVTCG: queued" "$COLD_LOG" | head -n 3
grep "PVTCG: hit" "$WARM_LOG" | head -n 3
