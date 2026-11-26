use crate::tcg_ir_cache::{TcgEndian, TcgIrBlock, TcgIrCompatibility, TcgIrL2Store};
use std::collections::HashMap;
use std::ffi::{c_char, CStr};
use std::panic::{catch_unwind, AssertUnwindSafe};
use std::path::PathBuf;
use std::ptr;
use std::slice;
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::mpsc::{self, SyncSender, TrySendError};
use std::sync::{Arc, RwLock};

const MAX_FFI_STRING_BYTES: usize = 4096;
const MAX_FFI_CPU_FEATURES: usize = 128;
const MAX_FFI_RECORD_BYTES: usize = 2 * 1024 * 1024;

#[repr(C)]
pub struct PvTcgCompatibility {
    pub qemu_build_id: *const c_char,
    pub guest_arch: *const c_char,
    pub guest_cpu_model: *const c_char,
    pub guest_build_id: *const c_char,
    pub host_arch: *const c_char,
    pub host_abi: *const c_char,
    /// 0 = little-endian, 1 = big-endian.
    pub host_endian: u8,
    pub host_page_size: u32,
    pub host_cpu_features: *const *const c_char,
    pub host_cpu_features_len: usize,
    pub guest_pc: u64,
    pub guest_cs_base: u64,
    pub tb_flags: u64,
    pub tb_cflags: u64,
    pub mmu_index: u32,
    pub physical_pages: *const u64,
    pub physical_pages_len: usize,
    /// Each digest points to exactly 32 readable bytes.
    pub guest_code_sha256: *const u8,
    pub tb_context_sha256: *const u8,
}

/// Opaque handle owned by the caller between open() and close().
pub struct PvTcgL2Handle {
    store: Arc<TcgIrL2Store>,
    vm_id: String,
    resident: Arc<RwLock<ResidentL1>>,
    resident_clock: Arc<AtomicU64>,
    worker_tx: Option<SyncSender<PersistCommand>>,
    worker: Option<std::thread::JoinHandle<()>>,
    worker_errors: Arc<AtomicU64>,
    queue_drops: AtomicU64,
}

enum PersistCommand {
    Store(Arc<TcgIrBlock>),
    Flush(SyncSender<()>),
}

impl Drop for PvTcgL2Handle {
    fn drop(&mut self) {
        // Drop the sender first: the worker drains queued writes and then exits.
        self.worker_tx.take();
        if let Some(worker) = self.worker.take() {
            let _ = worker.join();
        }
    }
}

struct ResidentEntry {
    block: Arc<TcgIrBlock>,
    bytes: u64,
    last_access: AtomicU64,
}

struct ResidentL1 {
    entries: HashMap<[u8; 32], ResidentEntry>,
    bytes: u64,
    max_entries: usize,
    max_bytes: u64,
}

impl ResidentL1 {
    fn new(max_entries: usize, max_bytes: u64) -> Self {
        Self {
            entries: HashMap::new(),
            bytes: 0,
            max_entries: max_entries.max(1),
            max_bytes: max_bytes.max(1),
        }
    }

    fn insert(&mut self, block: TcgIrBlock, access_clock: &AtomicU64) -> Result<(), ()> {
        self.insert_arc(Arc::new(block), access_clock)
    }

    fn insert_arc(&mut self, block: Arc<TcgIrBlock>, access_clock: &AtomicU64) -> Result<(), ()> {
        let resident_bytes = block.resident_size_bytes();
        if resident_bytes > self.max_bytes {
            return Ok(());
        }
        let key = block.compatibility.fingerprint().map_err(|_| ())?;
        if let Some(old) = self.entries.remove(&key) {
            self.bytes = self.bytes.saturating_sub(old.bytes);
        }
        while self.entries.len() >= self.max_entries
            || self.bytes.saturating_add(resident_bytes) > self.max_bytes
        {
            let Some(oldest_key) = self
                .entries
                .iter()
                .min_by_key(|(_, entry)| entry.last_access.load(Ordering::Relaxed))
                .map(|(key, _)| *key)
            else {
                break;
            };
            if let Some(oldest) = self.entries.remove(&oldest_key) {
                self.bytes = self.bytes.saturating_sub(oldest.bytes);
            }
        }
        let last_access = access_clock
            .fetch_add(1, Ordering::Relaxed)
            .saturating_add(1);
        self.entries.insert(
            key,
            ResidentEntry {
                block,
                bytes: resident_bytes,
                last_access: AtomicU64::new(last_access),
            },
        );
        self.bytes = self.bytes.saturating_add(resident_bytes);
        Ok(())
    }

    fn lookup(
        &self,
        compatibility: &TcgIrCompatibility,
        access_clock: &AtomicU64,
    ) -> Result<Option<Arc<TcgIrBlock>>, ()> {
        let key = compatibility.fingerprint().map_err(|_| ())?;
        let Some(entry) = self.entries.get(&key) else {
            return Ok(None);
        };
        entry.last_access.store(
            access_clock
                .fetch_add(1, Ordering::Relaxed)
                .saturating_add(1),
            Ordering::Relaxed,
        );
        Ok(Some(Arc::clone(&entry.block)))
    }

    fn lookup_probe(
        &self,
        probe: &TcgIrCompatibility,
        access_clock: &AtomicU64,
    ) -> Result<Option<Arc<TcgIrBlock>>, ()> {
        probe.fingerprint().map_err(|_| ())?;
        let candidate = self
            .entries
            .iter()
            .filter(|(_, entry)| entry.block.compatibility.matches_translation_probe(probe))
            .max_by_key(|(_, entry)| entry.last_access.load(Ordering::Relaxed));
        let Some((_, entry)) = candidate else {
            return Ok(None);
        };
        entry.last_access.store(
            access_clock
                .fetch_add(1, Ordering::Relaxed)
                .saturating_add(1),
            Ordering::Relaxed,
        );
        Ok(Some(Arc::clone(&entry.block)))
    }

    fn remove(&mut self, compatibility: &TcgIrCompatibility) -> Result<(), ()> {
        let key = compatibility.fingerprint().map_err(|_| ())?;
        if let Some(entry) = self.entries.remove(&key) {
            self.bytes = self.bytes.saturating_sub(entry.bytes);
        }
        Ok(())
    }
}

unsafe fn copy_c_string(value: *const c_char) -> Result<String, ()> {
    if value.is_null() {
        return Err(());
    }
    let bytes = unsafe { CStr::from_ptr(value) }.to_bytes();
    if bytes.is_empty() || bytes.len() > MAX_FFI_STRING_BYTES {
        return Err(());
    }
    std::str::from_utf8(bytes)
        .map(str::to_owned)
        .map_err(|_| ())
}

unsafe fn copy_array<T: Copy>(
    value: *const T,
    length: usize,
    maximum: usize,
) -> Result<Vec<T>, ()> {
    if length > maximum || (length > 0 && value.is_null()) {
        return Err(());
    }
    if length == 0 {
        return Ok(Vec::new());
    }
    Ok(unsafe { slice::from_raw_parts(value, length) }.to_vec())
}

