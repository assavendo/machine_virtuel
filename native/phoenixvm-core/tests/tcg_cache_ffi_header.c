#include "phoenixvm_tcg_cache.h"

static int check_tcg_cache_c_abi(PvTcgL2Handle *handle,
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
