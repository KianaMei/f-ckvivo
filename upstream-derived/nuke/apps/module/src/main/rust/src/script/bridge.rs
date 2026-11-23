use super::package;
use jni::objects::{JObject, JString};
use jni::sys::{jint, jstring};
use jni::{EnvUnowned, Outcome};
use serde_json::{json, Value};
use std::path::Path;

/// Installs the package at `package` into the empty directory `destination` and answers with JSON: either
/// `{"ok":true,"manifest":{...},"sha256":"..."}` or `{"ok":false,"code":"...","message":"..."}`. Never throws and never
/// unwinds; null only when not even the answer string can be created.
#[no_mangle]
pub extern "system" fn Java_io_github_nuke_oss_app_shared_script_NativeScriptPackage_install(
    mut env: EnvUnowned<'_>,
    _receiver: JObject<'_>,
    package: JString<'_>,
    destination: JString<'_>,
    max_api: jint,
) -> jstring {
    let answer = env
        .with_env(|env| -> jni::errors::Result<_> {
            let (Ok(package), Ok(destination)) =
                (package.try_to_string(env), destination.try_to_string(env))
            else {
                return Ok(None);
            };
            Ok(Some(install(&package, &destination, max_api)))
        })
        .into_outcome();
    let answer = match answer {
        Outcome::Ok(Some(value)) => value,
        Outcome::Ok(None) => {
            json!({ "ok": false, "code": "INVALID_ARGUMENT", "message": "Unreadable path arguments" })
        }
        Outcome::Err(_) | Outcome::Panic(_) => {
            json!({ "ok": false, "code": "INTERNAL", "message": "The package reader panicked" })
        }
    };
    match env
        .with_env(|env| {
            env.new_string(answer.to_string())
                .map(|value| value.into_raw())
        })
        .into_outcome()
    {
        Outcome::Ok(text) => text,
        Outcome::Err(_) | Outcome::Panic(_) => std::ptr::null_mut(),
    }
}

fn install(package: &str, destination: &str, max_api: jint) -> Value {
    match package::install(
        Path::new(package),
        Path::new(destination),
        i64::from(max_api),
    ) {
        Ok(installed) => {
            json!({ "ok": true, "manifest": installed.manifest, "sha256": installed.sha256 })
        }
        Err(error) => json!({ "ok": false, "code": error.code, "message": error.message }),
    }
}
