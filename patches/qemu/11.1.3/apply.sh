#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 1 ]]; then
  echo "usage: $0 <qemu-11.1.3-source-directory>" >&2
  exit 2
fi

QEMU_SRC="$(cd "$1" && pwd)"
PATCH_DIR="$(cd "$(dirname "$0")" && pwd)"

test -f "$QEMU_SRC/accel/tcg/translate-all.c"
test -f "$QEMU_SRC/accel/tcg/tcg-all.c"
test -f "$QEMU_SRC/accel/tcg/meson.build"

if grep -q "PHOENIXVM_PERSISTENT_TCG_CACHE_V1" "$QEMU_SRC/accel/tcg/translate-all.c"; then
  echo "PhoenixVM TCG cache patch already applied"
  exit 0
fi

install -m 0644 "$PATCH_DIR/phoenixvm-tcg-cache.c" \
  "$QEMU_SRC/accel/tcg/phoenixvm-tcg-cache.c"
install -m 0644 "$PATCH_DIR/phoenixvm-tcg-cache.h" \
  "$QEMU_SRC/accel/tcg/phoenixvm-tcg-cache.h"
install -m 0644 "$PATCH_DIR/phoenixvm-tcg-cache-ffi.h" \
  "$QEMU_SRC/accel/tcg/phoenixvm-tcg-cache-ffi.h"

python3 - "$QEMU_SRC" <<'PY'
from pathlib import Path
import sys

root = Path(sys.argv[1])

def replace_once(path: str, old: str, new: str) -> None:
    file = root / path
    content = file.read_text()
    count = content.count(old)
    if count != 1:
        raise SystemExit(f"{path}: expected one patch anchor, found {count}: {old[:100]!r}")
    file.write_text(content.replace(old, new, 1))

replace_once(
    "accel/tcg/meson.build",
    "  'tcg-stats.c',\n  'translate-all.c',",
    "  'tcg-stats.c',\n  'phoenixvm-tcg-cache.c',\n  'translate-all.c',",
)

replace_once(
    "accel/tcg/translate-all.c",
    '#include "tcg/perf.h"\n',
    '#include "tcg/perf.h"\n#include "accel/tcg/phoenixvm-tcg-cache.h"\n',
)

replace_once(
    "accel/tcg/translate-all.c",
    "    tcg_func_start(tcg_ctx);\n\n"
    "    CPUState *cs = env_cpu(env);\n"
    "    tcg_ctx->cpu = cs;\n"
    "    cs->cc->tcg_ops->translate_code(cs, tb, max_insns, pc, host_pc);\n",
    "    tcg_func_start(tcg_ctx);\n\n"
    "    CPUState *cs = env_cpu(env);\n"
    "    tcg_ctx->cpu = cs;\n"
    "    /* PHOENIXVM_PERSISTENT_TCG_CACHE_V1: restore only validated symbolic IR. */\n"
    "    if (!phoenixvm_tcg_cache_try_restore(cs, tb, pc, host_pc, max_insns)) {\n"
    "        /* Roll back any partial import before invoking the upstream translator. */\n"
    "        tcg_func_start(tcg_ctx);\n"
    "        tcg_ctx->cpu = cs;\n"
    "        cs->cc->tcg_ops->translate_code(cs, tb, max_insns, pc, host_pc);\n"
    "        phoenixvm_tcg_cache_store(cs, tb, pc, host_pc);\n"
    "    }\n",
)

replace_once(
    "accel/tcg/tcg-all.c",
    '#include "internal-common.h"\n',
    '#include "internal-common.h"\n#include "accel/tcg/phoenixvm-tcg-cache.h"\n',
)

replace_once(
    "accel/tcg/tcg-all.c",
    "    tcg_init(s->tb_size * MiB, s->splitwx_enabled, max_threads);\n",
    "    tcg_init(s->tb_size * MiB, s->splitwx_enabled, max_threads);\n"
    "    /* Preload L2 on the main startup thread, before any vCPU is runnable. */\n"
    "    phoenixvm_tcg_cache_start();\n",
)
PY

echo "PhoenixVM QEMU 11.1.3 TCG cache patch applied"
