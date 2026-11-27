/*
 * PhoenixVM persistent TCG IR cache adapter.
 *
 * This is deliberately a conservative first importer for QEMU v11.1.3:
 * unknown opcodes, helpers, vector ops, cross-page TBs and special execution
 * modes are misses. Cached IR is rebuilt with fresh TCG objects and then goes
 * through the ordinary tcg_gen_code(), TB publication and page invalidation
 * lifecycle. Host machine code and QEMU process pointers are never persisted.
 *
 * SPDX-License-Identifier: GPL-2.0-or-later
 */

#include "qemu/osdep.h"

#if !defined(CONFIG_USER_ONLY) && defined(CONFIG_POSIX)

#include <dlfcn.h>
#include <unistd.h>

#include "accel/tcg/phoenixvm-tcg-cache.h"
#include "accel/tcg/phoenixvm-tcg-cache-ffi.h"
#include "accel/tcg/cpu-mmu-index.h"
#include "exec/translation-block.h"
#include "exec/target_page.h"
#include "system/physmem.h"
#include "tcg/tcg.h"
#include "tcg/tcg-op-common.h"
#include "tcg/tcg-internal.h"
#include "tcg/tcg-temp-internal.h"
#include "exec/log.h"
#include "qemu/bswap.h"

#define PV_QEMU_BUILD_ID "qemu-11.1.3-phoenixvm-pcache-abi1"
#define PV_MAX_FEATURES 128
#define PV_MAX_LABELS 4096
#define PV_MAX_IR_OPERATIONS 16384
#define PV_TEMP_UNMAPPED UINT16_MAX
#define PV_LABEL_UNMAPPED UINT16_MAX

enum {
    PV_OPERAND_TEMP = 0,
    PV_OPERAND_GLOBAL = 1,
    PV_OPERAND_IMMEDIATE = 2,
    PV_OPERAND_LABEL = 3,
};

typedef struct PvTcgCacheApi {
    void *module;
    PvTcgL2Handle *handle;

    PvTcgL2Handle *(*l2_open)(const char *, const char *, size_t, uint64_t);
    void (*l2_close)(PvTcgL2Handle *);
    int32_t (*l2_preload)(PvTcgL2Handle *, size_t, uint64_t);
    int32_t (*l2_flush)(PvTcgL2Handle *);
    int32_t (*l1_lookup_probe)(PvTcgL2Handle *,
                               const PvTcgCompatibility *,
                               PvTcgIrBlockHandle **);
    int32_t (*block_get_validation)(const PvTcgIrBlockHandle *,
                                    uint32_t *, uint8_t[32],
                                    uint64_t[2], size_t *);
    int32_t (*block_get_summary)(const PvTcgIrBlockHandle *,
                                 uint32_t *, uint16_t *,
                                 size_t *, size_t *, size_t *);
    int32_t (*block_get_temp)(const PvTcgIrBlockHandle *, size_t,
                              uint16_t *, uint16_t *, uint8_t *,
                              uint8_t *, uint8_t *, uint8_t *);
    int32_t (*block_get_label)(const PvTcgIrBlockHandle *, size_t,
                               uint16_t *, uint32_t *);
    int32_t (*block_get_operation)(const PvTcgIrBlockHandle *, size_t,
                                   const uint8_t **, size_t *,
                                   uint8_t *, uint8_t *, size_t *);
    int32_t (*block_get_operand)(const PvTcgIrBlockHandle *, size_t, size_t,
                                 uint8_t *, int64_t *);
    void (*block_free)(PvTcgIrBlockHandle *);

    PvTcgIrBlockHandle *(*block_create)(const PvTcgCompatibility *,
                                        uint32_t, uint16_t);
    int32_t (*block_add_temp)(PvTcgIrBlockHandle *, uint16_t, uint16_t,
                              uint8_t, uint8_t, uint8_t, uint8_t);
    int32_t (*block_add_label)(PvTcgIrBlockHandle *, uint16_t, uint32_t);
    int32_t (*block_add_operation)(PvTcgIrBlockHandle *,
                                   const char *, uint8_t, uint8_t);
    int32_t (*block_add_operand)(PvTcgIrBlockHandle *, uint32_t,
                                 uint8_t, int64_t);
    int32_t (*l2_store_block)(PvTcgL2Handle *,
                              const PvTcgIrBlockHandle *);
} PvTcgCacheApi;

typedef struct PvTcgCacheState {
    PvTcgCacheApi api;
    char *guest_arch;
    char *guest_cpu_model;
    char *guest_build_id;
    char *host_abi;
    char **host_features;
    size_t host_feature_count;
    bool trace;
} PvTcgCacheState;

static GMutex pv_init_lock;
static GCond pv_init_cond;
static gsize pv_start_once;
static gsize pv_sync_once;
static bool pv_init_done;
static PvTcgCacheState *pv_cache;

static gint pv_hits;
static gint pv_misses;
static gint pv_stores;
static gint pv_rejections;

static gint pv_string_ptr_compare(gconstpointer a, gconstpointer b,
                                  gpointer unused)
{
    (void)unused;
    return g_strcmp0(*(char * const *)a, *(char * const *)b);
}

static uint64_t pv_parse_u64(const char *text, uint64_t fallback)
{
    char *end = NULL;
    guint64 value;

    if (text == NULL || *text == '\0') {
        return fallback;
    }
    errno = 0;
    value = g_ascii_strtoull(text, &end, 10);
    if (errno != 0 || end == text || *end != '\0') {
        return fallback;
    }
    return value;
}

static void pv_load_symbol(void *module, const char *name,
                           void *function_storage, size_t function_size)
{
    void *symbol = dlsym(module, name);

    if (symbol == NULL || function_size != sizeof(symbol)) {
        memset(function_storage, 0, function_size);
        return;
    }
    memcpy(function_storage, &symbol, sizeof(symbol));
}

#define PV_LOAD(api, member, symbol) \
    pv_load_symbol((api)->module, (symbol), &(api)->member, \
                   sizeof((api)->member))

static bool pv_api_complete(const PvTcgCacheApi *api)
{
    return api->l2_open && api->l2_close && api->l2_preload &&
           api->l2_flush && api->l1_lookup_probe &&
           api->block_get_validation && api->block_get_summary &&
           api->block_get_temp && api->block_get_label &&
           api->block_get_operation && api->block_get_operand &&
           api->block_free && api->block_create && api->block_add_temp &&
           api->block_add_label && api->block_add_operation &&
           api->block_add_operand && api->l2_store_block;
}

