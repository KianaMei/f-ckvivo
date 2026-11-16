use super::base64;
use super::image::{Image, ImageError, MAX_IMAGE_BYTES, MAX_IMAGE_CHARS};
use super::rpc;
use super::token::{Permission, Token};
use crate::protocol::{decode, validate_request, wrap_payload, Value as Wire, MAX_BODY_BYTES};
use serde_json::{json, Map, Value};

const MAX_QUERY_CHARS: usize = 64;
const MAX_CONVERSATION_CHARS: usize = 256;
const MAX_TEXT_CHARS: usize = 5000;
const MAX_FOUND: u64 = 20;
const DEFAULT_FOUND: u64 = 10;
const MAX_LISTED: u64 = 200;
const DEFAULT_LISTED: u64 = 50;
const MAX_OFFSET: u64 = 100_000;
const MAX_HOST_MESSAGE_CHARS: usize = 300;
const MAX_CLAIM_DETAILS: usize = 3;
const MAX_BODY_CHARS: usize = MAX_BODY_BYTES.div_ceil(3) * 4;
const MAX_TAG: u64 = 0x1fff_ffff;
/// The largest response to a raw request that is handed back to the client.
pub const MAX_RESPONSE_BYTES: usize = 1024 * 1024;
// Where the response envelope carries its status and its error text.
const STATUS_FIELD: u32 = 3;
const ERROR_FIELD: u32 = 4;
const IMAGE_TOO_LARGE: &str = "image must be at most 5 MB";
const _: () = assert!(MAX_IMAGE_BYTES == 5 * 1024 * 1024);

/// What the server needs from the app it runs in. Every call blocks the worker that serves the request, and none of
/// them is retried.
pub trait Host {
    /// The user ID of the signed-in account.
    fn account(&mut self) -> Result<String, HostError>;

    /// The friends and groups of the signed-in account.
    fn contacts(&mut self) -> Result<Vec<Contact>, HostError>;

    /// Sends text to one of the contacts `contacts` returned and waits for the app's answer.
    fn send_text(&mut self, to: &Contact, text: &str) -> Result<Sent, HostError>;

    /// Uploads a picture and sends it to one of the contacts `contacts` returned, then waits for the app's answer.
    /// `original` asks the app to keep the file as it is instead of compressing it.
    fn send_image(
        &mut self,
        to: &Contact,
        image: &Image,
        original: bool,
    ) -> Result<Sent, HostError>;

    /// Sends one raw IM request, a whole RequestBody, through the app's own IM connection and waits for the response.
    /// A failure by time does not mean nothing was sent.
    fn send_protocol(
        &mut self,
        cmd: i32,
        inbox_type: i32,
        body: &[u8],
    ) -> Result<Vec<u8>, HostError>;

    /// Claims the rewards of one kind that the account has earned in the private chats among `conversations`, a chat
    /// at a time and in an order that stays the same between calls, leaving out the first `skip` of the chats that
    /// may hold one. It may stop before the last chat, and never submits a reward twice.
    fn claim(
        &mut self,
        reward: Reward,
        conversations: &[String],
        skip: usize,
    ) -> Result<Claimed, HostError>;

    /// Asks the app to mark every chat of the account read. The app only submits this; nothing confirms it.
    fn mark_all_read(&mut self) -> Result<(), HostError>;
}

/// Why the app could not do what was asked. The code is stable and reaches the client; the message is for people.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct HostError {
    pub code: String,
    pub message: String,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum ContactKind {
    Friend,
    Group,
}

impl ContactKind {
    pub fn name(self) -> &'static str {
        match self {
            ContactKind::Friend => "friend",
            ContactKind::Group => "group",
        }
    }
}

/// A chat a message can go to. `conversation` identifies it everywhere: in a token's allowed chats, in tool arguments
/// and in results. A friend also has the user ID the app sends by.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Contact {
    pub conversation: String,
    pub uid: Option<String>,
    pub name: String,
    pub kind: ContactKind,
}

/// The app's answer to one send. Only `Sent` is the server's acknowledgement; `Unknown` may or may not have gone out.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum SendStatus {
    Sent,
    Failed,
    Unknown,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Sent {
    pub status: SendStatus,
    pub detail: String,
    pub message_id: Option<String>,
}

