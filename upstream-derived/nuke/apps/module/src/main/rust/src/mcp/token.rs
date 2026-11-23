use serde_json::Value;
use sha2::{Digest, Sha256};
use std::collections::{HashMap, HashSet, VecDeque};
use std::time::{Duration, Instant};

pub const TOKEN_PREFIX: &str = "nuke_mcp_";
pub const MAX_TOKENS: usize = 64;
pub const MAX_REQUESTS_PER_MINUTE: u32 = 600;
const ID_BYTES: usize = 8;
const SECRET_BYTES: usize = 32;
/// How much randomness `issue` needs: the token ID and the secret.
pub const ENTROPY_BYTES: usize = ID_BYTES + SECRET_BYTES;
const MAX_ID_CHARS: usize = 32;
const MAX_NAME_CHARS: usize = 64;
const MAX_ACCOUNT_CHARS: usize = 64;
const MAX_CONVERSATIONS: usize = 2000;
const MAX_CONVERSATION_CHARS: usize = 256;
const MAX_BEARER_CHARS: usize = 256;
const RATE_WINDOW: Duration = Duration::from_secs(60);

/// What a token may do. The names are the ones the stored configuration uses.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Permission {
    ReadContacts,
    SendText,
    SendImage,
    ClaimRewards,
    MarkRead,
    SendProtocol,
}

impl Permission {
    pub fn name(self) -> &'static str {
        match self {
            Permission::ReadContacts => "READ_CONTACTS",
            Permission::SendText => "SEND_TEXT",
            Permission::SendImage => "SEND_IMAGE",
            Permission::ClaimRewards => "CLAIM_REWARDS",
            Permission::MarkRead => "MARK_READ",
            Permission::SendProtocol => "SEND_PROTOCOL",
        }
    }

    fn parse(name: &str) -> Option<Self> {
        match name {
            "READ_CONTACTS" => Some(Permission::ReadContacts),
            "SEND_TEXT" => Some(Permission::SendText),
            "SEND_IMAGE" => Some(Permission::SendImage),
            "CLAIM_REWARDS" => Some(Permission::ClaimRewards),
            "MARK_READ" => Some(Permission::MarkRead),
            "SEND_PROTOCOL" => Some(Permission::SendProtocol),
            _ => None,
        }
    }
}

/// One access token as stored: never the secret, only its SHA-256. A token belongs to the account that was signed in
/// when it was made and reaches only the chats it names, unless it allows all of them.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Token {
    pub id: String,
    pub name: String,
    secret_hash: [u8; 32],
    pub enabled: bool,
    pub account: String,
    permissions: Vec<Permission>,
    allow_all: bool,
    conversations: HashSet<String>,
    pub limit: u32,
}

impl Token {
    // Anything missing or out of range drops the token: a token the server cannot read in full grants nothing.
    fn parse(value: &Value) -> Option<Token> {
        let id = value.get("id")?.as_str()?;
        if id.len() > MAX_ID_CHARS || !is_token_part(id) {
            return None;
        }
        let name = value.get("name")?.as_str()?.trim();
        if name.is_empty() || name.chars().count() > MAX_NAME_CHARS {
            return None;
        }
        let secret_hash = decode_hash(value.get("secretHash")?.as_str()?)?;
        let account = value.get("account")?.as_str()?.trim();
        if account.is_empty() || account.chars().count() > MAX_ACCOUNT_CHARS {
            return None;
        }
        let limit = u32::try_from(value.get("limit")?.as_u64()?).ok()?;
        if !(1..=MAX_REQUESTS_PER_MINUTE).contains(&limit) {
            return None;
        }
        let strings = |key: &str| {
            value
                .get(key)
                .and_then(Value::as_array)
                .into_iter()
                .flatten()
                .filter_map(Value::as_str)
        };
        Some(Token {
            id: id.to_owned(),
            name: name.to_owned(),
            secret_hash,
            enabled: value.get("enabled").and_then(Value::as_bool) == Some(true),
            account: account.to_owned(),
            permissions: strings("permissions")
                .filter_map(Permission::parse)
                .collect(),
            allow_all: value.get("allowAll").and_then(Value::as_bool) == Some(true),
            conversations: strings("conversations")
                .map(str::trim)
                .filter(|id| !id.is_empty() && id.chars().count() <= MAX_CONVERSATION_CHARS)
                .take(MAX_CONVERSATIONS)
                .map(str::to_owned)
                .collect(),
            limit,
        })
    }

    pub fn allows(&self, permission: Permission) -> bool {
        self.permissions.contains(&permission)
    }

    pub fn reaches(&self, conversation: &str) -> bool {
        self.allow_all || self.conversations.contains(conversation)
    }

    /// Whether no chat is out of reach, which is what a request that names no chat needs.
    pub fn reaches_all(&self) -> bool {
        self.allow_all
    }

    // Whether `other` is this credential as it is now: the same ID with the same secret, still enabled.
    fn same_credential(&self, other: &Token) -> bool {
        self.id == other.id
            && other.enabled
            && constant_time_eq(&self.secret_hash, &other.secret_hash)
    }
}

/// The tokens the server accepts, read from the configuration the settings screen stores.
#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct Config {
    tokens: Vec<Token>,
}