static void pv_cache_shutdown(void)
{
    PvTcgCacheState *state = g_atomic_pointer_get(&pv_cache);

    if (state == NULL) {
        return;
    }
    if (state->api.handle != NULL) {
        state->api.l2_flush(state->api.handle);
        state->api.l2_close(state->api.handle);
        state->api.handle = NULL;
    }
    if (state->api.module != NULL) {
        dlclose(state->api.module);
        state->api.module = NULL;
    }
    g_atomic_pointer_set(&pv_cache, NULL);
}

static gpointer pv_cache_init_worker(gpointer unused)
{
    PvTcgCacheState *state = g_new0(PvTcgCacheState, 1);
    const char *library = g_getenv("PHOENIXVM_TCG_CACHE_LIBRARY");
    const char *root = g_getenv("PHOENIXVM_TCG_CACHE_DIR");
    const char *vm_id = g_getenv("PHOENIXVM_TCG_CACHE_VM_ID");
    const char *features = g_getenv("PHOENIXVM_TCG_HOST_FEATURES");
    char **feature_list = NULL;
    guint feature_count = 0;
    uint64_t max_entries, max_bytes;
    bool ok = false;
    const char *failure = "unknown initialization failure";

    (void)unused;

    state->guest_arch = g_strdup(g_getenv("PHOENIXVM_GUEST_ARCH"));
    state->guest_cpu_model = g_strdup(g_getenv("PHOENIXVM_GUEST_CPU_MODEL"));
    state->guest_build_id = g_strdup(g_getenv("PHOENIXVM_GUEST_BUILD_ID"));
    state->host_abi = g_strdup(g_getenv("PHOENIXVM_TCG_HOST_ABI"));
    state->trace = g_strcmp0(g_getenv("PHOENIXVM_TCG_CACHE_TRACE"), "1") == 0;

    if (library == NULL || root == NULL || vm_id == NULL ||
        state->guest_arch == NULL || state->guest_cpu_model == NULL ||
        state->guest_build_id == NULL || state->host_abi == NULL ||
        features == NULL || *features == '\0') {
        goto out;
    }

    feature_list = g_strsplit(features, ",", PV_MAX_FEATURES + 1);
    feature_count = g_strv_length(feature_list);
    if (feature_count == 0 || feature_count > PV_MAX_FEATURES) {
        failure = "host CPU feature list is empty or exceeds its limit";
        goto out;
    }
    g_qsort_with_data(feature_list, feature_count, sizeof(char *),
                      pv_string_ptr_compare, NULL);
    for (guint i = 0; i < feature_count; i++) {
        if (feature_list[i] == NULL || *feature_list[i] == '\0' ||
            (i > 0 && g_str_equal(feature_list[i - 1], feature_list[i]))) {
            failure = "host CPU feature list contains an empty or duplicate value";
            goto out;
        }
    }

    state->host_features = feature_list;
    state->host_feature_count = feature_count;
    feature_list = NULL;

    state->api.module = dlopen(library, RTLD_NOW | RTLD_LOCAL);
    if (state->api.module == NULL) {
        failure = dlerror();
        goto out;
    }

    PV_LOAD(&state->api, l2_open, "phoenixvm_tcg_l2_open");
    PV_LOAD(&state->api, l2_close, "phoenixvm_tcg_l2_close");
    PV_LOAD(&state->api, l2_preload, "phoenixvm_tcg_l2_preload");
    PV_LOAD(&state->api, l2_flush, "phoenixvm_tcg_l2_flush");
    PV_LOAD(&state->api, l1_lookup_probe, "phoenixvm_tcg_l1_lookup_probe");
    PV_LOAD(&state->api, block_get_validation, "phoenixvm_tcg_ir_block_get_validation");
    PV_LOAD(&state->api, block_get_summary, "phoenixvm_tcg_ir_block_get_summary");
    PV_LOAD(&state->api, block_get_temp, "phoenixvm_tcg_ir_block_get_temp");
    PV_LOAD(&state->api, block_get_label, "phoenixvm_tcg_ir_block_get_label");
    PV_LOAD(&state->api, block_get_operation, "phoenixvm_tcg_ir_block_get_operation");
    PV_LOAD(&state->api, block_get_operand, "phoenixvm_tcg_ir_block_get_operand");
    PV_LOAD(&state->api, block_free, "phoenixvm_tcg_ir_block_free");
    PV_LOAD(&state->api, block_create, "phoenixvm_tcg_ir_block_create");
    PV_LOAD(&state->api, block_add_temp, "phoenixvm_tcg_ir_block_add_temp");
    PV_LOAD(&state->api, block_add_label, "phoenixvm_tcg_ir_block_add_label");
    PV_LOAD(&state->api, block_add_operation, "phoenixvm_tcg_ir_block_add_operation");
    PV_LOAD(&state->api, block_add_operand, "phoenixvm_tcg_ir_block_add_operand");
    PV_LOAD(&state->api, l2_store_block, "phoenixvm_tcg_l2_store_block");

    if (!pv_api_complete(&state->api)) {
        failure = "native cache library is missing one or more required C ABI symbols";
        goto out;
    }

    max_entries = pv_parse_u64(g_getenv("PHOENIXVM_TCG_CACHE_MAX_ENTRIES"), 512);
    max_bytes = pv_parse_u64(g_getenv("PHOENIXVM_TCG_CACHE_MAX_BYTES"),
                             64 * 1024 * 1024);
    max_entries = CLAMP(max_entries, 1, 8192);
    max_bytes = CLAMP(max_bytes, 1, 256 * 1024 * 1024);

    state->api.handle = state->api.l2_open(root, vm_id,
                                           (size_t)max_entries, max_bytes);
    if (state->api.handle == NULL) {
        failure = "native L2 cache store could not be opened";
        goto out;
    }
    if (state->api.l2_preload(state->api.handle, (size_t)max_entries,
                              max_bytes) != 0) {
        failure = "native L2 preload failed";
        goto out;
    }

    ok = true;

out:
    g_strfreev(feature_list);
    if (!ok) {
        if (state->trace) {
            g_printerr("PVTCG: initialization disabled: %s\\n",
                       failure != NULL ? failure : "unknown");
        }
        if (state->api.handle != NULL && state->api.l2_close != NULL) {
            state->api.l2_close(state->api.handle);
            state->api.handle = NULL;
        }
        if (state->api.module != NULL) {
            dlclose(state->api.module);
            state->api.module = NULL;
        }
        g_free(state->guest_arch);
        g_free(state->guest_cpu_model);
        g_free(state->guest_build_id);
        g_free(state->host_abi);
        g_strfreev(state->host_features);
        g_free(state);
        state = NULL;
    }

    g_mutex_lock(&pv_init_lock);
    pv_init_done = true;
    g_atomic_pointer_set(&pv_cache, state);
    g_cond_broadcast(&pv_init_cond);
    g_mutex_unlock(&pv_init_lock);

    return NULL;
}

