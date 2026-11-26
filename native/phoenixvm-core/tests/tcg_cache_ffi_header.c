#include "phoenixvm_tcg_cache.h"

int check_tcg_cache_c_abi(PvTcgL2Handle *handle,
                          const PvTcgCompatibility *compatibility)
{
    uint8_t *record = NULL;
    size_t record_len = 0;
    int32_t status = phoenixvm_tcg_l2_load(handle, compatibility,
                                           &record, &record_len);

    if (status == 1) {
        phoenixvm_tcg_l2_free_buffer(record, record_len);
    }

    if (status < 0) {
        return phoenixvm_tcg_l2_invalidate_vm(handle);
    }
    return status;
}

int check_tcg_ir_builder_abi(PvTcgL2Handle *handle,
                             const PvTcgCompatibility *compatibility)
{
    PvTcgIrBlockHandle *block =
        phoenixvm_tcg_ir_block_create(compatibility, 4, 1);
    uint32_t guest_size = 0;
    uint16_t instruction_count = 0;
    size_t temp_count = 0, label_count = 0, operation_count = 0;
    int32_t operation_index;

    if (block == NULL) {
        return -1;
    }
    operation_index = phoenixvm_tcg_ir_block_add_operation(
        block, "insn_start", 0, 0);
    if (operation_index < 0
        || phoenixvm_tcg_ir_block_add_operand(
            block, (uint32_t)operation_index, 2, 0x1000) != 0
        || phoenixvm_tcg_ir_block_add_operand(
            block, (uint32_t)operation_index, 2, 0) != 0
        || phoenixvm_tcg_ir_block_add_operand(
            block, (uint32_t)operation_index, 2, 0) != 0) {
        phoenixvm_tcg_ir_block_free(block);
        return -1;
    }

    if (phoenixvm_tcg_l2_store_block(handle, block) != 0) {
        phoenixvm_tcg_ir_block_free(block);
        return -1;
    }
    phoenixvm_tcg_ir_block_free(block);

    /* Explicit barrier is for non-vCPU worker/shutdown paths only. */
    if (phoenixvm_tcg_l2_flush(handle) != 0) {
        return -1;
    }

    if (phoenixvm_tcg_l2_load_block(handle, compatibility, &block) != 1) {
        return -1;
    }
    if (phoenixvm_tcg_ir_block_get_summary(
            block, &guest_size, &instruction_count,
            &temp_count, &label_count, &operation_count) != 0) {
        phoenixvm_tcg_ir_block_free(block);
        return -1;
    }
    phoenixvm_tcg_ir_block_free(block);

    if (phoenixvm_tcg_l2_preload(handle, 8, 1024 * 1024) != 0) {
        return -1;
    }
    if (phoenixvm_tcg_l1_lookup(handle, compatibility, &block) != 1) {
        return -1;
    }
    phoenixvm_tcg_ir_block_free(block);

    if (phoenixvm_tcg_l2_preload(handle, 8, 1024 * 1024) != 0) {
        return -1;
    }
    if (phoenixvm_tcg_l1_lookup(handle, compatibility, &block) != 1) {
        return -1;
    }
    phoenixvm_tcg_ir_block_free(block);
    return guest_size == 4 && instruction_count == 1
        && temp_count == 0 && label_count == 0 && operation_count == 1 ? 0 : -1;
}