impl Config {
    /// Reads `{"tokens":[...]}`. Never fails: a token that does not read is left out and a document that does not read
    /// has no tokens, so a damaged configuration can only take access away.
    pub fn parse(json: &str) -> Config {
        let mut tokens: Vec<Token> = Vec::new();
        if let Ok(root) = serde_json::from_str::<Value>(json) {
            let entries = root.get("tokens").and_then(Value::as_array);
            for token in entries.into_iter().flatten().filter_map(Token::parse) {
                if tokens.len() == MAX_TOKENS {
                    break;
                }
                if tokens.iter().all(|known| known.id != token.id) {
                    tokens.push(token);
                }
            }
        }
        Config { tokens }
    }

    pub fn len(&self) -> usize {
        self.tokens.len()
    }

    pub fn is_empty(&self) -> bool {
        self.tokens.is_empty()
    }

    /// The token an `Authorization` header value names, when it is `Bearer nuke_mcp_<id>.<secret>` of an enabled token.
    /// The secret is compared in constant time, against a dummy when the ID is unknown.
    pub fn authenticate(&self, authorization: Option<&str>) -> Option<&Token> {
        let (scheme, bearer) = authorization?.split_once(' ')?;
        let bearer = bearer.trim();
        if !scheme.eq_ignore_ascii_case("Bearer") || bearer.len() > MAX_BEARER_CHARS {
            return None;
        }
        let (id, secret) = bearer.strip_prefix(TOKEN_PREFIX)?.split_once('.')?;
        if !is_token_part(id) || !is_token_part(secret) {
            return None;
        }
        let token = self.tokens.iter().find(|token| token.id == id);
        let expected = token.map_or([0u8; 32], |token| token.secret_hash);
        let matches = constant_time_eq(&expected, &hash(secret));
        token.filter(|token| matches && token.enabled)
    }

    /// The current form of a token a request authenticated with a moment ago, or none once it was revoked, disabled or
    /// given a new secret.
    pub fn current(&self, token: &Token) -> Option<&Token> {
        self.tokens
            .iter()
            .find(|current| token.same_credential(current))
    }

    pub(super) fn ids(&self) -> impl Iterator<Item = &str> {
        self.tokens.iter().map(|token| token.id.as_str())
    }
}

/// A new credential: the text handed to the user once, and what the configuration keeps of it.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Issued {
    pub id: String,
    pub secret_hash: String,
    pub token: String,
}

/// Builds a credential from `ENTROPY_BYTES` of the platform's secure randomness. With `id`, the existing token of that
/// ID gets a new secret and the ID bytes of the entropy are unused.
pub fn issue(entropy: &[u8], id: Option<&str>) -> Option<Issued> {
    if entropy.len() != ENTROPY_BYTES {
        return None;
    }
    let (id_bytes, secret_bytes) = entropy.split_at(ID_BYTES);
    let id = match id {
        Some(id) if id.len() <= MAX_ID_CHARS && is_token_part(id) => id.to_owned(),
        Some(_) => return None,
        None => hex(id_bytes),
    };
    let secret = hex(secret_bytes);
    Some(Issued {
        secret_hash: hex(&hash(&secret)),
        token: format!("{TOKEN_PREFIX}{id}.{secret}"),
        id,
    })
}

/// A sliding minute per token. Only requests that authenticated count.
#[derive(Debug, Default)]
pub(super) struct RateLimiter {
    windows: HashMap<String, VecDeque<Instant>>,
}

impl RateLimiter {
    pub fn admit(&mut self, token: &Token, now: Instant) -> bool {
        let window = self.windows.entry(token.id.clone()).or_default();
        while window
            .front()
            .is_some_and(|at| now.duration_since(*at) >= RATE_WINDOW)
        {
            window.pop_front();
        }
        if window.len() >= token.limit as usize {
            return false;
        }
        window.push_back(now);
        true
    }

    // Forgets the tokens the configuration no longer has.
    pub fn retain(&mut self, config: &Config) {
        self.windows
            .retain(|id, _| config.ids().any(|known| known == id));
    }
}

fn hash(secret: &str) -> [u8; 32] {
    Sha256::digest(secret.as_bytes()).into()
}

fn constant_time_eq(left: &[u8; 32], right: &[u8; 32]) -> bool {
    left.iter()
        .zip(right)
        .fold(0u8, |difference, (a, b)| difference | (a ^ b))
        == 0
}

fn is_token_part(text: &str) -> bool {
    !text.is_empty()
        && text
            .bytes()
            .all(|byte| byte.is_ascii_alphanumeric() || byte == b'-' || byte == b'_')
}

fn hex(bytes: &[u8]) -> String {
    const DIGITS: &[u8; 16] = b"0123456789abcdef";
    let mut text = String::with_capacity(bytes.len() * 2);
    for byte in bytes {
        text.push(DIGITS[usize::from(byte >> 4)] as char);
        text.push(DIGITS[usize::from(byte & 15)] as char);
    }
    text
}

fn decode_hash(text: &str) -> Option<[u8; 32]> {
    let digits = text.as_bytes();
    if digits.len() != 64 {
        return None;
    }
    let mut hash = [0u8; 32];
    for (index, byte) in hash.iter_mut().enumerate() {
        let high = (digits[index * 2] as char).to_digit(16)?;
        let low = (digits[index * 2 + 1] as char).to_digit(16)?;
        *byte = u8::try_from(high << 4 | low).ok()?;
    }
    Some(hash)
}