/// A reward a streak with a friend earns, which the account has to collect itself.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Reward {
    Sparks,
    Closeness,
}

impl Reward {
    pub fn name(self) -> &'static str {
        match self {
            Reward::Sparks => "sparks",
            Reward::Closeness => "closeness",
        }
    }
}

/// What one run of claims came to. `targets` is how many chats may hold a reward and `reached` how many of them,
/// counted from the first and with the skipped ones, the run got through. `scanned` chats were read; a reward was
/// `claimed`, `already` claimed or `failed`, or its submission went out without an answer (`unknown`); `unavailable`
/// counts the reads that failed. `details` are the first reasons, for people.
#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct Claimed {
    pub targets: u64,
    pub reached: u64,
    pub scanned: u64,
    pub claimed: u64,
    pub already: u64,
    pub failed: u64,
    pub unknown: u64,
    pub unavailable: u64,
    pub details: Vec<String>,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
enum Tool {
    FindContact,
    ListContacts,
    SendText,
    SendImage,
    ClaimSparks,
    ClaimCloseness,
    MarkAllRead,
    SendProtocol,
}

impl Tool {
    const ALL: [Tool; 8] = [
        Tool::FindContact,
        Tool::ListContacts,
        Tool::SendText,
        Tool::SendImage,
        Tool::ClaimSparks,
        Tool::ClaimCloseness,
        Tool::MarkAllRead,
        Tool::SendProtocol,
    ];

