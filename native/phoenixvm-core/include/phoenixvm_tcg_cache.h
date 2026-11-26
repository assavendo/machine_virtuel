#ifndef PHOENIXVM_TCG_CACHE_H
#define PHOENIXVM_TCG_CACHE_H

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef struct PvTcgL2Handle PvTcgL2Handle;

typedef struct PvTcgCompatibility {
    const char *qemu_build_id;
    const char *guest_arch;
    const char *guest_cpu_model;
    const char *guest_build_id;
    const char *host_arch;
    const char *host_abi;
    /* 0 = little-endian, 1 = big-endian. */
    uint8_t host_endian;
    uint32_t host_page_size;
    const char *const *host_cpu_features;
    size_t host_cpu_features_len;
    uint64_t guest_pc;
    uint64_t guest_cs_base;
    uint64_t tb_flags;
    uint64_t tb_cflags;
    uint32_t mmu_index;
    const uint64_t *physical_pages;
    size_t physical_pages_len;
    const uint8_t *guest_code_sha256; /* exactly 32 readable bytes */
    const uint8_t *tb_context_sha256; /* exactly 32 readable bytes */
} PvTcgCompatibility;

/*
 * Open a VM-scoped L2 store. The directory must reside in the private
 * application sandbox. Returns NULL on invalid input or I/O failure.
 */
PvTcgL2Handle *phoenixvm_tcg_l2_open(const char *root,
                                    const char *vm_id,
                                    size_t max_entries,
                                    uint64_t max_bytes);
void phoenixvm_tcg_l2_close(PvTcgL2Handle *handle);

/*
 * Store a complete PVTCGIR v3 record. Returns 0 on success, -1 on error.
 * Disk operations are blocking: call from a cache worker, never a vCPU.
 */
int32_t phoenixvm_tcg_l2_store(PvTcgL2Handle *handle,
                               const uint8_t *record,
                               size_t record_len);

/*
 * Look up by the complete compatibility identity.
 * Returns 1 on hit, 0 on miss, -1 on error.
 * On hit, the caller owns *record and must free it using
 * phoenixvm_tcg_l2_free_buffer().
 */
int32_t phoenixvm_tcg_l2_load(PvTcgL2Handle *handle,
                              const PvTcgCompatibility *compatibility,
                              uint8_t **record,
                              size_t *record_len);
void phoenixvm_tcg_l2_free_buffer(uint8_t *record, size_t record_len);

int32_t phoenixvm_tcg_l2_invalidate(PvTcgL2Handle *handle,
                                   const PvTcgCompatibility *compatibility);
int32_t phoenixvm_tcg_l2_invalidate_vm(PvTcgL2Handle *handle);

#ifdef __cplusplus
}
#endif

#endif /* PHOENIXVM_TCG_CACHE_H */
