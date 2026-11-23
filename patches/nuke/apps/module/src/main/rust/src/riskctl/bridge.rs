use super::{encode, scan};
use jni::objects::JObject;
use jni::sys::jstring;
use jni::{EnvUnowned, Outcome};

/// The device trust checks, one per line as `id<TAB>level<TAB>detail` (see `encode`); null when anything goes wrong, a
/// panic included, so nothing unwinds across the JNI boundary.
#[no_mangle]
pub extern "system" fn Java_io_github_nuke_oss_app_shared_integrity_NativeRisk_scan(
    mut env: EnvUnowned<'_>,
    _receiver: JObject<'_>,
) -> jstring {
    match env
        .with_env(|env| {
            env.new_string(encode(&scan()))
                .map(|value| value.into_raw())
        })
        .into_outcome()
    {
        Outcome::Ok(text) => text,
        Outcome::Err(_) | Outcome::Panic(_) => std::ptr::null_mut(),
    }
}