void phoenixvm_tcg_cache_start(void)
{
    g_printerr("PVTCG: start hook reached trace=%s\\n",
               g_getenv("PHOENIXVM_TCG_CACHE_TRACE") != NULL
                   ? g_getenv("PHOENIXVM_TCG_CACHE_TRACE") : "<unset>");
    if (g_once_init_enter(&pv_sync_once)) {
        g_mutex_init(&pv_init_lock);
        g_cond_init(&pv_init_cond);
        g_once_init_leave(&pv_sync_once, 1);
    }
    if (g_once_init_enter(&pv_start_once)) {
        g_thread_unref(g_thread_new("phoenixvm-tcg-cache",
                                    pv_cache_init_worker, NULL));
        atexit(pv_cache_shutdown);
        g_once_init_leave(&pv_start_once, 1);
    }

    /* This is called from tcg_init_machine(), before any vCPU can execute. */
    g_mutex_lock(&pv_init_lock);
    while (!pv_init_done) {
        g_cond_wait(&pv_init_cond, &pv_init_lock);
    }
    g_mutex_unlock(&pv_init_lock);
    {
        PvTcgCacheState *state = g_atomic_pointer_get(&pv_cache);
        g_printerr("PVTCG: init complete enabled=%d trace=%d\\n",
                   state != NULL, state != NULL && state->trace);
    }
}

static void pv_checksum_u64(GChecksum *sum, uint64_t value)
{
    uint64_t be = GUINT64_TO_BE(value);
    g_checksum_update(sum, (const guchar *)&be, sizeof(be));
}

static void pv_checksum_string(GChecksum *sum, const char *value)
{
    size_t len = value == NULL ? 0 : strlen(value);

    pv_checksum_u64(sum, len);
    if (len != 0) {
        g_checksum_update(sum, (const guchar *)value, len);
    }
}

static bool pv_global_layout_digest(uint8_t digest[32])
{
    GChecksum *sum = g_checksum_new(G_CHECKSUM_SHA256);
    gsize digest_len = 32;

    if (sum == NULL) {
        if (g_strcmp0(g_getenv("PHOENIXVM_TCG_CACHE_TRACE"), "1") == 0) {
            g_printerr("PVTCG: global-layout digest allocation failed\n");
        }
        return false;
    }

    if (g_strcmp0(g_getenv("PHOENIXVM_TCG_CACHE_TRACE"), "1") == 0) {
        g_printerr("PVTCG: global-layout begin globals=%d temps=%d frame_temp=%p temp_base=%p temp_size=%zu\n",
                   tcg_ctx->nb_globals, tcg_ctx->nb_temps,
                   (void *)tcg_ctx->frame_temp, (void *)tcg_ctx->temps,
                   sizeof(TCGTemp));
    }

    pv_checksum_u64(sum, tcg_ctx->nb_globals);
    pv_checksum_u64(sum, tcg_ctx->nb_indirects);
    pv_checksum_u64(sum, tcg_ctx->addr_type);
    pv_checksum_u64(sum, tcg_ctx->guest_mo);
    pv_checksum_u64(sum, tcg_ctx->reserved_regs);
    pv_checksum_u64(sum, (uint64_t)tcg_ctx->current_frame_offset);
    pv_checksum_u64(sum, (uint64_t)tcg_ctx->frame_start);
    pv_checksum_u64(sum, (uint64_t)tcg_ctx->frame_end);

    if (tcg_ctx->frame_temp != NULL) {
        uintptr_t base = (uintptr_t)tcg_ctx->temps;
        uintptr_t ptr = (uintptr_t)tcg_ctx->frame_temp;
        uintptr_t end = base + sizeof(TCGTemp) * TCG_MAX_TEMPS;

        if (ptr < base || ptr >= end ||
            ((ptr - base) % sizeof(TCGTemp)) != 0) {
            if (g_strcmp0(g_getenv("PHOENIXVM_TCG_CACHE_TRACE"), "1") == 0) {
                g_printerr("PVTCG: invalid frame_temp pointer=%p range=[%p,%p) delta=%" PRIuPTR " struct_size=%zu\n",
                           (void *)ptr, (void *)base, (void *)end,
                           ptr >= base ? ptr - base : 0, sizeof(TCGTemp));
            }
            g_checksum_free(sum);
            return false;
        }
        pv_checksum_u64(sum, (ptr - base) / sizeof(TCGTemp));
    } else {
        pv_checksum_u64(sum, UINT64_MAX);
    }

    for (int i = 0; i < tcg_ctx->nb_globals; i++) {
        const TCGTemp *ts = &tcg_ctx->temps[i];
        uint64_t mem_base_index = UINT64_MAX;

        if (ts->mem_base != NULL) {
            uintptr_t base = (uintptr_t)tcg_ctx->temps;
            uintptr_t ptr = (uintptr_t)ts->mem_base;
            uintptr_t end = base + sizeof(TCGTemp) * TCG_MAX_TEMPS;

            if (ptr < base || ptr >= end ||
                ((ptr - base) % sizeof(TCGTemp)) != 0) {
                if (g_strcmp0(g_getenv("PHOENIXVM_TCG_CACHE_TRACE"), "1") == 0) {
                    g_printerr("PVTCG: invalid mem_base global=%d name=%s pointer=%p range=[%p,%p) delta=%" PRIuPTR " struct_size=%zu\n",
                               i, ts->name != NULL ? ts->name : "<unnamed>",
                               (void *)ptr, (void *)base, (void *)end,
                               ptr >= base ? ptr - base : 0, sizeof(TCGTemp));
                }
                g_checksum_free(sum);
                return false;
            }
            mem_base_index = (ptr - base) / sizeof(TCGTemp);
        }

        pv_checksum_u64(sum, i);
        pv_checksum_u64(sum, ts->reg);
        pv_checksum_u64(sum, ts->val_type);
        pv_checksum_u64(sum, ts->base_type);
        pv_checksum_u64(sum, ts->type);
        pv_checksum_u64(sum, ts->kind);
        pv_checksum_u64(sum, ts->indirect_reg);
        pv_checksum_u64(sum, ts->indirect_base);
        pv_checksum_u64(sum, ts->mem_coherent);
        pv_checksum_u64(sum, ts->mem_allocated);
        pv_checksum_u64(sum, ts->temp_allocated);
        pv_checksum_u64(sum, ts->temp_subindex);
        pv_checksum_u64(sum, mem_base_index);
        pv_checksum_u64(sum, (uint64_t)ts->mem_offset);
        pv_checksum_string(sum, ts->name);
    }

    g_checksum_get_digest(sum, digest, &digest_len);
    g_checksum_free(sum);
    if (g_strcmp0(g_getenv("PHOENIXVM_TCG_CACHE_TRACE"), "1") == 0) {
        g_printerr("PVTCG: global-layout complete digest_len=%zu\n", digest_len);
    }
    return digest_len == 32;
}

