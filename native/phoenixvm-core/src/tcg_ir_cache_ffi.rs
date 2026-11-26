use crate::tcg_ir_cache::{TcgEndian, TcgIrBlock, TcgIrCompatibility, TcgIrL2Store};
use std::ffi::{c_char, CStr};
use std::panic::{catch_unwind, AssertUnwindSafe};
use std::path::PathBuf;
use std::ptr;
use std::slice;

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
    store: TcgIrL2Store,
    vm_id: String,
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
        let store =
            TcgIrL2Store::new(PathBuf::from(root), max_entries, max_bytes).map_err(|_| ())?;
        Ok::<_, ()>(Box::into_raw(Box::new(PvTcgL2Handle { store, vm_id })))
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
        drop(unsafe { Box::from_raw(handle) });
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
        handle.store.invalidate(&handle.vm_id, &key).map_err(|_| ())
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
        handle.store.invalidate_vm(&handle.vm_id).map_err(|_| ())
    }));
    if matches!(result, Ok(Ok(()))) {
        0
    } else {
        -1
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::tcg_ir_cache::{TcgIrOperation, TcgOperand};
    use std::ffi::CString;

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
    fn c_abi_round_trips_vm_scoped_v3_records_and_invalidation() {
        let root = std::env::temp_dir().join(format!(
            "phoenixvm-tcg-ffi-{}-{}",
            std::process::id(),
            TEMP_FILE_SEQUENCE.fetch_add(1, Ordering::Relaxed)
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
        fs::remove_dir_all(root).unwrap();
    }
}
