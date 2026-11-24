pub mod backend;
pub mod translation;

use jni::objects::JClass;
use jni::sys::jstring;
use jni::JNIEnv;

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