static const char *pv_host_arch(void)
{
#if defined(__aarch64__)
    return "aarch64";
#elif defined(__x86_64__)
    return "x86_64";
#elif defined(__arm__)
    return "arm";
#elif defined(__i386__)
    return "i386";
#elif defined(__riscv) && __riscv_xlen == 64
    return "riscv64";
#else
    return "unknown";
#endif
}

static bool pv_fill_compatibility(PvTcgCacheState *state,
                                  CPUState *cpu,
                                  TranslationBlock *tb,
                                  vaddr pc,
                                  uint64_t physical_page,
                                  const uint8_t code_hash[32],
                                  uint8_t context_hash[32],
                                  uint64_t *page_storage,
                                  PvTcgCompatibility *key)
{
    static const char *build_id = PV_QEMU_BUILD_ID;
#ifdef CONFIG_POSIX
    int host_page_size = getpagesize();
#else
    int host_page_size = 4096;
#endif

    if (host_page_size <= 0) {
        if (state->trace) {
            g_printerr("PVTCG: invalid host page size=%d\n", host_page_size);
        }
        return false;
    }
    if (!pv_global_layout_digest(context_hash)) {
        if (state->trace) {
            g_printerr("PVTCG: global-layout digest rejected\n");
        }
        return false;
    }

    *page_storage = physical_page;
    memset(key, 0, sizeof(*key));
    key->qemu_build_id = build_id;
    key->guest_arch = state->guest_arch;
    key->guest_cpu_model = state->guest_cpu_model;
    key->guest_build_id = state->guest_build_id;
    key->host_arch = pv_host_arch();
    key->host_abi = state->host_abi;
#if HOST_BIG_ENDIAN
    key->host_endian = 1;
#else
    key->host_endian = 0;
#endif
    key->host_page_size = (uint32_t)host_page_size;
    key->host_cpu_features = (const char * const *)state->host_features;
    key->host_cpu_features_len = state->host_feature_count;
    key->guest_pc = pc;
    key->guest_cs_base = tb->cs_base;
    key->tb_flags = tb->flags;
    key->tb_cflags = tb_cflags(tb);
    key->mmu_index = cpu_mmu_index(cpu, true);
    key->physical_pages = page_storage;
    key->physical_pages_len = 1;
    key->guest_code_sha256 = code_hash;
    key->tb_context_sha256 = context_hash;
    return true;
}

static bool pv_hash_guest_code(const void *host_pc, uint32_t guest_size,
                               uint8_t digest[32])
{
    GChecksum *sum;
    gsize digest_len = 32;

    if (host_pc == NULL || guest_size == 0) {
        return false;
    }
    sum = g_checksum_new(G_CHECKSUM_SHA256);
    if (sum == NULL) {
        return false;
    }
    g_checksum_update(sum, host_pc, guest_size);
    g_checksum_get_digest(sum, digest, &digest_len);
    g_checksum_free(sum);
    return digest_len == 32;
}

static bool pv_tb_eligible(TranslationBlock *tb, vaddr pc, void *host_pc)
{
    uint32_t disallowed = CF_USE_ICOUNT | CF_SINGLE_STEP |
                          CF_MEMI_ONLY | CF_BP_PAGE | CF_STEP_ATOMIC;

    if (host_pc == NULL || tb_page_addr0(tb) == -1 ||
        tb_page_addr1(tb) != -1 ||
        (tb_cflags(tb) & disallowed) != 0) {
        return false;
    }
#ifdef CONFIG_PLUGIN
    if (tcg_ctx->plugin_tb != NULL || tcg_ctx->plugin_db != NULL) {
        return false;
    }
#endif
    return true;
}

static bool pv_name_is(const uint8_t *name, size_t len, const char *wanted)
{
    return strlen(wanted) == len && memcmp(name, wanted, len) == 0;
}

static bool pv_find_opcode(const uint8_t *name, size_t len, TCGOpcode *opcode)
{
    for (unsigned i = 0; i < tcg_op_defs_max; i++) {
        const char *candidate = tcg_op_defs[i].name;
        if (candidate != NULL && pv_name_is(name, len, candidate)) {
            *opcode = (TCGOpcode)i;
            return true;
        }
    }
    return false;
}

static bool pv_read_operand(PvTcgCacheApi *api,
                            const PvTcgIrBlockHandle *block,
                            size_t op_index, size_t operand_index,
                            uint8_t *tag, int64_t *value)
{
    return api->block_get_operand(block, op_index, operand_index,
                                  tag, value) == 0;
}

static TCGTemp *pv_restore_temp(PvTcgCacheApi *api,
                                const PvTcgIrBlockHandle *block,
                                size_t temp_index,
                                TCGTemp **temps,
                                size_t temp_count)
{
    uint16_t id, base_id;
    uint8_t subindex, base_type, value_type, kind;
    TCGTempKind qemu_kind;
    TCGTemp *temp;

    if (api->block_get_temp(block, temp_index, &id, &base_id, &subindex,
                            &base_type, &value_type, &kind) != 0 ||
        id != temp_index || base_id != id || subindex != 0 ||
        id >= temp_count || base_type != value_type ||
        (base_type != TCG_TYPE_I32 && base_type != TCG_TYPE_I64) ||
        kind > 1) {
        return NULL;
    }

    qemu_kind = kind == 0 ? TEMP_TB : TEMP_EBB;
    temp = tcg_temp_new_internal((TCGType)base_type, qemu_kind);
    if (temp == NULL || temp->base_type != base_type ||
        temp->type != value_type || temp->kind != qemu_kind ||
        temp->temp_subindex != 0) {
        return NULL;
    }
    temps[id] = temp;
    return temp;
}

