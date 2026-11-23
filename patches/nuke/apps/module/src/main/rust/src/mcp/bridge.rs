use super::base64;
use super::{
    issue, Claimed, Config, Contact, ContactKind, Host, HostError, Image, Reach, Reward,
    SendStatus, Sent, Server, ENTROPY_BYTES,
};
use jni::objects::{JByteArray, JObject, JString};
use jni::sys::{jboolean, jint, jlong, jstring};
use jni::{jni_sig, jni_str, Env, EnvUnowned, Outcome};
use serde_json::{json, Value};
use std::sync::atomic::{AtomicI64, Ordering};
use std::sync::{Arc, Mutex, PoisonError};

// The open servers by handle. Kotlin only ever holds the number, so a stale or made-up handle finds nothing instead of
// reaching freed memory.
static SERVERS: Mutex<Vec<(i64, Arc<Server>)>> = Mutex::new(Vec::new());
static NEXT_HANDLE: AtomicI64 = AtomicI64::new(1);

fn server(handle: jlong) -> Option<Arc<Server>> {
    let servers = SERVERS.lock().unwrap_or_else(PoisonError::into_inner);
    servers
        .iter()
        .find(|(known, _)| *known == handle)
        .map(|(_, server)| server.clone())
}

fn text(env: &mut EnvUnowned<'_>, value: Option<String>) -> jstring {
    let Some(value) = value else {
        return std::ptr::null_mut();
    };
    match env
        .with_env(|env| env.new_string(value).map(|value| value.into_raw()))
        .into_outcome()
    {
        Outcome::Ok(text) => text,
        Outcome::Err(_) | Outcome::Panic(_) => std::ptr::null_mut(),
    }
}

/// Opens a server with the stored configuration, on 127.0.0.1 or, with `lan`, on every IPv4 interface for the local
/// networks, and answers with JSON: `{"ok":true,"server":<handle>,
/// "port":<port>,"tokens":<accepted>}` or `{"ok":false,"code":"...","message":"..."}`. Nothing is served until a thread
/// calls `serve`. Never throws and never unwinds; null only when not even the answer can be created.
#[no_mangle]
pub extern "system" fn Java_io_github_nuke_oss_app_tiktok_mcp_NativeMcp_open(
    mut env: EnvUnowned<'_>,
    _receiver: JObject<'_>,
    port: jint,
    lan: jboolean,
    config: JString<'_>,
) -> jstring {
    let answer = env
        .with_env(|env| -> jni::errors::Result<_> {
            let Ok(config) = config.try_to_string(env) else {
                return Ok(json!({ "ok": false, "code": "INVALID_ARGUMENT", "message": "Unreadable configuration" }));
            };
            let Ok(port) = u16::try_from(port) else {
                return Ok(json!({ "ok": false, "code": "INVALID_ARGUMENT", "message": "The port must be 0 to 65535" }));
            };
            let config = Config::parse(&config);
            let tokens = config.len();
            let reach = if lan { Reach::Lan } else { Reach::Loopback };
            Ok(match Server::open(port, reach, config) {
                Ok(server) => {
                    let handle = NEXT_HANDLE.fetch_add(1, Ordering::SeqCst);
                    let port = server.port();
                    SERVERS
                        .lock()
                        .unwrap_or_else(PoisonError::into_inner)
                        .push((handle, Arc::new(server)));
                    json!({ "ok": true, "server": handle, "port": port, "tokens": tokens })
                }
                Err(error) => {
                    json!({ "ok": false, "code": "BIND_FAILED", "message": error.to_string() })
                }
            })
        })
        .into_outcome();
    let answer = match answer {
        Outcome::Ok(answer) => answer,
        Outcome::Err(_) | Outcome::Panic(_) => {
            json!({ "ok": false, "code": "INTERNAL", "message": "The MCP server panicked" })
        }
    };
    text(&mut env, Some(answer.to_string()))
}

/// Makes the calling thread a worker of the server until it is closed. `host` is called on this thread only, through
/// `McpHost.request(String, String, byte[]): String`, and is not kept after the call returns. Returns at once for an unknown
/// handle.
#[no_mangle]
pub extern "system" fn Java_io_github_nuke_oss_app_tiktok_mcp_NativeMcp_serve(
    mut env: EnvUnowned<'_>,
    _receiver: JObject<'_>,
    handle: jlong,
    host: JObject<'_>,
) {
    let _ = env
        .with_env(|env| -> jni::errors::Result<()> {
            if let Some(server) = server(handle) {
                server.serve(&mut JniHost { env, host: &host });
            }
            Ok(())
        })
        .into_outcome();
}

/// Replaces the tokens of a server and returns how many the configuration gave it, or -1 for an unknown handle. A
/// configuration that does not read leaves the server without tokens.
#[no_mangle]
pub extern "system" fn Java_io_github_nuke_oss_app_tiktok_mcp_NativeMcp_configure(
    mut env: EnvUnowned<'_>,
    _receiver: JObject<'_>,
    handle: jlong,
    config: JString<'_>,
) -> jint {
    let accepted = env
        .with_env(|env| -> jni::errors::Result<_> {
            let Some(server) = server(handle) else {
                return Ok(-1);
            };
            let config = Config::parse(&config.try_to_string(env).unwrap_or_default());
            let accepted = jint::try_from(config.len()).unwrap_or(jint::MAX);
            server.configure(config);
            Ok(accepted)
        })
        .into_outcome();
    match accepted {
        Outcome::Ok(accepted) => accepted,
        Outcome::Err(_) | Outcome::Panic(_) => -1,
    }
}

