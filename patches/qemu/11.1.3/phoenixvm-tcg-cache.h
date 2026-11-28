/*
 * PhoenixVM persistent TCG cache adapter for QEMU 11.1.3.
 *
 * This header is internal to the QEMU patch. The external Rust C ABI is
 * declared in phoenixvm_tcg_cache_ffi.h alongside the implementation.
 */
#ifndef ACCEL_TCG_PHOENIXVM_TCG_CACHE_H
#define ACCEL_TCG_PHOENIXVM_TCG_CACHE_H

#include "exec/translation-block.h"
#include "exec/vaddr.h"
#include "hw/core/cpu.h"

void phoenixvm_tcg_cache_start(void);

bool phoenixvm_tcg_cache_try_restore(CPUState *cpu,
                                     TranslationBlock *tb,
                                     vaddr pc,
                                     void *host_pc,
                                     int *max_insns);

void phoenixvm_tcg_cache_store(CPUState *cpu,
                               TranslationBlock *tb,
                               vaddr pc,
                               void *host_pc);

#endif /* ACCEL_TCG_PHOENIXVM_TCG_CACHE_H */
