pub mod backend;
pub mod translation;

use backend::{select_backend, Backend, HostFeatures};
use jni::objects::JClass;
use jni::sys::{jboolean, jint, jlong, jstring};
use jni::JNIEnv;
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