unsafe fn copy_digest(value: *const u8) -> Result<[u8; 32], ()> {
    if value.is_null() {
        return Err(());
    }
    let mut digest = [0_u8; 32];
    digest.copy_from_slice(unsafe { slice::from_raw_parts(value, 32) });
    Ok(digest)
}

unsafe fn copy_compatibility(value: *const PvTcgCompatibility) -> Result<TcgIrCompatibility, ()> {
    if value.is_null() {
        return Err(());
    }
    let value = unsafe { &*value };
    let host_endian = match value.host_endian {
        0 => TcgEndian::Little,
        1 => TcgEndian::Big,
        _ => return Err(()),
    };
    let feature_ptrs = unsafe {
        copy_array(
            value.host_cpu_features,
            value.host_cpu_features_len,
            MAX_FFI_CPU_FEATURES,
        )?
    };
    let mut host_cpu_features = Vec::with_capacity(feature_ptrs.len());
    for feature in feature_ptrs {
        host_cpu_features.push(unsafe { copy_c_string(feature)? });
    }

    let compatibility = TcgIrCompatibility {
        qemu_build_id: unsafe { copy_c_string(value.qemu_build_id)? },
        guest_arch: unsafe { copy_c_string(value.guest_arch)? },
        guest_cpu_model: unsafe { copy_c_string(value.guest_cpu_model)? },
        guest_build_id: unsafe { copy_c_string(value.guest_build_id)? },
        host_arch: unsafe { copy_c_string(value.host_arch)? },
        host_abi: unsafe { copy_c_string(value.host_abi)? },
        host_endian,
        host_page_size: value.host_page_size,
        host_cpu_features,
        guest_pc: value.guest_pc,
        guest_cs_base: value.guest_cs_base,
        tb_flags: value.tb_flags,
        tb_cflags: value.tb_cflags,
        mmu_index: value.mmu_index,
        physical_pages: unsafe { copy_array(value.physical_pages, value.physical_pages_len, 2)? },
        guest_code_sha256: unsafe { copy_digest(value.guest_code_sha256)? },
        tb_context_sha256: unsafe { copy_digest(value.tb_context_sha256)? },
    };
    compatibility.validate().map_err(|_| ())?;
    Ok(compatibility)
}

/// Open a VM-scoped persistent IR store.
///
/// Returns null on invalid arguments, I/O errors or panic. The cache directory
/// must be private to the PhoenixVM application sandbox.
#[no_mangle]
pub unsafe extern "C" fn phoenixvm_tcg_l2_open(
    root: *const c_char,
    vm_id: *const c_char,
    max_entries: usize,
    max_bytes: u64,
) -> *mut PvTcgL2Handle {
    let result = catch_unwind(AssertUnwindSafe(|| {
        let root = unsafe { copy_c_string(root) }.map_err(|_| ())?;
        let vm_id = unsafe { copy_c_string(vm_id) }.map_err(|_| ())?;
        let store = Arc::new(
            TcgIrL2Store::new(PathBuf::from(root), max_entries, max_bytes).map_err(|_| ())?,
        );
        let worker_store = Arc::clone(&store);
        let worker_vm_id = vm_id.clone();
        let resident = Arc::new(RwLock::new(ResidentL1::new(256, 32 * 1024 * 1024)));
        let worker_resident = Arc::clone(&resident);
        let resident_clock = Arc::new(AtomicU64::new(0));
        let worker_clock = Arc::clone(&resident_clock);
        let worker_errors = Arc::new(AtomicU64::new(0));
        let worker_errors_thread = Arc::clone(&worker_errors);
        let (worker_tx, worker_rx) = mpsc::sync_channel::<PersistCommand>(128);
        let worker = std::thread::Builder::new()
            .name("phoenixvm-tcg-l2".into())
            .spawn(move || {
                while let Ok(command) = worker_rx.recv() {
                    match command {
                        PersistCommand::Store(block) => {
                            let persisted = worker_store.store(&worker_vm_id, &block);
                            let resident_inserted = persisted.and_then(|()| {
                                worker_resident
                                    .write()
                                    .map_err(|_| {
                                        crate::tcg_ir_cache::TcgIrCacheError::Invalid(
                                            "L1 lock poisoned",
                                        )
                                    })?
                                    .insert_arc(block, &worker_clock)
                                    .map_err(|_| {
                                        crate::tcg_ir_cache::TcgIrCacheError::Invalid(
                                            "L1 insert failed",
                                        )
                                    })
                            });
                            if resident_inserted.is_err() {
                                worker_errors_thread.fetch_add(1, Ordering::Relaxed);
                            }
                        }
                        PersistCommand::Flush(ack) => {
                            let _ = ack.send(());
                        }
                    }
                }
            })
            .map_err(|_| ())?;
        Ok::<_, ()>(Box::into_raw(Box::new(PvTcgL2Handle {
            store,
            vm_id,
            resident,
            resident_clock,
            worker_tx: Some(worker_tx),
            worker: Some(worker),
            worker_errors,
            queue_drops: AtomicU64::new(0),
        })))
    }));
    match result {
        Ok(Ok(handle)) => handle,
        _ => ptr::null_mut(),
    }
}

/// Close a handle returned by phoenixvm_tcg_l2_open().
#[no_mangle]
pub unsafe extern "C" fn phoenixvm_tcg_l2_close(handle: *mut PvTcgL2Handle) {
    if !handle.is_null() {
        let mut handle = unsafe { Box::from_raw(handle) };

        // Drop the sender first: recv() will drain queued commands then return
        // Disconnected. Join the worker before C can dlclose this Rust library.
        handle.worker_tx.take();
        if let Some(worker) = handle.worker.take() {
            let _ = worker.join();
        }
    }
}

/// Store one fully encoded PVTCGIR record for the handle's VM. Returns 0 on
/// success and -1 on validation, I/O or panic.
#[no_mangle]
pub unsafe extern "C" fn phoenixvm_tcg_l2_store(
    handle: *mut PvTcgL2Handle,
    record: *const u8,
    record_len: usize,
) -> i32 {
    if handle.is_null() || record.is_null() || record_len == 0 || record_len > MAX_FFI_RECORD_BYTES
    {
        return -1;
    }
    let result = catch_unwind(AssertUnwindSafe(|| {
        let handle = unsafe { &*handle };
        let bytes = unsafe { slice::from_raw_parts(record, record_len) };
        let block = TcgIrBlock::decode(bytes).map_err(|_| ())?;
        handle.store.store(&handle.vm_id, &block).map_err(|_| ())
    }));
    if matches!(result, Ok(Ok(()))) {
        0
    } else {
        -1
    }
}

