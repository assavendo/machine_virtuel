pub mod backend;
pub mod translation;

use backend::{select_backend, Backend, HostFeatures};
use jni::objects::{JClass, JIntArray};
use jni::sys::{jboolean, jint, jlong, jstring};
use jni::JNIEnv;
use std::mem::size_of;

#[derive(Clone)]
#[repr(C)]
struct CpuSet {
    bits: [u64; 16],
}

impl Default for CpuSet {
    fn default() -> Self {
        Self { bits: [0; 16] }
    }
}

fn build_cpu_set(cpu_ids: &[i32]) -> Option<CpuSet> {
    if cpu_ids.is_empty() || cpu_ids.iter().any(|cpu| !(0..1024).contains(cpu)) {
        return None;
    }
    let mut mask = CpuSet::default();
    for cpu in cpu_ids {
        let index = *cpu as usize;
        mask.bits[index / 64] |= 1_u64 << (index % 64);
    }
    Some(mask)
}

#[cfg(any(target_os = "linux", target_os = "android"))]
extern "C" {
    fn sched_setaffinity(pid: i32, cpusetsize: usize, mask: *const CpuSet) -> i32;
}
use translation::{TranslationProfile, TranslationTier};

fn backend_name(value: Backend) -> &'static str {
    match value {
        Backend::Kvm => "KVM",
        Backend::Gunyah => "GUNYAH",
        Backend::GenieZone => "GENIEZONE",
        Backend::Halla => "HALLA",
        Backend::Avf => "AVF",
        Backend::QemuTcg => "QEMU_TCG",
    }
}

fn translation_name(value: TranslationTier) -> &'static str {
    match value {
        TranslationTier::Cold => "COLD",
        TranslationTier::Warm => "WARM",
        TranslationTier::Hot => "HOT",
        TranslationTier::VeryHot => "VERY_HOT",
    }
}

#[no_mangle]
pub extern "system" fn Java_com_assavendo_machinevirtuel_engine_NativeEngine_nativeVersion(
    mut env: JNIEnv,
    _class: JClass,
) -> jstring {
    let value = format!(
        "PhoenixVM native core {} / {}",
        env!("CARGO_PKG_VERSION"),
        std::env::consts::ARCH
    );
    match env.new_string(value) {
        Ok(s) => s.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}

#[no_mangle]
pub extern "system" fn Java_com_assavendo_machinevirtuel_engine_NativeEngine_nativeSelectBackend(
    mut env: JNIEnv,
    _class: JClass,
    arm64_guest: jboolean,
    kvm: jboolean,
    gunyah: jboolean,
    genie_zone: jboolean,
    halla: jboolean,
    avf: jboolean,
    thermal_status: jint,
) -> jstring {
    let host = HostFeatures {
        kvm: kvm != 0,
        gunyah: gunyah != 0,
        genie_zone: genie_zone != 0,
        halla: halla != 0,
        avf: avf != 0,
        thermal_status,
    };

    let selected = select_backend(arm64_guest != 0, host);
    match env.new_string(backend_name(selected)) {
        Ok(s) => s.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}

#[no_mangle]
pub extern "system" fn Java_com_assavendo_machinevirtuel_engine_NativeEngine_nativeTranslationTier(
    mut env: JNIEnv,
    _class: JClass,
    executions: jlong,
) -> jstring {
    let profile = TranslationProfile {
        executions: executions.max(0) as u64,
        cache_hits: 0,
        cache_misses: 0,
    };
    match env.new_string(translation_name(profile.tier())) {
        Ok(s) => s.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}


#[no_mangle]
pub extern "system" fn Java_com_assavendo_machinevirtuel_engine_NativeEngine_nativeSetThreadAffinity(
    mut env: JNIEnv,
    _class: JClass,
    thread_id: jlong,
    cpu_ids: JIntArray,
) -> jint {
    if thread_id <= 0 || thread_id > i32::MAX as jlong {
        return -22; // EINVAL
    }

    let length = match env.get_array_length(&cpu_ids) {
        Ok(value) if value > 0 && value <= 1024 => value as usize,
        _ => return -22,
    };
    let mut ids = vec![0_i32; length];
    if env.get_int_array_region(&cpu_ids, 0, &mut ids).is_err() {
        return -22;
    }
    let mask = match build_cpu_set(&ids) {
        Some(value) => value,
        None => return -22,
    };

    #[cfg(any(target_os = "linux", target_os = "android"))]
    {
        let status = unsafe {
            sched_setaffinity(thread_id as i32, size_of::<CpuSet>(), &mask as *const CpuSet)
        };
        if status == 0 {
            0
        } else {
            -std::io::Error::last_os_error()
                .raw_os_error()
                .unwrap_or(1)
                .abs()
        }
    }

    #[cfg(not(any(target_os = "linux", target_os = "android")))]
    {
        let _ = mask;
        -95 // EOPNOTSUPP
    }
}

#[cfg(test)]
mod cpu_affinity_tests {
    use super::*;

    #[test]
    fn builds_cpu_masks_across_machine_word_boundaries() {
        let mask = build_cpu_set(&[0, 1, 63, 64, 127, 1023]).unwrap();
        assert_eq!(mask.bits[0] & 1, 1);
        assert_eq!(mask.bits[0] & (1_u64 << 63), 1_u64 << 63);
        assert_eq!(mask.bits[1] & 1, 1);
        assert_eq!(mask.bits[1] & (1_u64 << 63), 1_u64 << 63);
        assert_eq!(mask.bits[15] & (1_u64 << 63), 1_u64 << 63);
    }

    #[test]
    fn rejects_empty_and_out_of_range_cpu_masks() {
        assert!(build_cpu_set(&[]).is_none());
        assert!(build_cpu_set(&[-1]).is_none());
        assert!(build_cpu_set(&[1024]).is_none());
    }
}
