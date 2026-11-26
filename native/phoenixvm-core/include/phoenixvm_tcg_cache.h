#ifndef PHOENIXVM_TCG_CACHE_H
#define PHOENIXVM_TCG_CACHE_H

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef struct PvTcgL2Handle PvTcgL2Handle;
typedef struct PvTcgIrBlockHandle PvTcgIrBlockHandle;

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

/*
 * Cache-worker API: perform bounded disk preload of the VM L2 into an
 * in-memory resident L1. This function blocks on I/O and MUST NOT run on a
 * vCPU thread.
 */
int32_t phoenixvm_tcg_l2_preload(PvTcgL2Handle *handle,
                                 size_t max_entries,
                                 uint64_t max_bytes);

/*
 * vCPU-path API: memory-only lookup after preload. This never accesses disk.
 * Returns 1 on hit, 0 on miss, -1 on invalid input/lock error.
 * On hit, the caller owns the block and must free it.
 */
int32_t phoenixvm_tcg_l1_lookup(PvTcgL2Handle *handle,
                                const PvTcgCompatibility *compatibility,
                                PvTcgIrBlockHandle **out_block);

/*
 * Structured PVTCGIR v3 builder and reader. No QEMU process pointers are
 * accepted by this API. Operand tags: 0=Temp, 1=Global, 2=Immediate, 3=Label.
 * Type tags: I32=0, I64=1, I128=2, V64=3, V128=4, V256=5, Ptr=6.
 * Temp-kind tags: TB=0, EBB=1.
 */
PvTcgIrBlockHandle *phoenixvm_tcg_ir_block_create(
    const PvTcgCompatibility *compatibility,
    uint32_t guest_size,
    uint16_t instruction_count);
void phoenixvm_tcg_ir_block_free(PvTcgIrBlockHandle *block);
int32_t phoenixvm_tcg_ir_block_add_temp(PvTcgIrBlockHandle *block,
                                        uint16_t id, uint16_t base_id,
                                        uint8_t subindex, uint8_t base_type,
                                        uint8_t value_type, uint8_t kind);
int32_t phoenixvm_tcg_ir_block_add_label(PvTcgIrBlockHandle *block,
                                         uint16_t id, uint32_t operation_index);
int32_t phoenixvm_tcg_ir_block_add_operation(PvTcgIrBlockHandle *block,
                                             const char *opcode,
                                             uint8_t param1, uint8_t param2);
int32_t phoenixvm_tcg_ir_block_add_operand(PvTcgIrBlockHandle *block,
                                           uint32_t operation_index,
                                           uint8_t tag, int64_t value);
int32_t phoenixvm_tcg_l2_store_block(PvTcgL2Handle *handle,
                                     const PvTcgIrBlockHandle *block);
int32_t phoenixvm_tcg_l2_load_block(PvTcgL2Handle *handle,
                                    const PvTcgCompatibility *compatibility,
                                    PvTcgIrBlockHandle **out_block);
/*
 * A successful block-store call only enqueues disk persistence. The queue is
 * bounded and nonblocking; return 1 means it was full (L1 retained, L2 skipped).
 * Flush is a blocking worker/shutdown operation, never call it from a vCPU.
 */
int32_t phoenixvm_tcg_l2_flush(PvTcgL2Handle *handle);
int32_t phoenixvm_tcg_l2_worker_stats(const PvTcgL2Handle *handle,
                                      uint64_t *errors,
                                      uint64_t *queue_drops);
int32_t phoenixvm_tcg_ir_block_get_summary(const PvTcgIrBlockHandle *block,
                                           uint32_t *guest_size,
                                           uint16_t *instruction_count,
                                           size_t *temp_count,
                                           size_t *label_count,
                                           size_t *operation_count);
int32_t phoenixvm_tcg_ir_block_get_temp(const PvTcgIrBlockHandle *block,
                                        size_t index, uint16_t *id,
                                        uint16_t *base_id, uint8_t *subindex,
                                        uint8_t *base_type, uint8_t *value_type,
                                        uint8_t *kind);
int32_t phoenixvm_tcg_ir_block_get_label(const PvTcgIrBlockHandle *block,
                                         size_t index, uint16_t *id,
                                         uint32_t *operation_index);
int32_t phoenixvm_tcg_ir_block_get_operation(const PvTcgIrBlockHandle *block,
                                             size_t index,
                                             const uint8_t **opcode,
                                             size_t *opcode_len,
                                             uint8_t *param1, uint8_t *param2,
                                             size_t *operand_count);
int32_t phoenixvm_tcg_ir_block_get_operand(const PvTcgIrBlockHandle *block,
                                           size_t operation_index,
                                           size_t operand_index,
                                           uint8_t *tag, int64_t *value);

#ifdef __cplusplus
}
#endif

#endif /* PHOENIXVM_TCG_CACHE_H */