/// Load one record by its complete compatibility identity.
///
/// Returns 1 on hit, 0 on miss, and -1 on invalid arguments, I/O errors or
/// panic. On hit, *record is owned by the caller and must be freed with
/// phoenixvm_tcg_l2_free_buffer().
#[no_mangle]
pub unsafe extern "C" fn phoenixvm_tcg_l2_load(
    handle: *mut PvTcgL2Handle,
    compatibility: *const PvTcgCompatibility,
    record: *mut *mut u8,
    record_len: *mut usize,
) -> i32 {
    if handle.is_null() || compatibility.is_null() || record.is_null() || record_len.is_null() {
        return -1;
    }
    unsafe {
        *record = ptr::null_mut();
        *record_len = 0;
    }
    let result = catch_unwind(AssertUnwindSafe(|| {
        let handle = unsafe { &*handle };
        let key = unsafe { copy_compatibility(compatibility) }.map_err(|_| ())?;
        let Some(block) = handle.store.load(&handle.vm_id, &key).map_err(|_| ())? else {
            return Ok::<_, ()>(None);
        };
        let bytes = block.encode().map_err(|_| ())?;
        if bytes.len() > MAX_FFI_RECORD_BYTES {
            return Err(());
        }
        Ok(Some(bytes))
    }));
    match result {
        Ok(Ok(Some(bytes))) => {
            let mut boxed = bytes.into_boxed_slice();
            let length = boxed.len();
            let pointer = boxed.as_mut_ptr();
            std::mem::forget(boxed);
            unsafe {
                *record = pointer;
                *record_len = length;
            }
            1
        }
        Ok(Ok(None)) => 0,
        _ => -1,
    }
}

/// Free a byte buffer returned by phoenixvm_tcg_l2_load().
#[no_mangle]
pub unsafe extern "C" fn phoenixvm_tcg_l2_free_buffer(record: *mut u8, record_len: usize) {
    if !record.is_null() && record_len > 0 && record_len <= MAX_FFI_RECORD_BYTES {
        let raw = ptr::slice_from_raw_parts_mut(record, record_len);
        drop(unsafe { Box::from_raw(raw) });
    }
}

/// Invalidate one compatibility identity. Returns 0 on success and -1 on
/// invalid arguments, I/O errors or panic.
#[no_mangle]
pub unsafe extern "C" fn phoenixvm_tcg_l2_invalidate(
    handle: *mut PvTcgL2Handle,
    compatibility: *const PvTcgCompatibility,
) -> i32 {
    if handle.is_null() || compatibility.is_null() {
        return -1;
    }
    let result = catch_unwind(AssertUnwindSafe(|| {
        let handle = unsafe { &*handle };
        let key = unsafe { copy_compatibility(compatibility) }.map_err(|_| ())?;
        handle
            .store
            .invalidate(&handle.vm_id, &key)
            .map_err(|_| ())?;
        handle.resident.write().map_err(|_| ())?.remove(&key)
    }));
    if matches!(result, Ok(Ok(()))) {
        0
    } else {
        -1
    }
}

/// Invalidate all entries belonging to the handle's VM.
#[no_mangle]
pub unsafe extern "C" fn phoenixvm_tcg_l2_invalidate_vm(handle: *mut PvTcgL2Handle) -> i32 {
    if handle.is_null() {
        return -1;
    }
    let result = catch_unwind(AssertUnwindSafe(|| {
        let handle = unsafe { &*handle };
        handle.store.invalidate_vm(&handle.vm_id).map_err(|_| ())?;
        let mut resident = handle.resident.write().map_err(|_| ())?;
        resident.entries.clear();
        resident.bytes = 0;
        Ok::<_, ()>(())
    }));
    if matches!(result, Ok(Ok(()))) {
        0
    } else {
        -1
    }
}

/// Preload this VM's most-recently-used L2 records into a bounded memory L1.
///
/// This performs filesystem I/O and MUST be called by a cache worker before
/// guest vCPU execution. Returns 0 on success, -1 on invalid quota or I/O error.
#[no_mangle]
pub unsafe extern "C" fn phoenixvm_tcg_l2_preload(
    handle: *mut PvTcgL2Handle,
    max_entries: usize,
    max_bytes: u64,
) -> i32 {
    if handle.is_null() || max_entries == 0 || max_bytes == 0 {
        return -1;
    }
    let result = catch_unwind(AssertUnwindSafe(|| {
        let handle = unsafe { &*handle };
        let blocks = handle
            .store
            .preload_vm(&handle.vm_id, max_entries, max_bytes)
            .map_err(|_| ())?;
        let mut resident = ResidentL1::new(max_entries, max_bytes);
        for block in blocks {
            resident.insert(block, &handle.resident_clock)?;
        }
        let mut current = handle.resident.write().map_err(|_| ())?;
        *current = resident;
        Ok::<_, ()>(())
    }));
    if matches!(result, Ok(Ok(()))) {
        0
    } else {
        -1
    }
}

/// Nonblocking, memory-only lookup for the QEMU vCPU translation path.
///
/// This function never touches the filesystem. It returns 1 on hit, 0 on miss,
/// and -1 on invalid context/lock failure. The returned block must be freed.
#[no_mangle]
pub unsafe extern "C" fn phoenixvm_tcg_l1_lookup(
    handle: *mut PvTcgL2Handle,
    compatibility: *const PvTcgCompatibility,
    out_block: *mut *mut PvTcgIrBlockHandle,
) -> i32 {
    if handle.is_null() || compatibility.is_null() || out_block.is_null() {
        return -1;
    }
    unsafe { *out_block = ptr::null_mut() };
    let result = catch_unwind(AssertUnwindSafe(|| {
        let handle = unsafe { &*handle };
        let key = unsafe { copy_compatibility(compatibility) }.map_err(|_| ())?;
        // Never wait for a worker-side L1 write lock on the vCPU path. A
        // concurrent preload/invalidation/store publication is a safe miss.
        let resident = handle.resident.try_read().map_err(|_| ())?;
        resident.lookup(&key, &handle.resident_clock)
    }));
    match result {
        Ok(Ok(Some(block))) => {
            unsafe { *out_block = Box::into_raw(Box::new(PvTcgIrBlockHandle { block })) };
            1
        }
        Ok(Ok(None)) => 0,
        _ => -1,
    }
}

/// Candidate lookup by all translation context fields except guest-code hash.
///
/// A hit is only a candidate. QEMU must validate the returned block's stored
/// code digest against freshly read guest code bytes before restoring its TCG ops.
#[no_mangle]
pub unsafe extern "C" fn phoenixvm_tcg_l1_lookup_probe(
    handle: *mut PvTcgL2Handle,
    probe: *const PvTcgCompatibility,
    out_block: *mut *mut PvTcgIrBlockHandle,
) -> i32 {
    if handle.is_null() || probe.is_null() || out_block.is_null() {
        return -1;
    }
    unsafe { *out_block = ptr::null_mut() };
    let result = catch_unwind(AssertUnwindSafe(|| {
        let handle = unsafe { &*handle };
        let key = unsafe { copy_compatibility(probe) }.map_err(|_| ())?;
        // Do not wait on worker publication or preload in the vCPU path.
        let resident = handle.resident.try_read().map_err(|_| ())?;
        resident.lookup_probe(&key, &handle.resident_clock)
    }));
    match result {
        Ok(Ok(Some(block))) => {
            unsafe { *out_block = Box::into_raw(Box::new(PvTcgIrBlockHandle { block })) };
            1
        }
        Ok(Ok(None)) => 0,
        _ => -1,
    }
}