/// The server's port, activity record and last-used times as JSON; null for an unknown handle.
#[no_mangle]
pub extern "system" fn Java_io_github_nuke_oss_app_tiktok_mcp_NativeMcp_snapshot(
    mut env: EnvUnowned<'_>,
    _receiver: JObject<'_>,
    handle: jlong,
) -> jstring {
    let snapshot =
        std::panic::catch_unwind(|| server(handle).map(|server| server.snapshot().to_string()));
    text(&mut env, snapshot.ok().flatten())
}

/// Stops a server and forgets its handle. Its workers return from `serve` on their own; the port is free once the last
/// one has. Safe to call twice.
#[no_mangle]
pub extern "system" fn Java_io_github_nuke_oss_app_tiktok_mcp_NativeMcp_close(
    _env: EnvUnowned<'_>,
    _receiver: JObject<'_>,
    handle: jlong,
) {
    let _ = std::panic::catch_unwind(|| {
        let closed = {
            let mut servers = SERVERS.lock().unwrap_or_else(PoisonError::into_inner);
            let at = servers.iter().position(|(known, _)| *known == handle);
            at.map(|at| servers.swap_remove(at).1)
        };
        if let Some(server) = closed {
            server.close();
        }
    });
}

/// Builds a credential from `ENTROPY_BYTES` of secure randomness, for a new token or, with `id`, as the new secret of
/// an existing one. Answers `{"id":"...","secretHash":"...","token":"..."}`, or null when the arguments do not fit.
#[no_mangle]
pub extern "system" fn Java_io_github_nuke_oss_app_tiktok_mcp_NativeMcp_issue(
    mut env: EnvUnowned<'_>,
    _receiver: JObject<'_>,
    entropy: JByteArray<'_>,
    id: JString<'_>,
) -> jstring {
    let issued = env
        .with_env(|env| -> jni::errors::Result<_> {
            let Ok(entropy) = env.convert_byte_array(&entropy) else {
                return Ok(None);
            };
            let id = if id.is_null() {
                None
            } else {
                match id.try_to_string(env) {
                    Ok(id) => Some(id),
                    Err(_) => return Ok(None),
                }
            };
            Ok(issue(&entropy, id.as_deref()).map(|issued| {
                json!({ "id": issued.id, "secretHash": issued.secret_hash, "token": issued.token }).to_string()
            }))
        })
        .into_outcome();
    match issued {
        Outcome::Ok(issued) => text(&mut env, issued),
        Outcome::Err(_) | Outcome::Panic(_) => std::ptr::null_mut(),
    }
}

/// How much randomness `issue` takes.
#[no_mangle]
pub extern "system" fn Java_io_github_nuke_oss_app_tiktok_mcp_NativeMcp_entropyBytes(
    _env: EnvUnowned<'_>,
    _receiver: JObject<'_>,
) -> jint {
    ENTROPY_BYTES as jint
}

// The app as the Kotlin side offers it: one upcall that takes an operation, its arguments as JSON and the bytes that
// go with them, if any, and answers with JSON, `{"ok":true,...}` or `{"ok":false,"code":"...","message":"..."}`. It lives for one `serve` call on one thread.
struct JniHost<'a, 'local, 'host> {
    env: &'a mut Env<'local>,
    host: &'a JObject<'host>,
}

impl JniHost<'_, '_, '_> {
    fn request(
        &mut self,
        operation: &str,
        arguments: Value,
        payload: Option<&[u8]>,
    ) -> Result<Value, HostError> {
        let host = self.host;
        // Each upcall gets a frame of its own: the worker stays in native code for as long as the server runs, so
        // local references would otherwise pile up until it ends.
        let answer = self
            .env
            .with_local_frame(8, |env| -> jni::errors::Result<Option<String>> {
                let operation = env.new_string(operation)?;
                let arguments = env.new_string(arguments.to_string())?;
                let payload = match payload {
                    Some(bytes) => env.byte_array_from_slice(bytes)?,
                    None => JByteArray::null(),
                };
                let answer = env
                    .call_method(
                        host,
                        jni_str!("request"),
                        jni_sig!("(Ljava/lang/String;Ljava/lang/String;[B)Ljava/lang/String;"),
                        &[(&operation).into(), (&arguments).into(), (&payload).into()],
                    )?
                    .l()?;
                if answer.is_null() {
                    return Ok(None);
                }
                let answer = env.cast_local::<JString>(answer)?;
                Ok(Some(answer.try_to_string(env)?))
            });
        // An exception the app threw must not stay pending: the next JNI call of this worker would fail on it.
        if self.env.exception_check() {
            self.env.exception_clear();
        }
        let failed = |message: &str| HostError {
            code: "HOST_FAILED".to_owned(),
            message: message.to_owned(),
        };
        let answer = answer
            .map_err(|_| failed("The app did not answer"))?
            .ok_or_else(|| failed("The app gave no answer"))?;
        let answer: Value =
            serde_json::from_str(&answer).map_err(|_| failed("The app's answer does not read"))?;
        if answer.get("ok").and_then(Value::as_bool) == Some(true) {
            return Ok(answer);
        }
        let member = |key: &str| answer.get(key).and_then(Value::as_str).map(str::to_owned);
        Err(HostError {
            code: member("code").unwrap_or_else(|| "HOST_FAILED".to_owned()),
            message: member("message").unwrap_or_default(),
        })
    }
}