static TCGTemp *pv_restore_value(PvTcgCacheApi *api,
                                 const PvTcgIrBlockHandle *block,
                                 size_t op_index,
                                 size_t operand_index,
                                 TCGType type,
                                 TCGTemp **temps,
                                 size_t temp_count,
                                 bool output)
{
    uint8_t tag;
    int64_t value;
    TCGTemp *temp;

    if (!pv_read_operand(api, block, op_index, operand_index, &tag, &value)) {
        return NULL;
    }

    switch (tag) {
    case PV_OPERAND_TEMP:
        if (value < 0 || (uint64_t)value >= temp_count) {
            return NULL;
        }
        temp = temps[value];
        if (temp == NULL || temp->base_type != type ||
            temp->type != type || temp->temp_subindex != 0) {
            return NULL;
        }
        return temp;
    case PV_OPERAND_GLOBAL:
        if (value < 0 || value >= tcg_ctx->nb_globals) {
            return NULL;
        }
        temp = &tcg_ctx->temps[value];
        if (temp->base_type != type || temp->type != type) {
            return NULL;
        }
        return temp;
    case PV_OPERAND_IMMEDIATE:
        if (output || (type != TCG_TYPE_I32 && type != TCG_TYPE_I64)) {
            return NULL;
        }
        if (type == TCG_TYPE_I32) {
            temp = tcg_constant_internal(type, (int32_t)value);
        } else {
            temp = tcg_constant_internal(type, value);
        }
        return temp;
    default:
        return NULL;
    }
}

static bool pv_restore_block(PvTcgCacheApi *api,
                             const PvTcgIrBlockHandle *block,
                             TranslationBlock *tb,
                             uint32_t guest_size,
                             uint16_t instruction_count,
                             size_t temp_count,
                             size_t label_count,
                             size_t operation_count)
{
    TCGTemp *temps[TCG_MAX_TEMPS] = { 0 };
    TCGLabel **labels = NULL;
    bool ok = false;

    if (temp_count > TCG_MAX_TEMPS ||
        label_count > PV_MAX_LABELS ||
        operation_count == 0 ||
        operation_count > PV_MAX_IR_OPERATIONS ||
        instruction_count == 0 ||
        guest_size == 0) {
        return false;
    }

    for (size_t i = 0; i < temp_count; i++) {
        if (pv_restore_temp(api, block, i, temps, temp_count) == NULL) {
            goto out;
        }
    }

    labels = g_new0(TCGLabel *, MAX(label_count, 1));
    for (size_t i = 0; i < label_count; i++) {
        uint16_t id;
        uint32_t operation_index;

        if (api->block_get_label(block, i, &id, &operation_index) != 0 ||
            id != i || operation_index >= operation_count) {
            goto out;
        }
        labels[id] = gen_new_label();
    }

    for (size_t i = 0; i < operation_count; i++) {
        const uint8_t *name;
        size_t name_len, operand_count;
        uint8_t param1, param2;
        TCGOpcode opcode;

        if (api->block_get_operation(block, i, &name, &name_len,
                                     &param1, &param2, &operand_count) != 0 ||
            name == NULL || name_len == 0 ||
            !pv_find_opcode(name, name_len, &opcode)) {
            goto out;
        }

        if (pv_name_is(name, name_len, "insn_start")) {
            int64_t words[INSN_START_WORDS];

            if (operand_count != INSN_START_WORDS ||
                param1 != 0 || param2 != 0) {
                goto out;
            }
            for (size_t j = 0; j < INSN_START_WORDS; j++) {
                uint8_t tag;
                if (!pv_read_operand(api, block, i, j, &tag, &words[j]) ||
                    tag != PV_OPERAND_IMMEDIATE) {
                    goto out;
                }
            }
            tcg_gen_insn_start((uint64_t)words[0],
                               (uint64_t)words[1],
                               (uint64_t)words[2]);
            continue;
        }

        if (pv_name_is(name, name_len, "br") ||
            pv_name_is(name, name_len, "set_label")) {
            uint8_t tag;
            int64_t label_id;

            if (operand_count != 1 || param1 != 0 || param2 != 0 ||
                !pv_read_operand(api, block, i, 0, &tag, &label_id) ||
                tag != PV_OPERAND_LABEL || label_id < 0 ||
                label_id >= label_count || labels[label_id] == NULL) {
                goto out;
            }
            if (pv_name_is(name, name_len, "br")) {
                tcg_gen_br(labels[label_id]);
            } else {
                gen_set_label(labels[label_id]);
            }
            continue;
        }

        if (pv_name_is(name, name_len, "goto_tb")) {
            uint8_t tag;
            int64_t index;

            if (operand_count != 1 || param1 != 0 || param2 != 0 ||
                !pv_read_operand(api, block, i, 0, &tag, &index) ||
                tag != PV_OPERAND_IMMEDIATE || index < 0 || index > 1) {
                goto out;
            }
            tcg_gen_goto_tb((unsigned)index);
            continue;
        }

        if (pv_name_is(name, name_len, "exit_tb")) {
            uint8_t tag;
            int64_t index;

            if (operand_count != 1 || param1 != 0 || param2 != 0 ||
                !pv_read_operand(api, block, i, 0, &tag, &index) ||
                tag != PV_OPERAND_IMMEDIATE ||
                (index != 0 && index != 1 && index != 3)) {
                goto out;
            }
            tcg_gen_exit_tb(tb, (unsigned)index);
            continue;
        }

        {
            const TCGOpDef *def = &tcg_op_defs[opcode];
            TCGOp *op;
            TCGType type = (TCGType)param1;
            size_t expected = def->nb_oargs + def->nb_iargs + def->nb_cargs;

            if (param2 != 0 || operand_count != expected ||
                (type != TCG_TYPE_I32 && type != TCG_TYPE_I64)) {
                goto out;
            }

            if (pv_name_is(name, name_len, "mov") ||
                pv_name_is(name, name_len, "add") ||
                pv_name_is(name, name_len, "sub") ||
                pv_name_is(name, name_len, "and") ||
                pv_name_is(name, name_len, "or") ||
                pv_name_is(name, name_len, "xor") ||
                pv_name_is(name, name_len, "mul")) {
                TCGArg args[3];

                if (def->nb_oargs != 1 || def->nb_cargs != 0 ||
                    def->nb_args != expected || expected > ARRAY_SIZE(args) ||
                    (pv_name_is(name, name_len, "mov") && def->nb_iargs != 1) ||
                    (!pv_name_is(name, name_len, "mov") && def->nb_iargs != 2)) {
                    goto out;
                }
                for (size_t j = 0; j < expected; j++) {
                    TCGTemp *value = pv_restore_value(api, block, i, j, type,
                                                      temps, temp_count,
                                                      j < def->nb_oargs);
                    if (value == NULL) {
                        goto out;
                    }
                    args[j] = temp_arg(value);
                }
                op = tcg_emit_op(opcode, def->nb_args);
                TCGOP_TYPE(op) = type;
                TCGOP_FLAGS(op) = param2;
                for (size_t j = 0; j < expected; j++) {
                    op->args[j] = args[j];
                }
                continue;
            }
        }

        goto out;
    }

    tb->size = guest_size;
    tb->icount = instruction_count;
    ok = true;

out:
    g_free(labels);
    return ok;
}