/// Opaque, decoded or incrementally constructed TCG IR block.
pub struct PvTcgIrBlockHandle {
    block: Arc<TcgIrBlock>,
}

const MAX_FFI_TEMPS: usize = 512;
const MAX_FFI_LABELS: usize = 512;
const MAX_FFI_OPERATIONS: usize = 16_384;
const MAX_FFI_OPERANDS: usize = 32;

/// Construct an empty block with a complete compatibility identity.
/// Operand tags for add_operand: 0=Temp, 1=Global, 2=Immediate, 3=Label.
#[no_mangle]
pub unsafe extern "C" fn phoenixvm_tcg_ir_block_create(
    compatibility: *const PvTcgCompatibility,
    guest_size: u32,
    instruction_count: u16,
) -> *mut PvTcgIrBlockHandle {
    if compatibility.is_null() || guest_size == 0 || instruction_count == 0 {
        return ptr::null_mut();
    }
    let result = catch_unwind(AssertUnwindSafe(|| {
        let compatibility = unsafe { copy_compatibility(compatibility) }.map_err(|_| ())?;
        Ok::<_, ()>(Box::into_raw(Box::new(PvTcgIrBlockHandle {
            block: Arc::new(TcgIrBlock {
                compatibility,
                guest_size,
                instruction_count,
                temporaries: Vec::new(),
                labels: Vec::new(),
                operations: Vec::new(),
            }),
        })))
    }));
    match result {
        Ok(Ok(block)) => block,
        _ => ptr::null_mut(),
    }
}

/// Release a builder or decoded block handle.
#[no_mangle]
pub unsafe extern "C" fn phoenixvm_tcg_ir_block_free(block: *mut PvTcgIrBlockHandle) {
    if !block.is_null() {
        drop(unsafe { Box::from_raw(block) });
    }
}

/// Append one symbolic temporary. Type tags: I32=0, I64=1, I128=2,
/// V64=3, V128=4, V256=5, Ptr=6. Kind tags: TB=0, EBB=1.
#[no_mangle]
pub unsafe extern "C" fn phoenixvm_tcg_ir_block_add_temp(
    block: *mut PvTcgIrBlockHandle,
    id: u16,
    base_id: u16,
    subindex: u8,
    base_type: u8,
    value_type: u8,
    kind: u8,
) -> i32 {
    if block.is_null() {
        return -1;
    }
    let base_type = match base_type {
        0 => crate::tcg_ir_cache::TcgValueType::I32,
        1 => crate::tcg_ir_cache::TcgValueType::I64,
        2 => crate::tcg_ir_cache::TcgValueType::I128,
        3 => crate::tcg_ir_cache::TcgValueType::V64,
        4 => crate::tcg_ir_cache::TcgValueType::V128,
        5 => crate::tcg_ir_cache::TcgValueType::V256,
        6 => crate::tcg_ir_cache::TcgValueType::Ptr,
        _ => return -1,
    };
    let value_type = match value_type {
        0 => crate::tcg_ir_cache::TcgValueType::I32,
        1 => crate::tcg_ir_cache::TcgValueType::I64,
        2 => crate::tcg_ir_cache::TcgValueType::I128,
        3 => crate::tcg_ir_cache::TcgValueType::V64,
        4 => crate::tcg_ir_cache::TcgValueType::V128,
        5 => crate::tcg_ir_cache::TcgValueType::V256,
        6 => crate::tcg_ir_cache::TcgValueType::Ptr,
        _ => return -1,
    };
    let kind = match kind {
        0 => crate::tcg_ir_cache::TcgTempKind::Tb,
        1 => crate::tcg_ir_cache::TcgTempKind::Ebb,
        _ => return -1,
    };
    let block = unsafe { &mut *block };
    let Some(block) = Arc::get_mut(&mut block.block) else {
        return -1;
    };
    if block.temporaries.len() >= MAX_FFI_TEMPS {
        return -1;
    }
    block.temporaries.push(crate::tcg_ir_cache::TcgTempRecord {
        id,
        base_id,
        subindex,
        base_type,
        value_type,
        kind,
    });
    0
}

/// Append a label definition's metadata. It must correspond to a set_label op.
#[no_mangle]
pub unsafe extern "C" fn phoenixvm_tcg_ir_block_add_label(
    block: *mut PvTcgIrBlockHandle,
    id: u16,
    operation_index: u32,
) -> i32 {
    if block.is_null() {
        return -1;
    }
    let block = unsafe { &mut *block };
    let Some(block) = Arc::get_mut(&mut block.block) else {
        return -1;
    };
    if block.labels.len() >= MAX_FFI_LABELS {
        return -1;
    }
    block.labels.push(crate::tcg_ir_cache::TcgLabelRecord {
        id,
        operation_index,
    });
    0
}

/// Append an opcode by stable name and return its operation index, or -1.
#[no_mangle]
pub unsafe extern "C" fn phoenixvm_tcg_ir_block_add_operation(
    block: *mut PvTcgIrBlockHandle,
    opcode: *const c_char,
    param1: u8,
    param2: u8,
) -> i32 {
    if block.is_null() || opcode.is_null() {
        return -1;
    }
    let opcode = match unsafe { copy_c_string(opcode) } {
        Ok(value) if value.len() <= 64 => value,
        _ => return -1,
    };
    let block = unsafe { &mut *block };
    let Some(block) = Arc::get_mut(&mut block.block) else {
        return -1;
    };
    if block.operations.len() >= MAX_FFI_OPERATIONS {
        return -1;
    }
    let index = block.operations.len();
    block.operations.push(crate::tcg_ir_cache::TcgIrOperation {
        opcode,
        param1,
        param2,
        operands: Vec::new(),
    });
    i32::try_from(index).unwrap_or(-1)
}

/// Append one symbolic operand. Tags: 0=Temp, 1=Global, 2=Immediate, 3=Label.
/// Helper operands are deliberately not exposed by this v3 C ABI.
#[no_mangle]
pub unsafe extern "C" fn phoenixvm_tcg_ir_block_add_operand(
    block: *mut PvTcgIrBlockHandle,
    operation_index: u32,
    tag: u8,
    value: i64,
) -> i32 {
    if block.is_null() {
        return -1;
    }
    let operand = match tag {
        0 => match u16::try_from(value) {
            Ok(id) => crate::tcg_ir_cache::TcgOperand::Temp(id),
            Err(_) => return -1,
        },
        1 => match u16::try_from(value) {
            Ok(id) => crate::tcg_ir_cache::TcgOperand::Global(id),
            Err(_) => return -1,
        },
        2 => crate::tcg_ir_cache::TcgOperand::Immediate(value),
        3 => match u16::try_from(value) {
            Ok(id) => crate::tcg_ir_cache::TcgOperand::Label(id),
            Err(_) => return -1,
        },
        _ => return -1,
    };
    let block = unsafe { &mut *block };
    let Some(block) = Arc::get_mut(&mut block.block) else {
        return -1;
    };
    let Some(operation) = block.operations.get_mut(operation_index as usize) else {
        return -1;
    };
    if operation.operands.len() >= MAX_FFI_OPERANDS {
        return -1;
    }
    operation.operands.push(operand);
    0
}