    fn name(self) -> &'static str {
        match self {
            Tool::FindContact => "douyin_find_contact",
            Tool::ListContacts => "douyin_list_contacts",
            Tool::SendText => "douyin_send_text",
            Tool::SendImage => "douyin_send_image",
            Tool::ClaimSparks => "douyin_claim_sparks",
            Tool::ClaimCloseness => "douyin_claim_closeness",
            Tool::MarkAllRead => "douyin_mark_all_read",
            Tool::SendProtocol => "douyin_send_protocol",
        }
    }

    fn permission(self) -> Permission {
        match self {
            Tool::FindContact | Tool::ListContacts => Permission::ReadContacts,
            Tool::SendText => Permission::SendText,
            Tool::SendImage => Permission::SendImage,
            Tool::ClaimSparks | Tool::ClaimCloseness => Permission::ClaimRewards,
            Tool::MarkAllRead => Permission::MarkRead,
            Tool::SendProtocol => Permission::SendProtocol,
        }
    }

    fn definition(self) -> Value {
        let (description, properties, required, read_only) = match self {
            Tool::FindContact => (
                "Find Douyin friends and group chats by name. Use this first when you only know who a message is for, \
                 then pass the conversationId of the match to douyin_send_text or douyin_send_image.",
                json!({
                    "name": { "type": "string", "minLength": 1, "maxLength": MAX_QUERY_CHARS,
                        "description": "All or part of a friend's remark or nickname, or of a group's name." },
                    "limit": { "type": "integer", "minimum": 1, "maximum": MAX_FOUND, "default": DEFAULT_FOUND },
                }),
                json!(["name"]),
                true,
            ),
            Tool::ListContacts => (
                "List the Douyin friends and group chats this token may reach. Only for browsing or when the user asks \
                 for the list; to find one recipient use douyin_find_contact.",
                json!({
                    "type": { "type": "string", "enum": ["friend", "group"] },
                    "limit": { "type": "integer", "minimum": 1, "maximum": MAX_LISTED, "default": DEFAULT_LISTED },
                    "offset": { "type": "integer", "minimum": 0, "maximum": MAX_OFFSET, "default": 0 },
                }),
                json!([]),
                true,
            ),
            Tool::SendText => (
                "Send a text message from the signed-in Douyin account to a friend or a group chat. status is sent only \
                 once Douyin's server acknowledged the message. unknown means no answer came in time and the message \
                 may still have gone out: never send it again without asking the user.",
                json!({
                    "conversationId": { "type": "string", "minLength": 1, "maxLength": MAX_CONVERSATION_CHARS,
                        "description": "The conversationId of a contact returned by douyin_find_contact or douyin_list_contacts." },
                    "text": { "type": "string", "minLength": 1, "maxLength": MAX_TEXT_CHARS },
                }),
                json!(["conversationId", "text"]),
                false,
            ),
            Tool::SendImage => (
                "Send one picture from the signed-in Douyin account to a friend or a group chat. The picture is a whole \
                 JPEG or PNG file of at most 5 MB as standard base64. status reads as for douyin_send_text: unknown \
                 means the picture may still have gone out, so never send it again without asking the user.",
                json!({
                    "conversationId": { "type": "string", "minLength": 1, "maxLength": MAX_CONVERSATION_CHARS,
                        "description": "The conversationId of a contact returned by douyin_find_contact or douyin_list_contacts." },
                    "image": { "type": "string", "minLength": 1, "maxLength": MAX_IMAGE_CHARS, "contentEncoding": "base64",
                        "description": "The bytes of the JPEG or PNG file in standard base64, without a data: prefix." },
                    "original": { "type": "boolean", "default": false,
                        "description": "Send the file as it is instead of letting Douyin compress it." },
                }),
                json!(["conversationId", "image"]),
                false,
            ),
            Tool::ClaimSparks => (
                "Claim the spark rewards the signed-in Douyin account has earned from its streaks, for one friend or \
                 for every friend this token reaches. Only friends with a running streak whose chat Douyin has loaded \
                 are looked at. The result counts the chats read and the rewards claimed; unknown counts submissions \
                 that went out without an answer and are never repeated. A run stops after a while: when the result \
                 has next, call again with skip set to it to go on.",
                claim_properties(),
                json!([]),
                false,
            ),
            Tool::ClaimCloseness => (
                "Claim the pet closeness the signed-in Douyin account has earned with its friends, for one friend or \
                 for every friend this token reaches. Only friends with a running streak are looked at. The result \
                 reads as for douyin_claim_sparks, and a run also stops at a submission whose outcome is unknown: \
                 tell the user instead of going on.",
                claim_properties(),
                json!([]),
                false,
            ),
            Tool::MarkAllRead => (
                "Mark every chat of the signed-in Douyin account read, the stranger inbox included, as Douyin's own \
                 mark-all step does. There is no single-chat form and no undo. status is submitted: Douyin does not \
                 report whether its server took it, and the module's read-status blocking still applies.",
                json!({}),
                json!([]),
                false,
            ),
            Tool::SendProtocol => (
                "Send one raw Douyin IM request as the signed-in account and return the server's response. For protocol \
                 work only: the request can do whatever its command does, to any chat, and is not checked. Never \
                 build one unless the user gave the command and the body, and never repeat one that failed with \
                 TIMEOUT without asking, because it may still have been sent.",
                json!({
                    "cmd": { "type": "integer", "minimum": 1, "maximum": i32::MAX,
                        "description": "The IM command number." },
                    "inboxType": { "type": "integer", "minimum": 0, "maximum": i32::MAX, "default": 1 },
                    "body": { "type": "string", "maxLength": MAX_BODY_CHARS, "contentEncoding": "base64",
                        "description": "Standard base64 of the whole RequestBody protobuf, or with bodyTag of the business message alone. At most 4 MiB decoded." },
                    "bodyTag": { "type": "integer", "minimum": 1, "maximum": MAX_TAG,
                        "description": "When given, body is wrapped as the field of this number in a new RequestBody." },
                }),
                json!(["cmd", "body"]),
                false,
            ),
        };
        json!({
            "name": self.name(),
            "description": description,
            "inputSchema": {
                "type": "object",
                "properties": properties,
                "required": required,
                "additionalProperties": false,
            },
            "annotations": {
                "readOnlyHint": read_only,
                "destructiveHint": self == Tool::SendProtocol,
                "idempotentHint": read_only,
                "openWorldHint": !read_only,
            },
        })
    }
}

/// The tools a token may call; the rest are not even listed.
pub(super) fn definitions(token: &Token) -> Value {
    Tool::ALL
        .into_iter()
        .filter(|tool| token.allows(tool.permission()))
        .map(Tool::definition)
        .collect()
}