bool phoenixvm_tcg_cache_try_restore(CPUState *cpu,
                                     TranslationBlock *tb,
                                     vaddr pc,
                                     void *host_pc,
                                     int *max_insns)
{
    PvTcgCacheState *state = g_atomic_pointer_get(&pv_cache);
    PvTcgCompatibility probe;
    PvTcgIrBlockHandle *block = NULL;
    uint8_t zero_hash[32] = { 0 };
    uint8_t context_hash[32];
    uint64_t page_storage = 0;
    uint8_t stored_hash[32];
    uint8_t current_hash[32];
    uint64_t stored_pages[2] = { 0 };
    size_t stored_page_count = 0;
    uint32_t guest_size = 0;
    uint16_t instruction_count = 0;
    size_t temp_count = 0, label_count = 0, operation_count = 0;
    uint64_t physical_page;
    bool restored = false;

    if (state != NULL && state->trace) {
        g_printerr("PVTCG: restore hook pc=0x%" PRIx64 " state=ready\\n",
                   (uint64_t)pc);
    }
    if (state == NULL || !pv_tb_eligible(tb, pc, host_pc)) {
        if (state != NULL && state->trace) {
            g_printerr("PVTCG: ineligible TB pc=0x%" PRIx64
                       " phys0=0x%" PRIx64 " phys1=0x%" PRIx64
                       " cflags=0x%x\\n",
                       (uint64_t)pc, (uint64_t)tb_page_addr0(tb),
                       (uint64_t)tb_page_addr1(tb), tb_cflags(tb));
        }
        g_atomic_int_inc(&pv_misses);
        return false;
    }
    physical_page = tb_page_addr0(tb) & TARGET_PAGE_MASK;
    if (!pv_fill_compatibility(state, cpu, tb, pc, physical_page,
                               zero_hash, context_hash, &page_storage,
                               &probe)) {
        g_atomic_int_inc(&pv_misses);
        return false;
    }

    if (state->api.l1_lookup_probe(state->api.handle, &probe, &block) != 1 ||
        block == NULL) {
        g_atomic_int_inc(&pv_misses);
        return false;
    }

    if (state->api.block_get_validation(block, &guest_size, stored_hash,
                                          stored_pages,
                                          &stored_page_count) != 0 ||
        stored_page_count != 1 ||
        stored_pages[0] != physical_page ||
        guest_size == 0 ||
        guest_size > TARGET_PAGE_SIZE - (pc & (TARGET_PAGE_SIZE - 1)) ||
        !pv_hash_guest_code(host_pc, guest_size, current_hash) ||
        memcmp(stored_hash, current_hash, sizeof(stored_hash)) != 0) {
        state->api.block_free(block);
        g_atomic_int_inc(&pv_misses);
        return false;
    }

    if (state->api.block_get_summary(block, &guest_size, &instruction_count,
                                     &temp_count, &label_count,
                                     &operation_count) != 0 ||
        instruction_count == 0 ||
        (*max_insns > 0 && instruction_count > (uint16_t)*max_insns)) {
        state->api.block_free(block);
        g_atomic_int_inc(&pv_misses);
        return false;
    }

    restored = pv_restore_block(&state->api, block, tb, guest_size,
                                instruction_count, temp_count,
                                label_count, operation_count);
    state->api.block_free(block);
    if (!restored) {
        g_atomic_int_inc(&pv_rejections);
        g_atomic_int_inc(&pv_misses);
        return false;
    }

    *max_insns = instruction_count;
    g_atomic_int_inc(&pv_hits);
    if (state->trace) {
        g_printerr("PVTCG: hit pc=0x%" PRIx64 " size=%u insns=%u\n",
                   (uint64_t)pc, guest_size, instruction_count);
    }
    return true;
}

static bool pv_supported_operation(const TCGOp *op, const TCGOpDef **out_def)
{
    const TCGOpDef *def;

    if (op->opc >= tcg_op_defs_max) {
        return false;
    }
    def = &tcg_op_defs[op->opc];
    if (def->name == NULL || op->nargs < def->nb_args) {
        return false;
    }

    if (strcmp(def->name, "insn_start") == 0) {
        if (def->nb_oargs != 0 || def->nb_iargs != 0 ||
            def->nb_cargs != INSN_START_WORDS ||
            op->param1 != 0 || op->param2 != 0) {
            return false;
        }
    } else if (strcmp(def->name, "mov") == 0) {
        if (def->nb_oargs != 1 || def->nb_iargs != 1 ||
            def->nb_cargs != 0 ||
            (op->param1 != TCG_TYPE_I32 && op->param1 != TCG_TYPE_I64) ||
            op->param2 != 0) {
            return false;
        }
    } else if (strcmp(def->name, "add") == 0 ||
               strcmp(def->name, "sub") == 0 ||
               strcmp(def->name, "and") == 0 ||
               strcmp(def->name, "or") == 0 ||
               strcmp(def->name, "xor") == 0 ||
               strcmp(def->name, "mul") == 0) {
        if (def->nb_oargs != 1 || def->nb_iargs != 2 ||
            def->nb_cargs != 0 ||
            (op->param1 != TCG_TYPE_I32 && op->param1 != TCG_TYPE_I64) ||
            op->param2 != 0) {
            return false;
        }
    } else if (strcmp(def->name, "br") == 0 ||
               strcmp(def->name, "set_label") == 0) {
        if (def->nb_oargs != 0 || def->nb_iargs != 0 ||
            def->nb_cargs != 1 || op->param1 != 0 || op->param2 != 0) {
            return false;
        }
    } else if (strcmp(def->name, "goto_tb") == 0 ||
               strcmp(def->name, "exit_tb") == 0) {
        if (def->nb_oargs != 0 || def->nb_iargs != 0 ||
            def->nb_cargs != 1 || op->param1 != 0 || op->param2 != 0) {
            return false;
        }
    } else {
        return false;
    }

    *out_def = def;
    return true;
}

