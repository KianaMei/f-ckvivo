use super::verify::{self, Verdict};
use jni::objects::{JObject, JString};
use jni::sys::{jint, jstring};
use jni::{Env, EnvUnowned, Outcome};
use std::fs::File;

// Kept in step with io.github.nuke.oss.app.shared.integrity.NativeSignature.
const MATCHES: jint = 0;
const MISMATCH: jint = 1;
const UNCONFIGURED: jint = 2;
const UNREADABLE: jint = 3;

fn path(env: &Env<'_>, path: &JString<'_>) -> Option<String> {
    path.try_to_string(env).ok()
}

/// Compares the APK at `path` with the compiled-in release certificate. Never throws and never unwinds: anything that
/// goes wrong, a panic included, is reported as unreadable.
#[no_mangle]
pub extern "system" fn Java_io_github_nuke_oss_app_shared_integrity_NativeSignature_verify(
    mut env: EnvUnowned<'_>,
    _receiver: JObject<'_>,
    apk: JString<'_>,
) -> jint {
    let verdict = env
        .with_env(|env| -> jni::errors::Result<_> {
            Ok(path(env, &apk).map(|apk| verify::verify(&apk)))
        })
        .into_outcome();
    match verdict {
        Outcome::Ok(Some(Verdict::Matches)) => MATCHES,
        Outcome::Ok(Some(Verdict::Mismatch)) => MISMATCH,
        Outcome::Ok(Some(Verdict::Unconfigured)) => UNCONFIGURED,
        _ => UNREADABLE,
    }
}

/// The SHA-256 of the APK's signer certificate in lowercase hex, for diagnostics; null when it cannot be read.
#[no_mangle]
pub extern "system" fn Java_io_github_nuke_oss_app_shared_integrity_NativeSignature_signerDigest(
    mut env: EnvUnowned<'_>,
    _receiver: JObject<'_>,
    apk: JString<'_>,
) -> jstring {
    let digest = env
        .with_env(|env| -> jni::errors::Result<_> {
            let Some(text) = path(env, &apk)
                .and_then(|apk| File::open(apk).ok())
                .and_then(|mut file| verify::signer_digest(&mut file).ok())
                .map(|digest| verify::hex(&digest))
            else {
                return Ok(std::ptr::null_mut());
            };
            env.new_string(text).map(|value| value.into_raw())
        })
        .into_outcome();
    match digest {
        Outcome::Ok(text) => text,
        Outcome::Err(_) | Outcome::Panic(_) => std::ptr::null_mut(),
    }
}