/// Wait for all queued L2 writes to finish. This can block and must run
/// after vCPU translation has stopped, on a shutdown or cache-worker thread.
#[no_mangle]
pub unsafe extern "C" fn phoenixvm_tcg_l2_flush(handle: *mut PvTcgL2Handle) -> i32 {
    if handle.is_null() {
        return -1;
    }
    let result = catch_unwind(AssertUnwindSafe(|| {
        let handle = unsafe { &*handle };
        let sender = handle.worker_tx.as_ref().ok_or(())?;
        let (ack_tx, ack_rx) = mpsc::sync_channel(0);
        sender.send(PersistCommand::Flush(ack_tx)).map_err(|_| ())?;
        ack_rx.recv().map_err(|_| ())?;
        if handle.worker_errors.load(Ordering::Relaxed) != 0 {
            return Err(());
        }
        Ok(())
    }));
    if matches!(result, Ok(Ok(()))) {
        0
    } else {
        -1
    }
}

/// Read process-local persistence worker diagnostics.
#[no_mangle]
pub unsafe extern "C" fn phoenixvm_tcg_l2_worker_stats(
    handle: *const PvTcgL2Handle,
    errors: *mut u64,
    queue_drops: *mut u64,
) -> i32 {
    if handle.is_null() || errors.is_null() || queue_drops.is_null() {
        return -1;
    }
    let handle = unsafe { &*handle };
    unsafe {
        *errors = handle.worker_errors.load(Ordering::Relaxed);
        *queue_drops = handle.queue_drops.load(Ordering::Relaxed);
    }
    0
}

/// Validate a block, publish it to resident L1 and enqueue L2 persistence.
/// Returns 0 when queued, 1 when the bounded queue is full (the L1 entry is
/// retained but no persistent write was accepted), and -1 on invalid input.
#[no_mangle]
pub unsafe extern "C" fn phoenixvm_tcg_l2_store_block(
    handle: *mut PvTcgL2Handle,
    block: *const PvTcgIrBlockHandle,
) -> i32 {
    if handle.is_null() || block.is_null() {
        return -1;
    }
    let result = catch_unwind(AssertUnwindSafe(|| {
        let handle = unsafe { &*handle };
        let block = unsafe { &*block };
        // Do not encode, touch L1 locks or access disk on this path. The bounded
        // worker queue owns the block after this nonblocking enqueue; the worker
        // validates/persists it and then publishes it into resident L1.
        let sender = handle.worker_tx.as_ref().ok_or(())?;
        match sender.try_send(PersistCommand::Store(Arc::clone(&block.block))) {
            Ok(()) => Ok::<_, ()>(0),
            Err(TrySendError::Full(_)) => {
                handle.queue_drops.fetch_add(1, Ordering::Relaxed);
                Ok(1)
            }
            Err(TrySendError::Disconnected(_)) => Err(()),
        }
    }));
    match result {
        Ok(Ok(status)) => status,
        _ => -1,
    }
}

/// Load a fully validated block. Returns 1 on hit, 0 on miss, -1 on error.
#[no_mangle]
pub unsafe extern "C" fn phoenixvm_tcg_l2_load_block(
    handle: *mut PvTcgL2Handle,
    compatibility: *const PvTcgCompatibility,
    out_block: *mut *mut PvTcgIrBlockHandle,
) -> i32 {
    if handle.is_null() || compatibility.is_null() || out_block.is_null() {
        return -1;
    }
    unsafe { *out_block = ptr::null_mut() };
    let result = catch_unwind(AssertUnwindSafe(|| {
        let handle = unsafe { &*handle };
        let key = unsafe { copy_compatibility(compatibility) }.map_err(|_| ())?;
        let block = handle.store.load(&handle.vm_id, &key).map_err(|_| ())?;
        if let Some(block) = block {
            let block = Arc::new(block);
            handle
                .resident
                .write()
                .map_err(|_| ())?
                .insert_arc(Arc::clone(&block), &handle.resident_clock)?;
            Ok::<_, ()>(Some(block))
        } else {
            Ok(None)
        }
    }));
    match result {
        Ok(Ok(Some(block))) => {
            unsafe { *out_block = Box::into_raw(Box::new(PvTcgIrBlockHandle { block })) };
            1
        }
        Ok(Ok(None)) => 0,
        _ => -1,
    }
}

/// Return the stored guest-code digest and physical-page identity.
///
/// QEMU must compare these fields with fresh guest RAM bytes and current page
/// mappings before importing any operation from this candidate block.
#[no_mangle]
pub unsafe extern "C" fn phoenixvm_tcg_ir_block_get_validation(
    block: *const PvTcgIrBlockHandle,
    guest_size: *mut u32,
    guest_code_sha256: *mut u8,
    physical_pages: *mut u64,
    physical_pages_len: *mut usize,
) -> i32 {
    if block.is_null()
        || guest_size.is_null()
        || guest_code_sha256.is_null()
        || physical_pages.is_null()
        || physical_pages_len.is_null()
    {
        return -1;
    }
    let block = unsafe { &*block };
    let compatibility = &block.block.compatibility;
    if compatibility.physical_pages.len() > 2 {
        return -1;
    }
    unsafe {
        *guest_size = block.block.guest_size;
        ptr::copy_nonoverlapping(
            compatibility.guest_code_sha256.as_ptr(),
            guest_code_sha256,
            32,
        );
        let pages = slice::from_raw_parts_mut(physical_pages, 2);
        pages.fill(0);
        for (index, page) in compatibility.physical_pages.iter().enumerate() {
            pages[index] = *page;
        }
        *physical_pages_len = compatibility.physical_pages.len();
    }
    0
}

/// Read block counts and guest metadata from a decoded block.
#[no_mangle]
pub unsafe extern "C" fn phoenixvm_tcg_ir_block_get_summary(
    block: *const PvTcgIrBlockHandle,
    guest_size: *mut u32,
    instruction_count: *mut u16,
    temp_count: *mut usize,
    label_count: *mut usize,
    operation_count: *mut usize,
) -> i32 {
    if block.is_null()
        || guest_size.is_null()
        || instruction_count.is_null()
        || temp_count.is_null()
        || label_count.is_null()
        || operation_count.is_null()
    {
        return -1;
    }
    let block = unsafe { &*block };
    unsafe {
        *guest_size = block.block.guest_size;
        *instruction_count = block.block.instruction_count;
        *temp_count = block.block.temporaries.len();
        *label_count = block.block.labels.len();
        *operation_count = block.block.operations.len();
    }
    0
}