/// How one `tools/call` ended: a tool result, which may itself report a failure, or a protocol error.
#[derive(Debug, PartialEq)]
pub(super) enum Reply {
    Result {
        body: Value,
        failed: bool,
    },
    Error {
        code: i64,
        message: &'static str,
        stable: Option<&'static str>,
    },
}

impl Reply {
    fn invalid(message: &'static str) -> Reply {
        Reply::Error {
            code: rpc::INVALID_PARAMS,
            message,
            stable: None,
        }
    }

    fn failed(code: &str, message: &str) -> Reply {
        let message: String = message.chars().take(MAX_HOST_MESSAGE_CHARS).collect();
        Reply::Result {
            body: json!({ "errorCode": code, "message": message }),
            failed: true,
        }
    }

    // What the activity record says about this reply.
    fn code(&self) -> String {
        match self {
            Reply::Result { body, .. } => body
                .get("errorCode")
                .and_then(Value::as_str)
                .unwrap_or("OK")
                .to_owned(),
            Reply::Error { stable, .. } => stable.unwrap_or("INVALID_PARAMS").to_owned(),
        }
    }
}

const DENIED: Reply = Reply::Error {
    code: rpc::FORBIDDEN,
    message: "Permission denied",
    stable: Some("PERMISSION_DENIED"),
};
const REVOKED: Reply = Reply::Error {
    code: rpc::UNAUTHORIZED,
    message: "Access token is no longer valid",
    stable: Some("TOKEN_REVOKED"),
};

/// One line of the activity record: which tool, for which chat, and how it ended. Never the text of a message.
#[derive(Debug, PartialEq, Eq)]
pub(super) struct Audit {
    pub tool: &'static str,
    pub target: Option<String>,
    pub ok: bool,
    pub code: String,
}

pub(super) struct Called {
    pub reply: Reply,
    // Absent when the call named no tool this server has.
    pub audit: Option<Audit>,
}

/// Runs one `tools/call`. `token` is what the request authenticated with; `current` reads that token again, so what a
/// call does after waiting for the app follows the configuration as it is then.
pub(super) fn call(
    params: Option<&Map<String, Value>>,
    token: &Token,
    current: &dyn Fn() -> Option<Token>,
    host: &mut dyn Host,
) -> Called {
    let unknown = |message| Called {
        reply: Reply::invalid(message),
        audit: None,
    };
    let Some(params) = params else {
        return unknown("Missing tool parameters");
    };
    if !only(params, &["name", "arguments", "_meta"]) {
        return unknown("Unknown tool parameter");
    }
    let Some(name) = params.get("name").and_then(Value::as_str) else {
        return unknown("Tool name must be a string");
    };
    let none = Map::new();
    let arguments = match params.get("arguments") {
        None => &none,
        Some(Value::Object(arguments)) => arguments,
        Some(_) => return unknown("Tool arguments must be an object"),
    };
    let Some(tool) = Tool::ALL.into_iter().find(|tool| tool.name() == name) else {
        return unknown("Unknown tool");
    };
    let mut run = Run {
        token,
        current,
        host,
        target: None,
    };
    let reply = match tool {
        _ if !token.allows(tool.permission()) => Err(DENIED),
        Tool::FindContact => run.find_contact(arguments),
        Tool::ListContacts => run.list_contacts(arguments),
        Tool::SendText => run.send_text(arguments),
        Tool::SendImage => run.send_image(arguments),
        Tool::ClaimSparks => run.claim(arguments, Reward::Sparks),
        Tool::ClaimCloseness => run.claim(arguments, Reward::Closeness),
        Tool::MarkAllRead => run.mark_all_read(arguments),
        Tool::SendProtocol => run.send_protocol(arguments),
    }
    .unwrap_or_else(|reply| reply);
    Called {
        audit: Some(Audit {
            tool: tool.name(),
            target: run.target,
            ok: matches!(reply, Reply::Result { failed: false, .. }),
            code: reply.code(),
        }),
        reply,
    }
}

struct Run<'a> {
    token: &'a Token,
    current: &'a dyn Fn() -> Option<Token>,
    host: &'a mut dyn Host,
    target: Option<String>,
}