static bool pv_temp_index(TCGTemp *temp, size_t *index)
{
    uintptr_t base = (uintptr_t)tcg_ctx->temps;
    uintptr_t ptr = (uintptr_t)temp;
    uintptr_t end = base + sizeof(TCGTemp) * TCG_MAX_TEMPS;

    if (ptr < base || ptr >= end ||
        ((ptr - base) % sizeof(TCGTemp)) != 0) {
        return false;
    }
    *index = (ptr - base) / sizeof(TCGTemp);
    return *index < (size_t)tcg_ctx->nb_temps;
}

static bool pv_prepare_temp(TCGTemp *temp, bool output,
                            uint16_t *temp_map,
                            TCGTemp **temp_by_id,
                            size_t *temp_count)
{
    size_t index;

    if (!pv_temp_index(temp, &index)) {
        return false;
    }
    if (index < (size_t)tcg_ctx->nb_globals) {
        return true;
    }
    if (temp->kind == TEMP_CONST) {
        return !output;
    }
    if ((temp->kind != TEMP_TB && temp->kind != TEMP_EBB) ||
        temp->temp_subindex != 0 ||
        temp->base_type != temp->type ||
        (temp->base_type != TCG_TYPE_I32 &&
         temp->base_type != TCG_TYPE_I64) ||
        *temp_count >= TCG_MAX_TEMPS) {
        return false;
    }
    if (temp_map[index] == PV_TEMP_UNMAPPED) {
        temp_map[index] = (uint16_t)*temp_count;
        temp_by_id[*temp_count] = temp;
        (*temp_count)++;
    }
    return true;
}

static bool pv_add_temp_operand(PvTcgCacheApi *api,
                                PvTcgIrBlockHandle *block,
                                uint32_t op_index,
                                TCGTemp *temp,
                                bool output,
                                const uint16_t *temp_map)
{
    size_t index;
    uint8_t tag;
    int64_t value;

    if (!pv_temp_index(temp, &index)) {
        return false;
    }
    if (index < (size_t)tcg_ctx->nb_globals) {
        tag = PV_OPERAND_GLOBAL;
        value = index;
    } else if (temp->kind == TEMP_CONST) {
        if (output) {
            return false;
        }
        tag = PV_OPERAND_IMMEDIATE;
        value = temp->val;
    } else {
        if (temp_map[index] == PV_TEMP_UNMAPPED) {
            return false;
        }
        tag = PV_OPERAND_TEMP;
        value = temp_map[index];
    }

    return api->block_add_operand(block, op_index, tag, value) == 0;
}

static bool pv_add_immediate(PvTcgCacheApi *api,
                             PvTcgIrBlockHandle *block,
                             uint32_t op_index,
                             int64_t value)
{
    return api->block_add_operand(block, op_index,
                                  PV_OPERAND_IMMEDIATE, value) == 0;
}

