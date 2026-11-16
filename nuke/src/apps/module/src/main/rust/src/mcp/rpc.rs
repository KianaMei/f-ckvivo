use super::token::Token;
use super::tools::{self, Audit, Host, Reply};
use serde_json::{json, Map, Value};

pub(super) const PARSE_ERROR: i64 = -32700;
pub(super) const INVALID_REQUEST: i64 = -32600;
pub(super) const METHOD_NOT_FOUND: i64 = -32601;
pub(super) const INVALID_PARAMS: i64 = -32602;
pub(super) const UNAUTHORIZED: i64 = -32001;
pub(super) const FORBIDDEN: i64 = -32003;
pub(super) const ACCOUNT_MISMATCH: i64 = -32004;

// Newest first: a client that asks for a revision the server does not know is answered with the newest one.
const PROTOCOL_VERSIONS: [&str; 3] = ["2025-06-18", "2025-03-26", "2024-11-05"];
const SERVER_NAME: &str = "nuke-douyin";
const INSTRUCTIONS: &str = "Tools act as the Douyin account signed in on the user's phone. Find the recipient with \
    douyin_find_contact before douyin_send_text or douyin_send_image, and confirm with the user when several contacts \
    match. A send whose status is unknown may have been delivered: do not repeat it without asking the user. \
    douyin_claim_sparks and douyin_claim_closeness collect rewards the account already earned and never repeat a \
    submission; call one again only with the next it returned. douyin_mark_all_read clears every unread chat at once: \
    use it only when the user asked for exactly that. \
    douyin_send_protocol sends a raw IM request unchecked: use it only for a request the user spelled out.";

pub(super) fn supports(version: &str) -> bool {
    PROTOCOL_VERSIONS.contains(&version)
}

pub(super) fn error(id: Value, code: i64, message: &str, stable: Option<&str>) -> Value {
    let mut error = json!({ "code": code, "message": message });
    if let Some(stable) = stable {
        error["data"] = json!({ "code": stable });
    }
    json!({ "jsonrpc": "2.0", "id": id, "error": error })
}

fn result(id: Value, result: Value) -> Value {
    json!({ "jsonrpc": "2.0", "id": id, "result": result })
}

/// What one JSON-RPC message led to: the response, none for a notification, and the activity line of a tool call.
pub(super) struct Handled {
    pub response: Option<Value>,
    pub audit: Option<Audit>,
}

impl From<Value> for Handled {
    fn from(response: Value) -> Self {
        Handled {
            response: Some(response),
            audit: None,
        }
    }
}

const IGNORED: Handled = Handled {
    response: None,
    audit: None,
};

/// Answers one message of an authenticated request. Batches went away with the 2025-06-18 revision and are refused.
pub(super) fn handle(
    message: &Value,
    token: &Token,
    current: &dyn Fn() -> Option<Token>,
    host: &mut dyn Host,
) -> Handled {
    let Value::Object(message) = message else {
        let reason = if message.is_array() {
            "Batches are not supported"
        } else {
            "A JSON-RPC message must be an object"
        };
        return error(Value::Null, INVALID_REQUEST, reason, None).into();
    };
    // The server sends no requests, so a response from the client answers nothing and is dropped.
    if !message.contains_key("method")
        && (message.contains_key("result") || message.contains_key("error"))
    {
        return IGNORED;
    }
    let id = match message.get("id") {
        None => None,
        Some(id) if id.is_string() || id.is_number() => Some(id.clone()),
        Some(_) => {
            return error(
                Value::Null,
                INVALID_REQUEST,
                "id must be a string or a number",
                None,
            )
            .into()
        }
    };
    let invalid = |reason: &str| -> Handled {
        error(
            id.clone().unwrap_or(Value::Null),
            INVALID_REQUEST,
            reason,
            None,
        )
        .into()
    };
    if message.get("jsonrpc").and_then(Value::as_str) != Some("2.0") {
        return invalid("jsonrpc must be 2.0");
    }
    let Some(method) = message.get("method").and_then(Value::as_str) else {
        return invalid("method must be a string");
    };
    let params = match message.get("params") {
        None => None,
        Some(Value::Object(params)) => Some(params),
        Some(_) => return invalid("params must be an object"),
    };
    // A notification is never answered, whatever it names.
    let Some(id) = id else {
        return IGNORED;
    };
    match method {
        "initialize" => result(id, initialize(params)).into(),
        "ping" => result(id, json!({})).into(),
        "tools/list" => result(id, json!({ "tools": tools::definitions(token) })).into(),
        "tools/call" => {
            let called = tools::call(params, token, current, host);
            let response = match called.reply {
                Reply::Result { body, failed } => result(
                    id,
                    json!({ "content": [{ "type": "text", "text": body.to_string() }], "isError": failed }),
                ),
                Reply::Error {
                    code,
                    message,
                    stable,
                } => error(id, code, message, stable),
            };
            Handled {
                response: Some(response),
                audit: called.audit,
            }
        }
        _ => error(id, METHOD_NOT_FOUND, "Method not found", None).into(),
    }
}

fn initialize(params: Option<&Map<String, Value>>) -> Value {
    let requested = params
        .and_then(|params| params.get("protocolVersion"))
        .and_then(Value::as_str);
    let version = requested
        .filter(|version| supports(version))
        .unwrap_or(PROTOCOL_VERSIONS[0]);
    json!({
        "protocolVersion": version,
        "capabilities": { "tools": { "listChanged": false } },
        "serverInfo": { "name": SERVER_NAME, "version": env!("CARGO_PKG_VERSION") },
        "instructions": INSTRUCTIONS,
    })
}