// Both sides are the reply; Err only lets a step end the call early.
type Step<T = Reply> = Result<T, Reply>;

impl Run<'_> {
    fn find_contact(&mut self, arguments: &Map<String, Value>) -> Step {
        if !only(arguments, &["name", "limit"]) {
            return Err(Reply::invalid("Unknown argument"));
        }
        let name = arguments
            .get("name")
            .and_then(Value::as_str)
            .map(str::trim)
            .filter(|name| !name.is_empty() && name.chars().count() <= MAX_QUERY_CHARS)
            .ok_or(Reply::invalid("name must be 1 to 64 characters"))?;
        let limit = integer(arguments, "limit", DEFAULT_FOUND, 1, MAX_FOUND)
            .ok_or(Reply::invalid("limit must be an integer from 1 to 20"))?;
        let needle = name.to_lowercase();
        // An exact name comes first, then a name that starts with the query, then one that contains it.
        let mut found: Vec<(u8, Contact)> = self
            .visible_contacts()?
            .into_iter()
            .filter_map(|contact| {
                let known = contact.name.to_lowercase();
                let rank = if known == needle || contact.conversation == name {
                    0
                } else if known.starts_with(&needle) {
                    1
                } else if known.contains(&needle) {
                    2
                } else {
                    return None;
                };
                Some((rank, contact))
            })
            .collect();
        found.sort_by_key(|(rank, _)| *rank);
        let contacts: Vec<Value> = found
            .iter()
            .take(limit)
            .map(|(_, contact)| describe(contact))
            .collect();
        Ok(Reply::Result {
            body: json!({ "contacts": contacts, "matches": found.len() }),
            failed: false,
        })
    }

    fn list_contacts(&mut self, arguments: &Map<String, Value>) -> Step {
        if !only(arguments, &["type", "limit", "offset"]) {
            return Err(Reply::invalid("Unknown argument"));
        }
        let kind = match arguments.get("type").map(Value::as_str) {
            None => None,
            Some(Some("friend")) => Some(ContactKind::Friend),
            Some(Some("group")) => Some(ContactKind::Group),
            Some(_) => return Err(Reply::invalid("type must be friend or group")),
        };
        let limit = integer(arguments, "limit", DEFAULT_LISTED, 1, MAX_LISTED)
            .ok_or(Reply::invalid("limit must be an integer from 1 to 200"))?;
        let offset = integer(arguments, "offset", 0, 0, MAX_OFFSET)
            .ok_or(Reply::invalid("offset must be an integer from 0 to 100000"))?;
        let listed: Vec<Contact> = self
            .visible_contacts()?
            .into_iter()
            .filter(|contact| kind.is_none_or(|kind| contact.kind == kind))
            .collect();
        let contacts: Vec<Value> = listed
            .iter()
            .skip(offset)
            .take(limit)
            .map(describe)
            .collect();
        Ok(Reply::Result {
            body: json!({ "contacts": contacts, "total": listed.len(), "offset": offset }),
            failed: false,
        })
    }

    fn send_text(&mut self, arguments: &Map<String, Value>) -> Step {
        if !only(arguments, &["conversationId", "text"]) {
            return Err(Reply::invalid("Unknown argument"));
        }
        let conversation = conversation(arguments)?;
        let text = arguments
            .get("text")
            .and_then(Value::as_str)
            .filter(|text| {
                !text.trim().is_empty()
                    && text.chars().count() <= MAX_TEXT_CHARS
                    && !text
                        .chars()
                        .any(|c| c.is_control() && !matches!(c, '\n' | '\r' | '\t'))
            })
            .ok_or(Reply::invalid("text must be 1 to 5000 characters"))?;
        self.deliver(conversation, Permission::SendText, |host, contact| {
            host.send_text(contact, text)
        })
    }

    fn send_image(&mut self, arguments: &Map<String, Value>) -> Step {
        if !only(arguments, &["conversationId", "image", "original"]) {
            return Err(Reply::invalid("Unknown argument"));
        }
        let conversation = conversation(arguments)?;
        let original = match arguments.get("original") {
            None => false,
            Some(Value::Bool(original)) => *original,
            Some(_) => return Err(Reply::invalid("original must be true or false")),
        };
        let image = arguments
            .get("image")
            .and_then(Value::as_str)
            .ok_or(Reply::invalid("image must be base64 text"))
            .and_then(|text| {
                Image::decode(text).map_err(|error| {
                    Reply::invalid(match error {
                        ImageError::Encoding => "image must be standard base64",
                        ImageError::TooLarge => IMAGE_TOO_LARGE,
                        ImageError::Unsupported => "image must be a JPEG or PNG file",
                    })
                })
            })?;
        self.deliver(conversation, Permission::SendImage, |host, contact| {
            host.send_image(contact, &image, original)
        })
    }

    fn send_protocol(&mut self, arguments: &Map<String, Value>) -> Step {
        if !only(arguments, &["cmd", "inboxType", "body", "bodyTag"]) {
            return Err(Reply::invalid("Unknown argument"));
        }
        let number = |key, default, min| {
            integer(arguments, key, default, min, i32::MAX as u64)
                .and_then(|value| i32::try_from(value).ok())
        };
        let cmd = number("cmd", 0, 1).ok_or(Reply::invalid("cmd must be a positive integer"))?;
        let inbox_type = number("inboxType", 1, 0)
            .ok_or(Reply::invalid("inboxType must be a nonnegative integer"))?;
        let tag = match arguments.get("bodyTag") {
            None => None,
            Some(_) => Some(
                integer(arguments, "bodyTag", 0, 1, MAX_TAG)
                    .and_then(|tag| i32::try_from(tag).ok())
                    .ok_or(Reply::invalid(
                        "bodyTag must be an integer from 1 to 536870911",
                    ))?,
            ),
        };
        let too_large = || Reply::invalid("The request body must be at most 4 MiB");
        let text = arguments
            .get("body")
            .and_then(Value::as_str)
            .ok_or(Reply::invalid("body must be base64 text"))?;
        if text.len() > MAX_BODY_CHARS {
            return Err(too_large());
        }
        let payload = base64::decode(text).ok_or(Reply::invalid("body must be standard base64"))?;
        let body = match tag {
            Some(tag) => wrap_payload(tag, &payload).map_err(|_| too_large())?,
            None => payload,
        };
        validate_request(cmd, inbox_type, body.len()).map_err(|_| too_large())?;
        self.target = Some(format!("cmd {cmd}"));
        // The body is not read, so no chat can be told from it: only a token that reaches every chat may send one.
        let unbounded = |token: &Token| {
            every_chat(
                token,
                "A raw request can address any chat, so only a token that reaches all chats may send one",
            )
        };
        unbounded(self.token)?;
        self.signed_in()?;
        let token = (self.current)().ok_or(REVOKED)?;
        if !token.allows(Permission::SendProtocol) {
            return Err(DENIED);
        }
        unbounded(&token)?;
        let response = self
            .host
            .send_protocol(cmd, inbox_type, &body)
            .map_err(|error| Reply::failed(&error.code, &error.message))?;
        if response.len() > MAX_RESPONSE_BYTES {
            return Err(Reply::failed(
                "RESPONSE_TOO_LARGE",
                &format!(
                    "The request was sent, but its response of {} bytes is too large to return",
                    response.len()
                ),
            ));
        }
        let mut answer = json!({
            "cmd": cmd, "inboxType": inbox_type,
            "response": base64::encode(&response), "responseBytes": response.len(),
        });
        // The envelope's own verdict, when it reads; what it means is the command's business.
        for field in decode(&response).unwrap_or_default() {
            match (field.number, field.value) {
                (STATUS_FIELD, Wire::Varint(status)) if answer.get("statusCode").is_none() => {
                    answer["statusCode"] = status.into();
                }
                (ERROR_FIELD, Wire::Bytes(text)) if answer.get("error").is_none() => {
                    let text: String = String::from_utf8_lossy(&text)
                        .chars()
                        .take(MAX_HOST_MESSAGE_CHARS)
                        .collect();
                    answer["error"] = text.into();
                }
                _ => {}
            }
        }
        Ok(Reply::Result {
            body: answer,
            failed: false,
        })
    }

    fn claim(&mut self, arguments: &Map<String, Value>, reward: Reward) -> Step {
        if !only(arguments, &["conversationId", "skip"]) {
            return Err(Reply::invalid("Unknown argument"));
        }
        let one = match arguments.get("conversationId") {
            None => None,
            Some(_) => Some(conversation(arguments)?),
        };
        let skip = integer(arguments, "skip", 0, 0, MAX_OFFSET)
            .ok_or(Reply::invalid("skip must be an integer from 0 to 100000"))?;
        self.target = one.map(str::to_owned);
        self.signed_in()?;
        let not_allowed = || {
            Reply::failed(
                "CONVERSATION_NOT_ALLOWED",
                "This token does not reach that chat",
            )
        };
        if one.is_some_and(|one| !self.token.reaches(one)) {
            return Err(not_allowed());
        }
        let contacts = self.contacts()?;
        let token = (self.current)().ok_or(REVOKED)?;
        if !token.allows(Permission::ClaimRewards) {
            return Err(DENIED);
        }
        if one.is_some_and(|one| !token.reaches(one)) {
            return Err(not_allowed());
        }
        // Only the private chats the account really has and the token reaches are handed to the app, which claims
        // nowhere else.
        let chats: Vec<String> = contacts
            .into_iter()
            .filter(|contact| contact.kind == ContactKind::Friend)
            .map(|contact| contact.conversation)
            .filter(|chat| token.reaches(chat) && one.is_none_or(|one| one == chat))
            .collect();
        if one.is_some() && chats.is_empty() {
            return Err(Reply::failed(
                "CONTACT_NOT_FOUND",
                "No friend has that conversationId",
            ));
        }
        let claimed = self
            .host
            .claim(reward, &chats, skip)
            .map_err(|error| Reply::failed(&error.code, &error.message))?;
        let details: Vec<String> = claimed
            .details
            .iter()
            .take(MAX_CLAIM_DETAILS)
            .map(|detail| detail.chars().take(MAX_HOST_MESSAGE_CHARS).collect())
            .collect();
        let mut body = json!({
            "targets": claimed.targets, "scanned": claimed.scanned, "claimed": claimed.claimed,
            "already": claimed.already, "failed": claimed.failed, "unknown": claimed.unknown,
            "unavailable": claimed.unavailable, "details": details,
        });
        // Where a run that stopped early goes on.
        if claimed.reached < claimed.targets {
            body["next"] = claimed.reached.into();
        }
        Ok(Reply::Result {
            body,
            failed: false,
        })
    }

    fn mark_all_read(&mut self, arguments: &Map<String, Value>) -> Step {
        if !arguments.is_empty() {
            return Err(Reply::invalid("Unknown argument"));
        }
        // Every chat is marked, so the chats a token names cannot bound it.
        let unbounded = |token: &Token| {
            every_chat(
                token,
                "Every chat is marked read, so only a token that reaches all chats may do it",
            )
        };
        unbounded(self.token)?;
        self.signed_in()?;
        let token = (self.current)().ok_or(REVOKED)?;
        if !token.allows(Permission::MarkRead) {
            return Err(DENIED);
        }
        unbounded(&token)?;
        self.host
            .mark_all_read()
            .map_err(|error| Reply::failed(&error.code, &error.message))?;
        Ok(Reply::Result {
            body: json!({ "status": "submitted" }),
            failed: false,
        })
    }

    // The part every send shares: who may send to whom is settled first, by the token as it is once the app has
    // answered, and only then is the app asked to send.
    fn deliver(
        &mut self,
        conversation: &str,
        permission: Permission,
        send: impl FnOnce(&mut dyn Host, &Contact) -> Result<Sent, HostError>,
    ) -> Step {
        self.target = Some(conversation.to_owned());
        self.signed_in()?;
        let not_allowed = || {
            Reply::failed(
                "CONVERSATION_NOT_ALLOWED",
                "This token may not message that chat",
            )
        };
        if !self.token.reaches(conversation) {
            return Err(not_allowed());
        }
        // Only a chat the account really has is ever handed to the app, never an ID taken on trust.
        let contact = self
            .contacts()?
            .into_iter()
            .find(|contact| contact.conversation == conversation)
            .ok_or_else(|| {
                Reply::failed(
                    "CONTACT_NOT_FOUND",
                    "No friend or group chat has that conversationId",
                )
            })?;
        let token = (self.current)().ok_or(REVOKED)?;
        if !token.allows(permission) {
            return Err(DENIED);
        }
        if !token.reaches(conversation) {
            return Err(not_allowed());
        }
        let sent = send(&mut *self.host, &contact)
            .map_err(|error| Reply::failed(&error.code, &error.message))?;
        let detail: String = sent.detail.chars().take(MAX_HOST_MESSAGE_CHARS).collect();
        let mut body = json!({ "conversationId": conversation, "detail": detail });
        let (status, error) = match sent.status {
            SendStatus::Sent => ("sent", None),
            SendStatus::Failed => ("failed", Some("SEND_FAILED")),
            SendStatus::Unknown => ("unknown", Some("SEND_UNCONFIRMED")),
        };
        body["status"] = status.into();
        if let Some(error) = error {
            body["errorCode"] = error.into();
        }
        if let Some(id) = sent.message_id {
            body["messageId"] = id.into();
        }
        Ok(Reply::Result {
            body,
            failed: error.is_some(),
        })
    }

    // A token acts only as the account it was made for.
    fn signed_in(&mut self) -> Step<()> {
        match self.host.account() {
            Err(error) => Err(Reply::failed(&error.code, &error.message)),
            Ok(account) if account != self.token.account => Err(Reply::Error {
                code: rpc::ACCOUNT_MISMATCH,
                message: "The token belongs to another account",
                stable: Some("ACCOUNT_MISMATCH"),
            }),
            Ok(_) => Ok(()),
        }
    }

    fn contacts(&mut self) -> Step<Vec<Contact>> {
        self.host
            .contacts()
            .map_err(|error| Reply::failed(&error.code, &error.message))
    }

    // The contacts the token may see, by the token as it is once the app has answered.
    fn visible_contacts(&mut self) -> Step<Vec<Contact>> {
        self.signed_in()?;
        let contacts = self.contacts()?;
        let token = (self.current)().ok_or(REVOKED)?;
        if !token.allows(Permission::ReadContacts) {
            return Err(DENIED);
        }
        Ok(contacts
            .into_iter()
            .filter(|contact| token.reaches(&contact.conversation))
            .collect())
    }
}