/// Get a temporary record by index.
#[no_mangle]
pub unsafe extern "C" fn phoenixvm_tcg_ir_block_get_temp(
    block: *const PvTcgIrBlockHandle,
    index: usize,
    id: *mut u16,
    base_id: *mut u16,
    subindex: *mut u8,
    base_type: *mut u8,
    value_type: *mut u8,
    kind: *mut u8,
) -> i32 {
    if block.is_null()
        || id.is_null()
        || base_id.is_null()
        || subindex.is_null()
        || base_type.is_null()
        || value_type.is_null()
        || kind.is_null()
    {
        return -1;
    }
    let block = unsafe { &*block };
    let Some(temp) = block.block.temporaries.get(index) else {
        return -1;
    };
    unsafe {
        *id = temp.id;
        *base_id = temp.base_id;
        *subindex = temp.subindex;
        *base_type = temp.base_type as u8;
        *value_type = temp.value_type as u8;
        *kind = temp.kind as u8;
    }
    0
}

/// Get a label record by index.
#[no_mangle]
pub unsafe extern "C" fn phoenixvm_tcg_ir_block_get_label(
    block: *const PvTcgIrBlockHandle,
    index: usize,
    id: *mut u16,
    operation_index: *mut u32,
) -> i32 {
    if block.is_null() || id.is_null() || operation_index.is_null() {
        return -1;
    }
    let block = unsafe { &*block };
    let Some(label) = block.block.labels.get(index) else {
        return -1;
    };
    unsafe {
        *id = label.id;
        *operation_index = label.operation_index;
    }
    0
}

/// Get stable opcode bytes and operand count. The opcode pointer remains valid
/// until the block handle is freed.
#[no_mangle]
pub unsafe extern "C" fn phoenixvm_tcg_ir_block_get_operation(
    block: *const PvTcgIrBlockHandle,
    index: usize,
    opcode: *mut *const u8,
    opcode_len: *mut usize,
    param1: *mut u8,
    param2: *mut u8,
    operand_count: *mut usize,
) -> i32 {
    if block.is_null()
        || opcode.is_null()
        || opcode_len.is_null()
        || param1.is_null()
        || param2.is_null()
        || operand_count.is_null()
    {
        return -1;
    }
    let block = unsafe { &*block };
    let Some(operation) = block.block.operations.get(index) else {
        return -1;
    };
    unsafe {
        *opcode = operation.opcode.as_ptr();
        *opcode_len = operation.opcode.len();
        *param1 = operation.param1;
        *param2 = operation.param2;
        *operand_count = operation.operands.len();
    }
    0
}