static bool pv_serialize_translation(PvTcgCacheState *state,
                                     CPUState *cpu,
                                     TranslationBlock *tb,
                                     vaddr pc,
                                     void *host_pc)
{
    PvTcgCompatibility key;
    uint8_t code_hash[32];
    uint8_t context_hash[32];
    uint64_t page_storage = 0;
    uint16_t *temp_map = NULL;
    TCGTemp *temp_by_id[TCG_MAX_TEMPS] = { 0 };
    uint16_t *label_map = NULL;
    uint32_t *label_definitions = NULL;
    PvTcgIrBlockHandle *block = NULL;
    TCGOp *op;
    size_t temp_count = 0;
    size_t label_count = 0;
    uint32_t operation_count = 0;
    uint64_t physical_page;
    const char *failure = "unclassified-validation-failure";
    int32_t store_status = -999;
    bool ok = false;

    if (!pv_tb_eligible(tb, pc, host_pc) ||
        tb->size == 0 || tb->icount == 0 ||
        tb->size > TARGET_PAGE_SIZE - (pc & (TARGET_PAGE_SIZE - 1))) {
        failure = "tb-ineligible-or-crosses-code-page";
        goto out;
    }

    physical_page = tb_page_addr0(tb) & TARGET_PAGE_MASK;
    if (!pv_hash_guest_code(host_pc, tb->size, code_hash)) {
        failure = "guest-code-hash-failed";
        goto out;
    }
    if (!pv_fill_compatibility(state, cpu, tb, pc, physical_page,
                               code_hash, context_hash, &page_storage,
                               &key)) {
        failure = "compatibility-context-build-failed";
        goto out;
    }

    temp_map = g_new(uint16_t, MAX(tcg_ctx->nb_temps, 1));
    for (int i = 0; i < tcg_ctx->nb_temps; i++) {
        temp_map[i] = PV_TEMP_UNMAPPED;
    }
    label_map = g_new(uint16_t, MAX(tcg_ctx->nb_labels, 1));
    label_definitions = g_new(uint32_t, MAX(tcg_ctx->nb_labels, 1));
    for (int i = 0; i < tcg_ctx->nb_labels; i++) {
        label_map[i] = PV_LABEL_UNMAPPED;
        label_definitions[i] = UINT32_MAX;
    }

    /* Pass 1: fail closed, gather logical temporaries and label definitions. */
    QTAILQ_FOREACH(op, &tcg_ctx->ops, link) {
        const TCGOpDef *def;

        if (!pv_supported_operation(op, &def)) {
            failure = "unsupported-tcg-opcode";
            if (state->trace) {
                const char *op_name = op->opc < tcg_op_defs_max
                    ? tcg_op_defs[op->opc].name : NULL;
                g_printerr("PVTCG: serialize reject pc=0x%" PRIx64
                           " unsupported opcode=%s opc=%u\\n",
                           (uint64_t)pc, op_name != NULL ? op_name : "<invalid>",
                           (unsigned)op->opc);
            }
            goto out;
        }
        if (strcmp(def->name, "br") == 0 ||
            strcmp(def->name, "set_label") == 0) {
            TCGLabel *label = arg_label(op->args[0]);

            if (label == NULL || label->id >= tcg_ctx->nb_labels) {
                goto out;
            }
            if (strcmp(def->name, "set_label") == 0) {
                if (label_map[label->id] != PV_LABEL_UNMAPPED ||
                    label_count >= PV_MAX_LABELS) {
                    goto out;
                }
                label_map[label->id] = (uint16_t)label_count;
                label_definitions[label_count] = operation_count;
                label_count++;
            }
        } else {
            for (unsigned i = 0; i < def->nb_oargs + def->nb_iargs; i++) {
                TCGTemp *temp = arg_temp(op->args[i]);
                if (!pv_prepare_temp(temp, i < def->nb_oargs,
                                     temp_map, temp_by_id, &temp_count)) {
                    goto out;
                }
            }
        }
        operation_count++;
        if (operation_count > PV_MAX_IR_OPERATIONS) {
            goto out;
        }
    }

    /* Every branch must refer to a label that is actually defined in this TB. */
    QTAILQ_FOREACH(op, &tcg_ctx->ops, link) {
        const TCGOpDef *def = &tcg_op_defs[op->opc];

        if (strcmp(def->name, "br") == 0) {
            TCGLabel *label = arg_label(op->args[0]);
            if (label == NULL || label->id >= tcg_ctx->nb_labels ||
                label_map[label->id] == PV_LABEL_UNMAPPED) {
                goto out;
            }
        }
    }

    block = state->api.block_create(&key, tb->size, tb->icount);
    if (block == NULL) {
        goto out;
    }

    for (size_t i = 0; i < temp_count; i++) {
        TCGTemp *temp = temp_by_id[i];
        uint8_t kind = temp->kind == TEMP_TB ? 0 : 1;

        if (state->api.block_add_temp(block, (uint16_t)i, (uint16_t)i,
                                      0, temp->base_type, temp->type,
                                      kind) != 0) {
            goto out;
        }
    }

    /* Pass 2: serialize only symbolic operands and abstract branch/exit IDs. */
    operation_count = 0;
    QTAILQ_FOREACH(op, &tcg_ctx->ops, link) {
        const TCGOpDef *def = &tcg_op_defs[op->opc];
        const char *name = def->name;
        uint32_t this_op = operation_count++;

        if (state->api.block_add_operation(block, name,
                                           op->param1, op->param2) != 0) {
            goto out;
        }

        if (strcmp(name, "insn_start") == 0) {
            for (unsigned i = 0; i < INSN_START_WORDS; i++) {
                if (!pv_add_immediate(&state->api, block, this_op,
                                      (int64_t)tcg_get_insn_start_param(op, i))) {
                    goto out;
                }
            }
        } else if (strcmp(name, "br") == 0 ||
                   strcmp(name, "set_label") == 0) {
            TCGLabel *label = arg_label(op->args[0]);
            uint16_t logical_id;

            if (label == NULL || label->id >= tcg_ctx->nb_labels ||
                label_map[label->id] == PV_LABEL_UNMAPPED) {
                goto out;
            }
            logical_id = label_map[label->id];
            if (state->api.block_add_operand(block, this_op,
                                             PV_OPERAND_LABEL,
                                             logical_id) != 0) {
                goto out;
            }
        } else if (strcmp(name, "goto_tb") == 0) {
            uint64_t index = op->args[0];

            if (index > 1 ||
                !pv_add_immediate(&state->api, block, this_op, index)) {
                goto out;
            }
        } else if (strcmp(name, "exit_tb") == 0) {
            uintptr_t value = op->args[0];
            uintptr_t base = (uintptr_t)tcg_splitwx_to_rx((void *)tb);
            uintptr_t index;

            /* Reject exit_tb(NULL, 0) and every non-current-TB address. */
            if (value == 0 || value < base || value - base > 3) {
                goto out;
            }
            index = value - base;
            if ((index != 0 && index != 1 && index != 3) ||
                !pv_add_immediate(&state->api, block, this_op, index)) {
                goto out;
            }
        } else {
            for (unsigned i = 0; i < def->nb_oargs + def->nb_iargs; i++) {
                if (!pv_add_temp_operand(&state->api, block, this_op,
                                          arg_temp(op->args[i]),
                                          i < def->nb_oargs, temp_map)) {
                    goto out;
                }
            }
        }
    }

    for (size_t i = 0; i < label_count; i++) {
        if (label_definitions[i] == UINT32_MAX ||
            state->api.block_add_label(block, (uint16_t)i,
                                       label_definitions[i]) != 0) {
            goto out;
        }
    }

    store_status = state->api.l2_store_block(state->api.handle, block);
    if (store_status == 0) {
        g_atomic_int_inc(&pv_stores);
        ok = true;
        if (state->trace) {
            g_printerr("PVTCG: queued pc=0x%" PRIx64
                       " size=%u insns=%u ops=%u\\n",
                       (uint64_t)pc, tb->size, tb->icount, operation_count);
        }
    } else {
        failure = store_status > 0
            ? "native-l2-worker-queue-full"
            : "native-l2-store-rejected-record";
    }

out:
    if (!ok) {
        g_atomic_int_inc(&pv_rejections);
        if (state->trace) {
            g_printerr("PVTCG: serialize failed pc=0x%" PRIx64
                       " reason=%s store_status=%d\\n",
                       (uint64_t)pc, failure, store_status);
        }
    }
    if (block != NULL) {
        state->api.block_free(block);
    }
    g_free(temp_map);
    g_free(label_map);
    g_free(label_definitions);
    return ok;
}

void phoenixvm_tcg_cache_store(CPUState *cpu,
                               TranslationBlock *tb,
                               vaddr pc,
                               void *host_pc)
{
    PvTcgCacheState *state = g_atomic_pointer_get(&pv_cache);

    if (state != NULL) {
        if (state->trace) {
            g_printerr("PVTCG: store hook pc=0x%" PRIx64 " state=ready size=%u insns=%u\\n",
                       (uint64_t)pc, tb->size, tb->icount);
        }
        pv_serialize_translation(state, cpu, tb, pc, host_pc);
    } else if (g_strcmp0(g_getenv("PHOENIXVM_TCG_CACHE_TRACE"), "1") == 0) {
        g_printerr("PVTCG: cache adapter was not initialized before TB store\\n");
    }
}

#else /* CONFIG_USER_ONLY || !CONFIG_POSIX */

void phoenixvm_tcg_cache_start(void)
{
}

bool phoenixvm_tcg_cache_try_restore(CPUState *cpu,
                                     TranslationBlock *tb,
                                     vaddr pc,
                                     void *host_pc,
                                     int *max_insns)
{
    return false;
}

void phoenixvm_tcg_cache_store(CPUState *cpu,
                               TranslationBlock *tb,
                               vaddr pc,
                               void *host_pc)
{
}

#endif /* CONFIG_USER_ONLY */