fn conversation(arguments: &Map<String, Value>) -> Step<&str> {
    arguments
        .get("conversationId")
        .and_then(Value::as_str)
        .filter(|id| {
            !id.is_empty() && id.trim() == *id && id.chars().count() <= MAX_CONVERSATION_CHARS
        })
        .ok_or(Reply::invalid("conversationId must be a conversation ID"))
}

// What a tool that is not bound to one chat asks of a token.
fn every_chat(token: &Token, message: &str) -> Step<()> {
    if token.reaches_all() {
        Ok(())
    } else {
        Err(Reply::failed("ALL_CHATS_REQUIRED", message))
    }
}

fn claim_properties() -> Value {
    json!({
        "conversationId": { "type": "string", "minLength": 1, "maxLength": MAX_CONVERSATION_CHARS,
            "description": "The conversationId of one friend returned by douyin_find_contact or douyin_list_contacts. Without it, every friend this token reaches." },
        "skip": { "type": "integer", "minimum": 0, "maximum": MAX_OFFSET, "default": 0,
            "description": "The next of the run this one goes on from." },
    })
}

fn describe(contact: &Contact) -> Value {
    json!({ "conversationId": contact.conversation, "name": contact.name, "type": contact.kind.name() })
}

fn only(object: &Map<String, Value>, keys: &[&str]) -> bool {
    object.keys().all(|key| keys.contains(&key.as_str()))
}

// An optional whole number within bounds; none when it is present but anything else.
fn integer(
    arguments: &Map<String, Value>,
    key: &str,
    default: u64,
    min: u64,
    max: u64,
) -> Option<usize> {
    let value = match arguments.get(key) {
        None => default,
        Some(value) => value.as_u64()?,
    };
    (min..=max)
        .contains(&value)
        .then(|| usize::try_from(value).ok())?
}