fn unreadable(what: &str) -> HostError {
    HostError {
        code: "HOST_FAILED".to_owned(),
        message: format!("The app's {what} does not read"),
    }
}

impl Host for JniHost<'_, '_, '_> {
    fn account(&mut self) -> Result<String, HostError> {
        let answer = self.request("account", json!({}), None)?;
        answer
            .get("account")
            .and_then(Value::as_str)
            .map(str::to_owned)
            .ok_or_else(|| unreadable("account"))
    }

    fn contacts(&mut self) -> Result<Vec<Contact>, HostError> {
        let answer = self.request("contacts", json!({}), None)?;
        let entries = answer
            .get("contacts")
            .and_then(Value::as_array)
            .ok_or_else(|| unreadable("contact list"))?;
        Ok(entries.iter().filter_map(contact).collect())
    }

    fn send_text(&mut self, to: &Contact, text: &str) -> Result<Sent, HostError> {
        let answer = self.request(
            "sendText",
            json!({ "type": to.kind.name(), "conversationId": to.conversation, "uid": to.uid, "text": text }),
            None,
        )?;
        sent(&answer)
    }

    fn send_image(
        &mut self,
        to: &Contact,
        image: &Image,
        original: bool,
    ) -> Result<Sent, HostError> {
        let answer = self.request(
            "sendImage",
            json!({
                "type": to.kind.name(), "conversationId": to.conversation, "uid": to.uid,
                "extension": image.format.extension(), "width": image.width, "height": image.height,
                "original": original,
            }),
            Some(&image.bytes),
        )?;
        sent(&answer)
    }

    fn send_protocol(
        &mut self,
        cmd: i32,
        inbox_type: i32,
        body: &[u8],
    ) -> Result<Vec<u8>, HostError> {
        let answer = self.request(
            "sendProtocol",
            json!({ "cmd": cmd, "inboxType": inbox_type }),
            Some(body),
        )?;
        answer
            .get("response")
            .and_then(Value::as_str)
            .and_then(base64::decode)
            .ok_or_else(|| unreadable("response"))
    }

    fn claim(
        &mut self,
        reward: Reward,
        conversations: &[String],
        skip: usize,
    ) -> Result<Claimed, HostError> {
        let answer = self.request(
            "claim",
            json!({ "reward": reward.name(), "conversations": conversations, "skip": skip }),
            None,
        )?;
        let count = |key: &str| {
            answer
                .get(key)
                .and_then(Value::as_u64)
                .ok_or_else(|| unreadable("claim result"))
        };
        Ok(Claimed {
            targets: count("targets")?,
            reached: count("reached")?,
            scanned: count("scanned")?,
            claimed: count("claimed")?,
            already: count("already")?,
            failed: count("failed")?,
            unknown: count("unknown")?,
            unavailable: count("unavailable")?,
            details: answer
                .get("details")
                .and_then(Value::as_array)
                .into_iter()
                .flatten()
                .filter_map(Value::as_str)
                .map(str::to_owned)
                .collect(),
        })
    }

    fn mark_all_read(&mut self) -> Result<(), HostError> {
        self.request("markAllRead", json!({}), None).map(|_| ())
    }
}

fn sent(answer: &Value) -> Result<Sent, HostError> {
    let status = match answer.get("status").and_then(Value::as_str) {
        Some("sent") => SendStatus::Sent,
        Some("failed") => SendStatus::Failed,
        Some("unknown") => SendStatus::Unknown,
        _ => return Err(unreadable("send result")),
    };
    let member = |key: &str| answer.get(key).and_then(Value::as_str).map(str::to_owned);
    Ok(Sent {
        status,
        detail: member("detail").unwrap_or_default(),
        message_id: member("messageId"),
    })
}

// A friend without a user ID cannot be sent to, so it is no contact at all.
fn contact(value: &Value) -> Option<Contact> {
    let member = |key: &str| {
        value
            .get(key)
            .and_then(Value::as_str)
            .filter(|text| !text.is_empty())
    };
    let kind = match member("type")? {
        "friend" => ContactKind::Friend,
        "group" => ContactKind::Group,
        _ => return None,
    };
    let uid = member("uid").map(str::to_owned);
    if kind == ContactKind::Friend && uid.is_none() {
        return None;
    }
    let conversation = member("conversationId")?.to_owned();
    Some(Contact {
        name: member("name").unwrap_or(&conversation).to_owned(),
        conversation,
        uid,
        kind,
    })
}
