use super::{
    correlate, decode, decode_table, encode, encode_table, validate_request, wrap_payload,
    Correlation,
};
use crate::{ErrorCode, ProtocolError, ABI_VERSION};
use jni::objects::{JByteArray, JObject, JThrowable};
use jni::strings::JNIString;
use jni::sys::{jbyteArray, jint, jlong};
use jni::{jni_sig, jni_str, Env, EnvUnowned, Outcome};

// Bit flags describing which correlation inputs the host supplied.
const HAS_REQUEST_SEQUENCE: jint = 1;
const HAS_RESPONSE_CMD: jint = 2;
const HAS_RESPONSE_SEQUENCE: jint = 4;

fn throw(env: &mut Env<'_>, error: &ProtocolError) {
    if env.exception_check() {
        return;
    }
    let thrown = env.new_string(&error.message).and_then(|message| {
        let object = env.new_object(
            jni_str!("io/github/nuke/oss/app/tiktok/protocol/NativeProtocolException"),
            jni_sig!("(ILjava/lang/String;)V"),
            &[(error.code as jint).into(), (&message).into()],
        )?;
        env.cast_local::<JThrowable>(object)
    });
    match thrown {
        Ok(throwable) => {
            let _ = env.throw(throwable);
        }
        Err(_) => {
            let _ = env.throw_new(
                jni_str!("java/lang/IllegalStateException"),
                JNIString::from(error.message.as_str()),
            );
        }
    }
}

// Runs one native call so that neither an error nor a panic unwinds into the JVM; failures become a
// Java exception and the call returns the supplied default.
fn guarded<'local, T>(
    env: &mut EnvUnowned<'local>,
    default: T,
    operation: impl FnOnce(&mut Env<'local>) -> Result<T, ProtocolError>,
) -> T {
    let error = match env
        .with_env(|env| -> jni::errors::Result<_> { Ok(operation(env)) })
        .into_outcome()
    {
        Outcome::Ok(Ok(value)) => return value,
        Outcome::Ok(Err(error)) => error,
        Outcome::Err(error) => ProtocolError::new(ErrorCode::Internal, error.to_string()),
        Outcome::Panic(_) => {
            ProtocolError::new(ErrorCode::Internal, "Native protocol core panicked")
        }
    };
    // A panic while throwing is caught here too and only loses the exception.
    let _ = env
        .with_env(|env| -> jni::errors::Result<_> {
            throw(env, &error);
            Ok(())
        })
        .into_outcome();
    default
}

fn read(env: &Env<'_>, array: &JByteArray<'_>) -> Result<Vec<u8>, ProtocolError> {
    env.convert_byte_array(array).map_err(|_| {
        ProtocolError::new(ErrorCode::InvalidArgument, "Cannot read a Java byte array")
    })
}

fn write(env: &mut Env<'_>, bytes: &[u8]) -> Result<jbyteArray, ProtocolError> {
    env.byte_array_from_slice(bytes)
        .map(|array| array.into_raw())
        .map_err(|_| ProtocolError::new(ErrorCode::Internal, "Cannot allocate a Java byte array"))
}

#[no_mangle]
pub extern "system" fn Java_io_github_nuke_oss_app_tiktok_protocol_NativeProtocol_abiVersion(
    _env: EnvUnowned<'_>,
    _receiver: JObject<'_>,
) -> jint {
    ABI_VERSION
}

#[no_mangle]
pub extern "system" fn Java_io_github_nuke_oss_app_tiktok_protocol_NativeProtocol_decode(
    mut env: EnvUnowned<'_>,
    _receiver: JObject<'_>,
    bytes: JByteArray<'_>,
) -> jbyteArray {
    guarded(&mut env, std::ptr::null_mut(), |env| {
        let table = encode_table(&decode(&read(env, &bytes)?)?)?;
        write(env, &table)
    })
}

#[no_mangle]
pub extern "system" fn Java_io_github_nuke_oss_app_tiktok_protocol_NativeProtocol_encode(
    mut env: EnvUnowned<'_>,
    _receiver: JObject<'_>,
    table: JByteArray<'_>,
) -> jbyteArray {
    guarded(&mut env, std::ptr::null_mut(), |env| {
        let bytes = encode(&decode_table(&read(env, &table)?)?)?;
        write(env, &bytes)
    })
}

#[no_mangle]
pub extern "system" fn Java_io_github_nuke_oss_app_tiktok_protocol_NativeProtocol_wrapPayload(
    mut env: EnvUnowned<'_>,
    _receiver: JObject<'_>,
    body_tag: jint,
    payload: JByteArray<'_>,
) -> jbyteArray {
    guarded(&mut env, std::ptr::null_mut(), |env| {
        let body = wrap_payload(body_tag, &read(env, &payload)?)?;
        write(env, &body)
    })
}

#[no_mangle]
pub extern "system" fn Java_io_github_nuke_oss_app_tiktok_protocol_NativeProtocol_validateRequest(
    mut env: EnvUnowned<'_>,
    _receiver: JObject<'_>,
    cmd: jint,
    inbox_type: jint,
    body_length: jint,
) {
    guarded(&mut env, (), |_| {
        let length = usize::try_from(body_length).map_err(|_| {
            ProtocolError::new(ErrorCode::InvalidArgument, "Negative RequestBody length")
        })?;
        validate_request(cmd, inbox_type, length)
    })
}

#[no_mangle]
pub extern "system" fn Java_io_github_nuke_oss_app_tiktok_protocol_NativeProtocol_correlate(
    mut env: EnvUnowned<'_>,
    _receiver: JObject<'_>,
    expected_cmd: jint,
    present: jint,
    request_sequence: jlong,
    response_cmd: jint,
    response_sequence: jlong,
) {
    guarded(&mut env, (), |_| {
        let has = |flag: jint| present & flag != 0;
        correlate(
            expected_cmd,
            Correlation {
                request_sequence: has(HAS_REQUEST_SEQUENCE).then_some(request_sequence),
                response_cmd: has(HAS_RESPONSE_CMD).then_some(response_cmd),
                response_sequence: has(HAS_RESPONSE_SEQUENCE).then_some(response_sequence),
            },
        )
    })
}