/// Get a numeric operand. Tags: 0=Temp, 1=Global, 2=Immediate, 3=Label.
#[no_mangle]
pub unsafe extern "C" fn phoenixvm_tcg_ir_block_get_operand(
    block: *const PvTcgIrBlockHandle,
    operation_index: usize,
    operand_index: usize,
    tag: *mut u8,
    value: *mut i64,
) -> i32 {
    if block.is_null() || tag.is_null() || value.is_null() {
        return -1;
    }
    let block = unsafe { &*block };
    let Some(operation) = block.block.operations.get(operation_index) else {
        return -1;
    };
    let Some(operand) = operation.operands.get(operand_index) else {
        return -1;
    };
    let (operand_tag, operand_value) = match operand {
        crate::tcg_ir_cache::TcgOperand::Temp(id) => (0, i64::from(*id)),
        crate::tcg_ir_cache::TcgOperand::Global(index) => (1, i64::from(*index)),
        crate::tcg_ir_cache::TcgOperand::Immediate(value) => (2, *value),
        crate::tcg_ir_cache::TcgOperand::Label(id) => (3, i64::from(*id)),
        crate::tcg_ir_cache::TcgOperand::Helper(_) => return -1,
    };
    unsafe {
        *tag = operand_tag;
        *value = operand_value;
    }
    0
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::tcg_ir_cache::{TcgIrOperation, TcgOperand};
    use std::ffi::CString;
    use std::sync::atomic::{AtomicU64, Ordering};

    static TEST_SEQUENCE: AtomicU64 = AtomicU64::new(0);

    fn compatibility() -> PvTcgCompatibility {
        static QEMU: &[u8] = b"qemu-11.1.3-phoenixvm-pcache-abi1\0";
        static GUEST_ARCH: &[u8] = b"x86_64\0";
        static CPU: &[u8] = b"qemu64\0";
        static GUEST_BUILD: &[u8] = b"guest-test-build\0";
        static HOST_ARCH: &[u8] = b"aarch64\0";
        static HOST_ABI: &[u8] = b"android-bionic-api35\0";
        static PAGE: [u64; 1] = [0x8000];
        static CODE_HASH: [u8; 32] = [0x11; 32];
        static CONTEXT_HASH: [u8; 32] = [0x22; 32];

        PvTcgCompatibility {
            qemu_build_id: QEMU.as_ptr().cast(),
            guest_arch: GUEST_ARCH.as_ptr().cast(),
            guest_cpu_model: CPU.as_ptr().cast(),
            guest_build_id: GUEST_BUILD.as_ptr().cast(),
            host_arch: HOST_ARCH.as_ptr().cast(),
            host_abi: HOST_ABI.as_ptr().cast(),
            host_endian: 0,
            host_page_size: 4096,
            host_cpu_features: ptr::null(),
            host_cpu_features_len: 0,
            guest_pc: 0x1000,
            guest_cs_base: 0,
            tb_flags: 0,
            tb_cflags: 0,
            mmu_index: 0,
            physical_pages: PAGE.as_ptr(),
            physical_pages_len: PAGE.len(),
            guest_code_sha256: CODE_HASH.as_ptr(),
            tb_context_sha256: CONTEXT_HASH.as_ptr(),
        }
    }

    fn block() -> TcgIrBlock {
        TcgIrBlock {
            compatibility: unsafe { copy_compatibility(&compatibility()).unwrap() },
            guest_size: 4,
            instruction_count: 1,
            temporaries: Vec::new(),
            labels: Vec::new(),
            operations: vec![TcgIrOperation {
                opcode: "insn_start".into(),
                param1: 0,
                param2: 0,
                operands: vec![
                    TcgOperand::Immediate(0x1000),
                    TcgOperand::Immediate(0),
                    TcgOperand::Immediate(0),
                ],
            }],
        }
    }

    #[test]
    fn structured_c_abi_builds_stores_loads_and_inspects_tcg_ir() {
        let root = std::env::temp_dir().join(format!(
            "phoenixvm-tcg-ffi-structured-{}-{}",
            std::process::id(),
            TEST_SEQUENCE.fetch_add(1, Ordering::Relaxed)
        ));
        let root_c = CString::new(root.to_string_lossy().as_bytes()).unwrap();
        let vm = CString::new("ffi-vm-structured").unwrap();
        let key = compatibility();

        // SAFETY: input C strings and compatibility descriptors remain alive.
        let handle = unsafe { phoenixvm_tcg_l2_open(root_c.as_ptr(), vm.as_ptr(), 8, 1024 * 1024) };
        assert!(!handle.is_null());

        // SAFETY: key is valid for this call; builder owns its copied metadata.
        let builder = unsafe { phoenixvm_tcg_ir_block_create(&key, 4, 1) };
        assert!(!builder.is_null());
        assert_eq!(
            unsafe { phoenixvm_tcg_ir_block_add_temp(builder, 0, 0, 0, 1, 1, 0) },
            0
        );

        let insn = CString::new("insn_start").unwrap();
        let insn_index =
            unsafe { phoenixvm_tcg_ir_block_add_operation(builder, insn.as_ptr(), 0, 0) };
        assert_eq!(insn_index, 0);
        for value in [0x1000, 0, 0] {
            assert_eq!(
                unsafe { phoenixvm_tcg_ir_block_add_operand(builder, insn_index as u32, 2, value) },
                0
            );
        }

        let mov = CString::new("mov").unwrap();
        let mov_index = unsafe {
            phoenixvm_tcg_ir_block_add_operation(
                builder,
                mov.as_ptr(),
                crate::tcg_ir_cache::TcgValueType::I64 as u8,
                0,
            )
        };
        assert_eq!(mov_index, 1);
        assert_eq!(
            unsafe { phoenixvm_tcg_ir_block_add_operand(builder, 1, 0, 0) },
            0
        );
        assert_eq!(
            unsafe { phoenixvm_tcg_ir_block_add_operand(builder, 1, 1, 0) },
            0
        );
        assert_eq!(unsafe { phoenixvm_tcg_l2_store_block(handle, builder) }, 0);
        unsafe { phoenixvm_tcg_ir_block_free(builder) };
        assert_eq!(unsafe { phoenixvm_tcg_l2_flush(handle) }, 0);

        // Close the writer, open a fresh handle, then hydrate L1 from the
        // persistent L2 before the simulated vCPU path begins.
        unsafe { phoenixvm_tcg_l2_close(handle) };
        let handle = unsafe { phoenixvm_tcg_l2_open(root_c.as_ptr(), vm.as_ptr(), 8, 1024 * 1024) };
        assert!(!handle.is_null());
        assert_eq!(
            unsafe { phoenixvm_tcg_l2_preload(handle, 8, 1024 * 1024) },
            0
        );

        let mut probe = compatibility();
        let probe_hash = [0_u8; 32];
        probe.guest_code_sha256 = probe_hash.as_ptr();
        let mut candidate = ptr::null_mut();
        assert_eq!(
            unsafe { phoenixvm_tcg_l1_lookup_probe(handle, &probe, &mut candidate) },
            1
        );
        let (mut candidate_size, mut page_count) = (0, 0);
        let mut candidate_hash = [0_u8; 32];
        let mut candidate_pages = [0_u64; 2];
        assert_eq!(
            unsafe {
                phoenixvm_tcg_ir_block_get_validation(
                    candidate,
                    &mut candidate_size,
                    candidate_hash.as_mut_ptr(),
                    candidate_pages.as_mut_ptr(),
                    &mut page_count,
                )
            },
            0
        );
        assert_eq!(candidate_size, 4);
        assert_eq!(candidate_hash, [0x11; 32]);
        assert_eq!(page_count, 1);
        assert_eq!(candidate_pages[0], 0x8000);
        unsafe { phoenixvm_tcg_ir_block_free(candidate) };

        // Make the backing directory unavailable after preload. A valid L1
        // lookup must still succeed, proving that the lookup path is memory-only.
        let offline_root = root.with_extension("offline");
        std::fs::rename(&root, &offline_root).unwrap();
        let mut decoded = ptr::null_mut();
        assert_eq!(
            unsafe { phoenixvm_tcg_l1_lookup(handle, &key, &mut decoded) },
            1
        );
        assert!(!decoded.is_null());

        let (mut guest_size, mut instruction_count) = (0, 0);
        let (mut temp_count, mut label_count, mut operation_count) = (0, 0, 0);
        assert_eq!(
            unsafe {
                phoenixvm_tcg_ir_block_get_summary(
                    decoded,
                    &mut guest_size,
                    &mut instruction_count,
                    &mut temp_count,
                    &mut label_count,
                    &mut operation_count,
                )
            },
            0
        );
        assert_eq!((guest_size, instruction_count), (4, 1));
        assert_eq!((temp_count, label_count, operation_count), (1, 0, 2));

        let (mut id, mut base_id) = (0, 0);
        let (mut subindex, mut base_type, mut value_type, mut kind) = (0, 0, 0, 0);
        assert_eq!(
            unsafe {
                phoenixvm_tcg_ir_block_get_temp(
                    decoded,
                    0,
                    &mut id,
                    &mut base_id,
                    &mut subindex,
                    &mut base_type,
                    &mut value_type,
                    &mut kind,
                )
            },
            0
        );
        assert_eq!((id, base_id, subindex), (0, 0, 0));
        assert_eq!((base_type, value_type, kind), (1, 1, 0));

        let (mut opcode, mut opcode_len) = (ptr::null(), 0);
        let (mut param1, mut param2, mut operand_count) = (0, 0, 0);
        assert_eq!(
            unsafe {
                phoenixvm_tcg_ir_block_get_operation(
                    decoded,
                    1,
                    &mut opcode,
                    &mut opcode_len,
                    &mut param1,
                    &mut param2,
                    &mut operand_count,
                )
            },
            0
        );
        assert_eq!(unsafe { slice::from_raw_parts(opcode, opcode_len) }, b"mov");
        assert_eq!((param1, param2, operand_count), (1, 0, 2));

        let (mut tag, mut value) = (0, 0);
        assert_eq!(
            unsafe { phoenixvm_tcg_ir_block_get_operand(decoded, 1, 0, &mut tag, &mut value) },
            0
        );
        assert_eq!((tag, value), (0, 0));
        assert_eq!(
            unsafe { phoenixvm_tcg_ir_block_get_operand(decoded, 1, 1, &mut tag, &mut value) },
            0
        );
        assert_eq!((tag, value), (1, 0));

        unsafe { phoenixvm_tcg_ir_block_free(decoded) };
        unsafe { phoenixvm_tcg_l2_close(handle) };
        std::fs::remove_dir_all(offline_root).unwrap();
    }

    #[test]
    fn c_abi_close_drains_accepted_worker_queue_before_library_unload() {
        let root = std::env::temp_dir().join(format!(
            "phoenixvm-tcg-ffi-close-drain-{}-{}",
            std::process::id(),
            TEST_SEQUENCE.fetch_add(1, Ordering::Relaxed)
        ));
        let root_c = CString::new(root.to_string_lossy().as_bytes()).unwrap();
        let vm = CString::new("ffi-vm-close-drain").unwrap();
        let key = compatibility();

        let writer = unsafe {
            phoenixvm_tcg_l2_open(root_c.as_ptr(), vm.as_ptr(), 8, 1024 * 1024)
        };
        assert!(!writer.is_null());

        let builder = unsafe { phoenixvm_tcg_ir_block_create(&key, 4, 1) };
        assert!(!builder.is_null());
        let opcode = CString::new("insn_start").unwrap();
        let operation = unsafe {
            phoenixvm_tcg_ir_block_add_operation(builder, opcode.as_ptr(), 0, 0)
        };
        assert_eq!(operation, 0);
        for value in [0x1000_i64, 0, 0] {
            assert_eq!(
                unsafe {
                    phoenixvm_tcg_ir_block_add_operand(
                        builder,
                        operation as u32,
                        2,
                        value,
                    )
                },
                0
            );
        }

        // The store is only enqueued. Close must disconnect and join the worker,
        // which drains this accepted command before returning to the C caller.
        assert_eq!(unsafe { phoenixvm_tcg_l2_store_block(writer, builder) }, 0);
        unsafe { phoenixvm_tcg_ir_block_free(builder) };
        unsafe { phoenixvm_tcg_l2_close(writer) };

        let reader = unsafe {
            phoenixvm_tcg_l2_open(root_c.as_ptr(), vm.as_ptr(), 8, 1024 * 1024)
        };
        assert!(!reader.is_null());
        assert_eq!(
            unsafe { phoenixvm_tcg_l2_preload(reader, 8, 1024 * 1024) },
            0
        );

        let mut probe = compatibility();
        let zero_hash = [0_u8; 32];
        probe.guest_code_sha256 = zero_hash.as_ptr();
        let mut cached = ptr::null_mut();
        assert_eq!(
            unsafe { phoenixvm_tcg_l1_lookup_probe(reader, &probe, &mut cached) },
            1
        );
        assert!(!cached.is_null());
        unsafe { phoenixvm_tcg_ir_block_free(cached) };
        unsafe { phoenixvm_tcg_l2_close(reader) };
        std::fs::remove_dir_all(root).unwrap();
    }

    #[test]
    fn resident_probe_ignores_only_the_guest_code_digest() {
        let block = block();
        let key = block.compatibility.clone();
        let mut probe = key.clone();
        probe.guest_code_sha256 = [0; 32];

        let clock = AtomicU64::new(0);
        let mut resident = ResidentL1::new(8, 1024 * 1024);
        resident.insert(block, &clock).unwrap();
        assert!(resident.lookup_probe(&probe, &clock).unwrap().is_some());

        let mut changed = probe.clone();
        changed.tb_flags ^= 1;
        assert!(resident.lookup_probe(&changed, &clock).unwrap().is_none());

        let mut changed_page = probe.clone();
        changed_page.physical_pages[0] ^= 0x1000;
        assert!(resident
            .lookup_probe(&changed_page, &clock)
            .unwrap()
            .is_none());
    }

    #[test]
    fn vcpu_lookup_fails_fast_during_worker_side_l1_write() {
        let root = std::env::temp_dir().join(format!(
            "phoenixvm-tcg-ffi-lock-{}-{}",
            std::process::id(),
            TEST_SEQUENCE.fetch_add(1, Ordering::Relaxed)
        ));
        let root_c = CString::new(root.to_string_lossy().as_bytes()).unwrap();
        let vm = CString::new("ffi-vm-lock").unwrap();
        let key = compatibility();
        let handle = unsafe { phoenixvm_tcg_l2_open(root_c.as_ptr(), vm.as_ptr(), 8, 1024 * 1024) };
        assert!(!handle.is_null());

        let handle_ref = unsafe { &*handle };
        let write_guard = handle_ref.resident.write().unwrap();
        let mut block = ptr::null_mut();
        let started = std::time::Instant::now();
        assert_eq!(
            unsafe { phoenixvm_tcg_l1_lookup(handle, &key, &mut block) },
            -1
        );
        assert!(started.elapsed() < std::time::Duration::from_millis(100));
        drop(write_guard);

        assert_eq!(
            unsafe { phoenixvm_tcg_l1_lookup(handle, &key, &mut block) },
            0
        );
        unsafe { phoenixvm_tcg_l2_close(handle) };
        std::fs::remove_dir_all(root).unwrap();
    }

    #[test]
    fn c_abi_round_trips_vm_scoped_v3_records_and_invalidation() {
        let root = std::env::temp_dir().join(format!(
            "phoenixvm-tcg-ffi-{}-{}",
            std::process::id(),
            TEST_SEQUENCE.fetch_add(1, Ordering::Relaxed)
        ));
        let root_c = CString::new(root.to_string_lossy().as_bytes()).unwrap();
        let vm = CString::new("ffi-vm-one").unwrap();

        // SAFETY: C strings remain alive through open; the returned handle is
        // used only by this test and closed exactly once.
        let handle = unsafe { phoenixvm_tcg_l2_open(root_c.as_ptr(), vm.as_ptr(), 8, 1024 * 1024) };
        assert!(!handle.is_null());

        let block = block();
        let encoded = block.encode().unwrap();
        // SAFETY: encoded remains alive for the duration of this call.
        assert_eq!(
            unsafe { phoenixvm_tcg_l2_store(handle, encoded.as_ptr(), encoded.len()) },
            0
        );

        let key = compatibility();
        let mut record = ptr::null_mut();
        let mut record_len = 0;
        // SAFETY: key points to static data, and out parameters are valid.
        assert_eq!(
            unsafe { phoenixvm_tcg_l2_load(handle, &key, &mut record, &mut record_len) },
            1
        );
        assert!(!record.is_null());
        // SAFETY: the load function returned this valid buffer and its length.
        let loaded = unsafe { slice::from_raw_parts(record, record_len) };
        assert_eq!(TcgIrBlock::decode(loaded).unwrap(), block);
        // SAFETY: free the exact allocation returned by load, exactly once.
        unsafe { phoenixvm_tcg_l2_free_buffer(record, record_len) };

        let mut changed = compatibility();
        let mut changed_hash = [0x11_u8; 32];
        changed_hash[0] ^= 0x80;
        changed.guest_code_sha256 = changed_hash.as_ptr();
        record = ptr::null_mut();
        record_len = 0;
        // SAFETY: changed_hash remains alive through the lookup.
        assert_eq!(
            unsafe { phoenixvm_tcg_l2_load(handle, &changed, &mut record, &mut record_len) },
            0
        );

        // SAFETY: the key and handle remain valid.
        assert_eq!(unsafe { phoenixvm_tcg_l2_invalidate(handle, &key) }, 0);
        // SAFETY: close the only handle created by this test.
        unsafe { phoenixvm_tcg_l2_close(handle) };
        std::fs::remove_dir_all(root).unwrap();
    }
}
