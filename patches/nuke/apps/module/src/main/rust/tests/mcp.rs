// The MCP server over real loopback sockets, with a stand-in for the app.

use nuke_protocol_core::mcp::{
    issue, Claimed, Config, Contact, ContactKind, Host, HostError, Image, ImageFormat, Reach,
    Reward, SendStatus, Sent, Server, ENTROPY_BYTES, MAX_IMAGE_BYTES, MAX_RESPONSE_BYTES,
};
use serde_json::{json, Value};
use std::io::{Read, Write};
use std::net::{Ipv4Addr, TcpListener, TcpStream};
use std::sync::{Arc, Mutex};
use std::thread::{self, JoinHandle};

const ACCOUNT: &str = "1001";

type Hook = Box<dyn FnMut() + Send>;

struct App {
    account: Result<String, HostError>,
    contacts: Result<Vec<Contact>, HostError>,
    answer: Result<Sent, HostError>,
    sent: Vec<(Contact, String)>,
    images: Vec<(Contact, Image, bool)>,
    response: Result<Vec<u8>, HostError>,
    requests: Vec<(i32, i32, Vec<u8>)>,
    claimed: Result<Claimed, HostError>,
    claims: Vec<(Reward, Vec<String>, usize)>,
    marked: Result<(), HostError>,
    marks: usize,
    // Runs while the server waits for the contact list, to change things under a call.
    while_reading_contacts: Option<Hook>,
}

#[derive(Clone)]
struct FakeHost(Arc<Mutex<App>>);

impl Host for FakeHost {
    fn account(&mut self) -> Result<String, HostError> {
        self.0.lock().unwrap().account.clone()
    }

    fn contacts(&mut self) -> Result<Vec<Contact>, HostError> {
        let hook = self.0.lock().unwrap().while_reading_contacts.take();
        if let Some(mut hook) = hook {
            hook();
        }
        self.0.lock().unwrap().contacts.clone()
    }

    fn send_text(&mut self, to: &Contact, text: &str) -> Result<Sent, HostError> {
        let mut app = self.0.lock().unwrap();
        app.sent.push((to.clone(), text.to_owned()));
        app.answer.clone()
    }

    fn send_image(
        &mut self,
        to: &Contact,
        image: &Image,
        original: bool,
    ) -> Result<Sent, HostError> {
        let mut app = self.0.lock().unwrap();
        app.images.push((to.clone(), image.clone(), original));
        app.answer.clone()
    }

    fn send_protocol(
        &mut self,
        cmd: i32,
        inbox_type: i32,
        body: &[u8],
    ) -> Result<Vec<u8>, HostError> {
        let mut app = self.0.lock().unwrap();
        app.requests.push((cmd, inbox_type, body.to_vec()));
        app.response.clone()
    }

    fn claim(
        &mut self,
        reward: Reward,
        conversations: &[String],
        skip: usize,
    ) -> Result<Claimed, HostError> {
        let mut app = self.0.lock().unwrap();
        app.claims.push((reward, conversations.to_vec(), skip));
        app.claimed.clone()
    }

    fn mark_all_read(&mut self) -> Result<(), HostError> {
        let mut app = self.0.lock().unwrap();
        app.marks += 1;
        app.marked.clone()
    }
}

// A PNG file as far as its header, which is all the server reads of one.
fn png(width: u32, height: u32) -> Vec<u8> {
    let mut file = b"\x89PNG\r\n\x1a\n\0\0\0\rIHDR".to_vec();
    file.extend_from_slice(&width.to_be_bytes());
    file.extend_from_slice(&height.to_be_bytes());
    file
}

fn base64(bytes: &[u8]) -> String {
    const DIGITS: &[u8; 64] = b"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
    let mut text = String::new();
    for group in bytes.chunks(3) {
        let value = group
            .iter()
            .fold(0u32, |value, byte| value << 8 | u32::from(*byte))
            << (8 * (3 - group.len()));
        for index in 0..4 {
            if index <= group.len() {
                text.push(DIGITS[(value >> (18 - 6 * index) & 63) as usize] as char);
            } else {
                text.push('=');
            }
        }
    }
    text
}

fn friend(conversation: &str, uid: &str, name: &str) -> Contact {
    Contact {
        conversation: conversation.to_owned(),
        uid: Some(uid.to_owned()),
        name: name.to_owned(),
        kind: ContactKind::Friend,
    }
}

fn group(conversation: &str, name: &str) -> Contact {
    Contact {
        conversation: conversation.to_owned(),
        uid: None,
        name: name.to_owned(),
        kind: ContactKind::Group,
    }
}

fn host_error(code: &str) -> HostError {
    HostError {
        code: code.to_owned(),
        message: "why".to_owned(),
    }
}

// One stored token with a known secret; `seed` tells two of them apart.
fn token(seed: u8, extra: Value) -> (Value, String) {
    let issued = issue(&[seed; ENTROPY_BYTES], None).unwrap();
    let mut stored = json!({
        "id": issued.id, "name": format!("token {seed}"), "secretHash": issued.secret_hash, "enabled": true,
        "account": ACCOUNT, "permissions": ["READ_CONTACTS", "SEND_TEXT"], "allowAll": true, "conversations": [],
        "limit": 600,
    });
    for (key, value) in extra.as_object().unwrap() {
        stored[key] = value.clone();
    }
    (stored, issued.token)
}

struct Reply {
    status: u16,
    head: String,
    body: String,
}

impl Reply {
    fn json(&self) -> Value {
        serde_json::from_str(&self.body).unwrap()
    }
}

struct Fixture {
    server: Arc<Server>,
    app: Arc<Mutex<App>>,
    workers: Vec<JoinHandle<()>>,
    bearer: String,
}

impl Fixture {
    fn start(extra: Value) -> Fixture {
        let (stored, bearer) = token(7, extra);
        Self::with(json!({ "tokens": [stored] }), bearer)
    }

    fn with(config: Value, bearer: String) -> Fixture {
        Self::reaching(Reach::Loopback, config, bearer)
    }

    fn reaching(reach: Reach, config: Value, bearer: String) -> Fixture {
        let server = Arc::new(Server::open(0, reach, Config::parse(&config.to_string())).unwrap());
        let app = Arc::new(Mutex::new(App {
            account: Ok(ACCOUNT.to_owned()),
            contacts: Ok(vec![
                friend("c-ann", "11", "Ann"),
                friend("c-anna", "12", "Anna Lee"),
                group("g-team", "Team Ann"),
                friend("c-bob", "13", "Bob"),
            ]),
            answer: Ok(Sent {
                status: SendStatus::Sent,
                detail: "sendCode=0".to_owned(),
                message_id: Some("9001".to_owned()),
            }),
            sent: Vec::new(),
            images: Vec::new(),
            response: Ok(vec![0x18, 0, 0x22, 3, b'b', b'a', b'd']),
            requests: Vec::new(),
            claimed: Ok(Claimed::default()),
            claims: Vec::new(),
            marked: Ok(()),
            marks: 0,
            while_reading_contacts: None,
        }));
        let workers = (0..2)
            .map(|_| {
                let (server, mut host) = (server.clone(), FakeHost(app.clone()));
                thread::spawn(move || server.serve(&mut host))
            })
            .collect();
        Fixture {
            server,
            app,
            workers,
            bearer,
        }
    }

    fn raw(&self, request: &[u8]) -> Reply {
        let mut stream = TcpStream::connect((Ipv4Addr::LOCALHOST, self.server.port())).unwrap();
        // A refusal may come, and the connection go, before the whole request is written or read back.
        let _ = stream.write_all(request);
        let mut answer = Vec::new();
        let _ = stream.read_to_end(&mut answer);
        let answer = String::from_utf8(answer).unwrap();
        let (head, body) = answer.split_once("\r\n\r\n").unwrap();
        Reply {
            status: head.split(' ').nth(1).unwrap().parse().unwrap(),
            head: head.to_owned(),
            body: body.to_owned(),
        }
    }

    fn request(&self, method: &str, target: &str, headers: &str, body: &str) -> Reply {
        let request = format!(
            "{method} {target} HTTP/1.1\r\nHost: 127.0.0.1\r\nContent-Length: {}\r\n{headers}\r\n{body}",
            body.len()
        );
        self.raw(request.as_bytes())
    }

    fn post_as(&self, bearer: &str, message: &Value) -> Reply {
        let headers =
            format!("Content-Type: application/json\r\nAuthorization: Bearer {bearer}\r\n");
        self.request("POST", "/mcp", &headers, &message.to_string())
    }

    fn rpc(&self, method: &str, params: Value) -> Value {
        let message = json!({ "jsonrpc": "2.0", "id": 1, "method": method, "params": params });
        let reply = self.post_as(&self.bearer, &message);
        assert_eq!(reply.status, 200, "{}", reply.body);
        reply.json()
    }

    // The tool's own result and whether it reported an error.
    fn tool(&self, name: &str, arguments: Value) -> (Value, bool) {
        let answer = self.rpc(
            "tools/call",
            json!({ "name": name, "arguments": arguments }),
        );
        let result = &answer["result"];
        assert!(result.is_object(), "{answer}");
        let text = result["content"][0]["text"].as_str().unwrap();
        (
            serde_json::from_str(text).unwrap(),
            result["isError"].as_bool().unwrap(),
        )
    }

    // The protocol error of a tool call that was refused: its code and stable name.
    fn refused(&self, name: &str, arguments: Value) -> (i64, Value) {
        let answer = self.rpc(
            "tools/call",
            json!({ "name": name, "arguments": arguments }),
        );
        let error = &answer["error"];
        assert!(error.is_object(), "{answer}");
        (
            error["code"].as_i64().unwrap(),
            error["data"]["code"].clone(),
        )
    }

    fn sent(&self) -> Vec<(Contact, String)> {
        self.app.lock().unwrap().sent.clone()
    }

    fn audit(&self) -> Vec<Value> {
        self.server.snapshot()["audit"].as_array().unwrap().clone()
    }
}

impl Drop for Fixture {
    fn drop(&mut self) {
        self.server.close();
        for worker in self.workers.drain(..) {
            worker.join().unwrap();
        }
    }
}

fn names(result: &Value) -> Vec<&str> {
    result["contacts"]
        .as_array()
        .unwrap()
        .iter()
        .map(|contact| contact["name"].as_str().unwrap())
        .collect()
}

#[test]
fn initialize_negotiates_the_revision_and_names_the_server() {
    let fixture = Fixture::start(json!({}));
    let known = fixture.rpc("initialize", json!({ "protocolVersion": "2025-03-26" }));
    assert_eq!(known["result"]["protocolVersion"], "2025-03-26");
    assert_eq!(known["result"]["serverInfo"]["name"], "nuke-douyin");
    assert_eq!(
        known["result"]["capabilities"]["tools"]["listChanged"],
        false
    );
    let newer = fixture.rpc("initialize", json!({ "protocolVersion": "2099-01-01" }));
    assert_eq!(newer["result"]["protocolVersion"], "2025-06-18");
    assert_eq!(fixture.rpc("ping", json!({}))["result"], json!({}));
    assert_eq!(
        fixture.rpc("resources/list", json!({}))["error"]["code"],
        -32601
    );
}

#[test]
fn only_a_valid_enabled_token_gets_past_the_door() {
    let (stored, bearer) = token(7, json!({}));
    let (disabled, disabled_bearer) = token(8, json!({ "enabled": false }));
    let fixture = Fixture::with(json!({ "tokens": [stored, disabled] }), bearer.clone());
    let ping = json!({ "jsonrpc": "2.0", "id": 1, "method": "ping" });
    let (id, secret) = bearer.split_once('.').unwrap();
    let wrong_secret = format!("{id}.{}", secret.replace('0', "1"));
    let unknown_id = format!("nuke_mcp_ffffffffffffffff.{secret}");
    for bad in [
        wrong_secret.as_str(),
        unknown_id.as_str(),
        disabled_bearer.as_str(),
        "nuke_mcp_x",
        "",
    ] {
        let reply = fixture.post_as(bad, &ping);
        assert_eq!(reply.status, 401, "{bad}");
        assert!(reply.head.contains("WWW-Authenticate: Bearer"));
        // Nothing before the token check says what answers on this port.
        assert_eq!(reply.json(), json!({ "error": "UNAUTHORIZED" }));
    }
    let headers = "Content-Type: application/json\r\n";
    assert_eq!(
        fixture
            .request("POST", "/mcp", headers, &ping.to_string())
            .status,
        401
    );
    let basic = format!("{headers}Authorization: Basic {bearer}\r\n");
    assert_eq!(
        fixture
            .request("POST", "/mcp", &basic, &ping.to_string())
            .status,
        401
    );
    let lowercase = format!("{headers}authorization: bearer {bearer}\r\n");
    assert_eq!(
        fixture
            .request("POST", "/mcp", &lowercase, &ping.to_string())
            .status,
        200
    );
    assert!(fixture.audit().is_empty());
}

#[test]
fn the_transport_refuses_browsers_other_paths_and_other_shapes() {
    let fixture = Fixture::start(json!({}));
    let auth = format!(
        "Content-Type: application/json\r\nAuthorization: Bearer {}\r\n",
        fixture.bearer
    );
    let ping = json!({ "jsonrpc": "2.0", "id": 1, "method": "ping" }).to_string();
    let with_origin = format!("{auth}Origin: https://example.com\r\n");
    assert_eq!(
        fixture.request("POST", "/mcp", &with_origin, &ping).status,
        403
    );
    assert_eq!(fixture.request("POST", "/health", &auth, &ping).status, 404);
    let get = fixture.request("GET", "/mcp", &auth, "");
    assert_eq!(get.status, 405);
    assert!(get.head.contains("Allow: POST"));
    let plain = format!(
        "Content-Type: text/plain\r\nAuthorization: Bearer {}\r\n",
        fixture.bearer
    );
    assert_eq!(fixture.request("POST", "/mcp", &plain, &ping).status, 415);
    let future = format!("{auth}MCP-Protocol-Version: 2099-01-01\r\n");
    assert_eq!(fixture.request("POST", "/mcp", &future, &ping).status, 400);
    let current = format!("{auth}MCP-Protocol-Version: 2025-06-18\r\n");
    assert_eq!(fixture.request("POST", "/mcp", &current, &ping).status, 200);
    assert_eq!(fixture.raw(b"GET /mcp HTTP/1.0\r\n\r\n").status, 505);

    let broken = fixture.request("POST", "/mcp", &auth, "{");
    assert_eq!(broken.status, 400);
    assert_eq!(broken.json()["error"]["code"], -32700);
    let batch = fixture.request("POST", "/mcp", &auth, &format!("[{ping}]"));
    assert_eq!(batch.json()["error"]["code"], -32600);
    let notification =
        json!({ "jsonrpc": "2.0", "method": "notifications/initialized" }).to_string();
    let accepted = fixture.request("POST", "/mcp", &auth, &notification);
    assert_eq!((accepted.status, accepted.body.as_str()), (202, ""));
    let null_id = json!({ "jsonrpc": "2.0", "id": null, "method": "ping" }).to_string();
    assert_eq!(
        fixture.request("POST", "/mcp", &auth, &null_id).json()["error"]["code"],
        -32600
    );
    let no_version = json!({ "id": 3, "method": "ping" }).to_string();
    let answer = fixture.request("POST", "/mcp", &auth, &no_version).json();
    assert_eq!(
        (answer["error"]["code"].as_i64(), answer["id"].as_i64()),
        (Some(-32600), Some(3))
    );
}

#[test]
fn a_body_over_the_limit_is_refused_and_the_refusal_arrives() {
    let fixture = Fixture::start(json!({}));
    // The length alone decides, before any of the body is taken.
    let head = format!(
        "POST /mcp HTTP/1.1\r\nHost: a\r\nContent-Type: application/json\r\nAuthorization: Bearer {}\r\nContent-Length: {}\r\n\r\n",
        fixture.bearer,
        8 * 1024 * 1024 + 1
    );
    let mut request = head.into_bytes();
    request.extend(std::iter::repeat_n(b' ', 70000));
    assert_eq!(fixture.raw(&request).status, 413);
    // A caller without a token is turned away before its body is read, and still gets the answer.
    let mut unauthorized = b"POST /mcp HTTP/1.1\r\nHost: a\r\nContent-Type: application/json\r\nContent-Length: 60000\r\n\r\n".to_vec();
    unauthorized.extend(std::iter::repeat_n(b' ', 60000));
    assert_eq!(fixture.raw(&unauthorized).status, 401);
}

#[test]
fn a_token_sees_only_the_tools_it_may_call() {
    let fixture = Fixture::start(json!({ "permissions": ["READ_CONTACTS"] }));
    let listed = fixture.rpc("tools/list", json!({}));
    let tools: Vec<&str> = listed["result"]["tools"]
        .as_array()
        .unwrap()
        .iter()
        .map(|tool| tool["name"].as_str().unwrap())
        .collect();
    assert_eq!(tools, ["douyin_find_contact", "douyin_list_contacts"]);
    assert_eq!(
        listed["result"]["tools"][0]["inputSchema"]["required"],
        json!(["name"])
    );
    let (code, stable) = fixture.refused(
        "douyin_send_text",
        json!({ "conversationId": "c-ann", "text": "hi" }),
    );
    assert_eq!((code, stable), (-32003, json!("PERMISSION_DENIED")));
    assert!(fixture.sent().is_empty());
    assert_eq!(fixture.audit()[0]["code"], "PERMISSION_DENIED");

    let nothing = Fixture::start(json!({ "permissions": [] }));
    assert_eq!(
        nothing.rpc("tools/list", json!({}))["result"]["tools"],
        json!([])
    );
}

#[test]
fn finds_contacts_by_name_with_the_closest_first() {
    let fixture = Fixture::start(json!({}));
    let (found, failed) = fixture.tool("douyin_find_contact", json!({ "name": " ann " }));
    assert!(!failed);
    assert_eq!(names(&found), ["Ann", "Anna Lee", "Team Ann"]);
    assert_eq!(
        found["contacts"][0],
        json!({ "conversationId": "c-ann", "name": "Ann", "type": "friend" })
    );
    assert_eq!(found["matches"], 3);
    let (limited, _) = fixture.tool("douyin_find_contact", json!({ "name": "ann", "limit": 1 }));
    assert_eq!(
        (names(&limited), limited["matches"].as_u64()),
        (vec!["Ann"], Some(3))
    );
    let (by_id, _) = fixture.tool("douyin_find_contact", json!({ "name": "g-team" }));
    assert_eq!(names(&by_id), ["Team Ann"]);
    let (none, failed) = fixture.tool("douyin_find_contact", json!({ "name": "zed" }));
    assert_eq!((names(&none).len(), failed), (0, false));
    for bad in [
        json!({}),
        json!({ "name": "  " }),
        json!({ "name": 1 }),
        json!({ "name": "a", "limit": 0 }),
        json!({ "name": "a", "limit": "2" }),
        json!({ "name": "a", "extra": 1 }),
    ] {
        assert_eq!(
            fixture.refused("douyin_find_contact", bad.clone()).0,
            -32602,
            "{bad}"
        );
    }
}

#[test]
fn lists_contacts_in_pages_and_by_type() {
    let fixture = Fixture::start(json!({}));
    let (all, _) = fixture.tool("douyin_list_contacts", json!({}));
    assert_eq!(names(&all), ["Ann", "Anna Lee", "Team Ann", "Bob"]);
    let (groups, _) = fixture.tool("douyin_list_contacts", json!({ "type": "group" }));
    assert_eq!(
        (names(&groups), groups["total"].as_u64()),
        (vec!["Team Ann"], Some(1))
    );
    let (page, _) = fixture.tool(
        "douyin_list_contacts",
        json!({ "type": "friend", "limit": 1, "offset": 1 }),
    );
    assert_eq!(
        (names(&page), page["total"].as_u64()),
        (vec!["Anna Lee"], Some(3))
    );
    assert_eq!(
        fixture
            .refused("douyin_list_contacts", json!({ "type": "stranger" }))
            .0,
        -32602
    );
}

#[test]
fn a_token_reaches_only_the_chats_it_names() {
    let fixture =
        Fixture::start(json!({ "allowAll": false, "conversations": ["c-bob", "g-team"] }));
    let (all, _) = fixture.tool("douyin_list_contacts", json!({}));
    assert_eq!(names(&all), ["Team Ann", "Bob"]);
    let (found, _) = fixture.tool("douyin_find_contact", json!({ "name": "ann" }));
    assert_eq!(names(&found), ["Team Ann"]);
    let (refused, failed) = fixture.tool(
        "douyin_send_text",
        json!({ "conversationId": "c-ann", "text": "hi" }),
    );
    assert_eq!(
        (refused["errorCode"].as_str(), failed),
        (Some("CONVERSATION_NOT_ALLOWED"), true)
    );
    assert!(fixture.sent().is_empty());
    let (sent, failed) = fixture.tool(
        "douyin_send_text",
        json!({ "conversationId": "c-bob", "text": "hi" }),
    );
    assert_eq!((sent["status"].as_str(), failed), (Some("sent"), false));
}

#[test]
fn sends_text_to_a_friend_by_user_id_and_to_a_group_by_conversation() {
    let fixture = Fixture::start(json!({}));
    let (sent, failed) = fixture.tool(
        "douyin_send_text",
        json!({ "conversationId": "c-ann", "text": "你好\n👋" }),
    );
    assert!(!failed);
    assert_eq!(
        sent,
        json!({ "status": "sent", "conversationId": "c-ann", "detail": "sendCode=0", "messageId": "9001" })
    );
    fixture.tool(
        "douyin_send_text",
        json!({ "conversationId": "g-team", "text": "all" }),
    );
    let delivered = fixture.sent();
    assert_eq!(
        delivered[0],
        (friend("c-ann", "11", "Ann"), "你好\n👋".to_owned())
    );
    assert_eq!(
        delivered[1],
        (group("g-team", "Team Ann"), "all".to_owned())
    );

    let (missing, failed) = fixture.tool(
        "douyin_send_text",
        json!({ "conversationId": "c-nobody", "text": "hi" }),
    );
    assert_eq!(
        (missing["errorCode"].as_str(), failed),
        (Some("CONTACT_NOT_FOUND"), true)
    );
    for bad in [
        json!({ "conversationId": "c-ann" }),
        json!({ "conversationId": "c-ann", "text": " " }),
        json!({ "conversationId": " c-ann", "text": "hi" }),
        json!({ "conversationId": "c-ann", "text": "a\u{0}b" }),
        json!({ "conversationId": "c-ann", "text": "x".repeat(5001) }),
        json!({ "conversationId": "c-ann", "text": "hi", "uid": "11" }),
    ] {
        assert_eq!(fixture.refused("douyin_send_text", bad).0, -32602);
    }
    assert_eq!(fixture.sent().len(), 2);
    assert_eq!(
        fixture.rpc("tools/call", json!({ "name": "douyin_delete_everything" }))["error"]["code"],
        -32602
    );

    // The record names the tool, the chat and the outcome, and never the text.
    let audit = fixture.audit();
    assert_eq!(audit.len(), 9);
    assert_eq!(audit[8]["tool"], "douyin_send_text");
    assert_eq!(
        (
            audit[8]["target"].as_str(),
            audit[8]["ok"].as_bool(),
            audit[8]["code"].as_str()
        ),
        (Some("c-ann"), Some(true), Some("OK"))
    );
    assert_eq!(
        (audit[6]["ok"].as_bool(), audit[6]["code"].as_str()),
        (Some(false), Some("CONTACT_NOT_FOUND"))
    );
    assert_eq!(audit[0]["code"], "INVALID_PARAMS");
    assert!(!fixture.server.snapshot().to_string().contains("你好"));
    assert!(
        fixture.server.snapshot()["lastUsed"]
            .as_object()
            .unwrap()
            .len()
            == 1
    );
}

#[test]
fn sends_a_picture_with_its_bytes_and_declared_size() {
    let fixture = Fixture::start(json!({ "permissions": ["SEND_IMAGE"] }));
    let listed = fixture.rpc("tools/list", json!({}));
    assert_eq!(listed["result"]["tools"][0]["name"], "douyin_send_image");
    assert_eq!(listed["result"]["tools"].as_array().unwrap().len(), 1);
    let file = png(640, 480);
    let (sent, failed) = fixture.tool(
        "douyin_send_image",
        json!({ "conversationId": "c-ann", "image": base64(&file) }),
    );
    assert!(!failed);
    assert_eq!(
        sent,
        json!({ "status": "sent", "conversationId": "c-ann", "detail": "sendCode=0", "messageId": "9001" })
    );
    fixture.tool(
        "douyin_send_image",
        json!({ "conversationId": "g-team", "image": base64(&file), "original": true }),
    );
    let delivered = fixture.app.lock().unwrap().images.clone();
    let image = Image {
        bytes: file.clone(),
        format: ImageFormat::Png,
        width: 640,
        height: 480,
    };
    assert_eq!(
        delivered,
        [
            (friend("c-ann", "11", "Ann"), image.clone(), false),
            (group("g-team", "Team Ann"), image, true),
        ]
    );
    // The activity record names the chat and never carries the picture.
    let audit = fixture.audit();
    assert_eq!(audit[0]["tool"], "douyin_send_image");
    assert_eq!(audit[0]["target"], "g-team");
    assert!(!audit[0].to_string().contains(&base64(&file)));

    // A picture of the largest size still fits in one request.
    let mut large = png(1, 1);
    large.resize(MAX_IMAGE_BYTES, 0);
    let (sent, _) = fixture.tool(
        "douyin_send_image",
        json!({ "conversationId": "c-ann", "image": base64(&large) }),
    );
    assert_eq!(sent["status"], "sent");
    large.push(0);
    for bad in [
        json!({ "conversationId": "c-ann" }),
        json!({ "conversationId": "c-ann", "image": 7 }),
        json!({ "conversationId": "c-ann", "image": "not base64!" }),
        json!({ "conversationId": "c-ann", "image": base64(b"GIF89a") }),
        json!({ "conversationId": "c-ann", "image": base64(&large) }),
        json!({ "conversationId": "c-ann", "image": base64(&file), "original": "yes" }),
        json!({ "conversationId": "c-ann", "image": base64(&file), "text": "hi" }),
    ] {
        assert_eq!(fixture.refused("douyin_send_image", bad).0, -32602);
    }
    assert_eq!(fixture.app.lock().unwrap().images.len(), 3);

    // Text is another permission, and a chat the token does not reach stays out of reach.
    let (code, stable) = fixture.refused(
        "douyin_send_text",
        json!({ "conversationId": "c-ann", "text": "hi" }),
    );
    assert_eq!((code, stable), (-32003, json!("PERMISSION_DENIED")));
    let narrow = Fixture::start(
        json!({ "permissions": ["SEND_IMAGE"], "allowAll": false, "conversations": ["c-bob"] }),
    );
    let (refused, failed) = narrow.tool(
        "douyin_send_image",
        json!({ "conversationId": "c-ann", "image": base64(&file) }),
    );
    assert_eq!(
        (refused["errorCode"].as_str(), failed),
        (Some("CONVERSATION_NOT_ALLOWED"), true)
    );
    assert!(narrow.app.lock().unwrap().images.is_empty());
    let text_only = Fixture::start(json!({ "permissions": ["SEND_TEXT"] }));
    assert_eq!(
        text_only
            .refused(
                "douyin_send_image",
                json!({ "conversationId": "c-ann", "image": base64(&file) }),
            )
            .1,
        json!("PERMISSION_DENIED")
    );
}

#[test]
fn a_raw_request_goes_out_as_given_and_its_response_comes_back() {
    let fixture = Fixture::start(json!({ "permissions": ["SEND_PROTOCOL"] }));
    let listed = fixture.rpc("tools/list", json!({}));
    let tool = &listed["result"]["tools"][0];
    assert_eq!(tool["name"], "douyin_send_protocol");
    assert_eq!(tool["annotations"]["destructiveHint"], true);

    let (answer, failed) = fixture.tool(
        "douyin_send_protocol",
        json!({ "cmd": 100, "body": base64(&[8, 1]) }),
    );
    assert!(!failed);
    assert_eq!(
        answer,
        json!({
            "cmd": 100, "inboxType": 1, "response": base64(&[0x18, 0, 0x22, 3, b'b', b'a', b'd']),
            "responseBytes": 7, "statusCode": 0, "error": "bad",
        })
    );
    // With a tag the body is the business message alone, wrapped as that field of a new RequestBody.
    fixture.tool(
        "douyin_send_protocol",
        json!({ "cmd": 609, "inboxType": 0, "bodyTag": 5, "body": base64(&[1, 2]) }),
    );
    fixture.tool("douyin_send_protocol", json!({ "cmd": 7, "body": "" }));
    assert_eq!(
        fixture.app.lock().unwrap().requests,
        [
            (100, 1, vec![8, 1]),
            (609, 0, vec![0x2a, 2, 1, 2]),
            (7, 1, vec![]),
        ]
    );
    // The record names the command and never the body.
    assert_eq!(fixture.audit()[0]["target"], "cmd 7");
    assert_eq!(fixture.audit()[1]["target"], "cmd 609");

    for bad in [
        json!({ "body": "" }),
        json!({ "cmd": 0, "body": "" }),
        json!({ "cmd": 2147483648u32, "body": "" }),
        json!({ "cmd": 1 }),
        json!({ "cmd": 1, "body": "not base64!" }),
        json!({ "cmd": 1, "body": "", "inboxType": -1 }),
        json!({ "cmd": 1, "body": "", "bodyTag": 0 }),
        json!({ "cmd": 1, "body": "", "bodyTag": 536870912 }),
        json!({ "cmd": 1, "body": "A".repeat(4 * 1024 * 1024 / 3 * 4 + 8) }),
        json!({ "cmd": 1, "body": "", "conversationId": "c-ann" }),
    ] {
        assert_eq!(
            fixture.refused("douyin_send_protocol", bad.clone()).0,
            -32602,
            "{bad}"
        );
    }
    assert_eq!(fixture.app.lock().unwrap().requests.len(), 3);

    // What the app could not do comes back with its code, a response that is not an envelope comes back bare, and
    // one too large to hand over says that the request went out.
    fixture.app.lock().unwrap().response = Err(host_error("TIMEOUT"));
    let (timeout, failed) = fixture.tool("douyin_send_protocol", json!({ "cmd": 1, "body": "" }));
    assert_eq!(
        (timeout["errorCode"].as_str(), failed),
        (Some("TIMEOUT"), true)
    );
    fixture.app.lock().unwrap().response = Ok(vec![0xff]);
    let (bare, failed) = fixture.tool("douyin_send_protocol", json!({ "cmd": 1, "body": "" }));
    assert!(!failed);
    assert_eq!(
        bare,
        json!({ "cmd": 1, "inboxType": 1, "response": "/w==", "responseBytes": 1 })
    );
    fixture.app.lock().unwrap().response = Ok(vec![0; MAX_RESPONSE_BYTES + 1]);
    let (large, failed) = fixture.tool("douyin_send_protocol", json!({ "cmd": 1, "body": "" }));
    assert_eq!(
        (large["errorCode"].as_str(), failed),
        (Some("RESPONSE_TOO_LARGE"), true)
    );
}

#[test]
fn a_raw_request_needs_its_own_permission_and_a_token_that_reaches_every_chat() {
    let narrow = Fixture::start(
        json!({ "permissions": ["SEND_PROTOCOL"], "allowAll": false, "conversations": ["c-ann"] }),
    );
    let (refused, failed) = narrow.tool("douyin_send_protocol", json!({ "cmd": 1, "body": "" }));
    assert_eq!(
        (refused["errorCode"].as_str(), failed),
        (Some("ALL_CHATS_REQUIRED"), true)
    );
    let sender = Fixture::start(json!({ "permissions": ["SEND_TEXT", "SEND_IMAGE"] }));
    assert_eq!(
        sender
            .refused("douyin_send_protocol", json!({ "cmd": 1, "body": "" }))
            .1,
        json!("PERMISSION_DENIED")
    );
    let other = Fixture::start(json!({ "permissions": ["SEND_PROTOCOL"] }));
    other.app.lock().unwrap().account = Ok("2002".to_owned());
    assert_eq!(
        other
            .refused("douyin_send_protocol", json!({ "cmd": 1, "body": "" }))
            .1,
        json!("ACCOUNT_MISMATCH")
    );
    for fixture in [&narrow, &sender, &other] {
        assert!(fixture.app.lock().unwrap().requests.is_empty());
    }
}

#[test]
fn rewards_are_claimed_only_in_the_private_chats_a_token_reaches() {
    let fixture = Fixture::start(json!({ "permissions": ["CLAIM_REWARDS"] }));
    let listed = fixture.rpc("tools/list", json!({}));
    let tools: Vec<&str> = listed["result"]["tools"]
        .as_array()
        .unwrap()
        .iter()
        .map(|tool| tool["name"].as_str().unwrap())
        .collect();
    assert_eq!(tools, ["douyin_claim_sparks", "douyin_claim_closeness"]);

    // Every friend, never a group; the app's counts come back as they are, and a run that ended says so by no next.
    fixture.app.lock().unwrap().claimed = Ok(Claimed {
        targets: 2,
        reached: 2,
        scanned: 2,
        claimed: 3,
        already: 1,
        details: vec!["why".to_owned(); 5],
        ..Claimed::default()
    });
    let (all, failed) = fixture.tool("douyin_claim_sparks", json!({}));
    assert!(!failed);
    assert_eq!(
        all,
        json!({
            "targets": 2, "scanned": 2, "claimed": 3, "already": 1, "failed": 0, "unknown": 0, "unavailable": 0,
            "details": ["why", "why", "why"],
        })
    );
    // A run that stopped early names where the next one goes on, which comes back as skip.
    fixture.app.lock().unwrap().claimed = Ok(Claimed {
        targets: 9,
        reached: 4,
        ..Claimed::default()
    });
    let (partial, _) = fixture.tool(
        "douyin_claim_closeness",
        json!({ "conversationId": "c-bob", "skip": 2 }),
    );
    assert_eq!(partial["next"], 4);
    let friends = vec!["c-ann".to_owned(), "c-anna".to_owned(), "c-bob".to_owned()];
    assert_eq!(
        fixture.app.lock().unwrap().claims,
        [
            (Reward::Sparks, friends, 0),
            (Reward::Closeness, vec!["c-bob".to_owned()], 2),
        ]
    );
    assert_eq!(fixture.audit()[0]["target"], "c-bob");
    assert_eq!(fixture.audit()[1]["target"], Value::Null);

    // A group, a chat nobody has and an argument that does not read claim nothing.
    for unknown in ["g-team", "c-nobody"] {
        let (missing, failed) =
            fixture.tool("douyin_claim_sparks", json!({ "conversationId": unknown }));
        assert_eq!(
            (missing["errorCode"].as_str(), failed),
            (Some("CONTACT_NOT_FOUND"), true)
        );
    }
    for bad in [
        json!({ "conversationId": "" }),
        json!({ "skip": -1 }),
        json!({ "skip": 100001 }),
        json!({ "limit": 1 }),
    ] {
        assert_eq!(
            fixture.refused("douyin_claim_sparks", bad.clone()).0,
            -32602,
            "{bad}"
        );
    }
    fixture.app.lock().unwrap().claimed = Err(host_error("BUSY"));
    let (busy, failed) = fixture.tool("douyin_claim_sparks", json!({}));
    assert_eq!((busy["errorCode"].as_str(), failed), (Some("BUSY"), true));
    assert_eq!(fixture.app.lock().unwrap().claims.len(), 3);

    // A token that names its chats claims in those alone, and not at all without the permission.
    let narrow = Fixture::start(
        json!({ "permissions": ["CLAIM_REWARDS"], "allowAll": false, "conversations": ["c-ann", "g-team"] }),
    );
    narrow.tool("douyin_claim_closeness", json!({}));
    let (refused, failed) = narrow.tool(
        "douyin_claim_closeness",
        json!({ "conversationId": "c-bob" }),
    );
    assert_eq!(
        (refused["errorCode"].as_str(), failed),
        (Some("CONVERSATION_NOT_ALLOWED"), true)
    );
    assert_eq!(
        narrow.app.lock().unwrap().claims,
        [(Reward::Closeness, vec!["c-ann".to_owned()], 0)]
    );
    let sender = Fixture::start(json!({ "permissions": ["SEND_TEXT", "MARK_READ"] }));
    assert_eq!(
        sender.refused("douyin_claim_sparks", json!({})).1,
        json!("PERMISSION_DENIED")
    );
    assert!(sender.app.lock().unwrap().claims.is_empty());
}

#[test]
fn a_claim_follows_the_chats_the_token_reaches_once_the_app_has_answered() {
    let (stored, bearer) = token(7, json!({ "permissions": ["CLAIM_REWARDS"] }));
    let fixture = Fixture::with(json!({ "tokens": [stored.clone()] }), bearer);
    let server = fixture.server.clone();
    let mut narrowed = stored;
    narrowed["allowAll"] = json!(false);
    narrowed["conversations"] = json!(["c-bob"]);
    let config = json!({ "tokens": [narrowed] }).to_string();
    fixture.app.lock().unwrap().while_reading_contacts = Some(Box::new(move || {
        server.configure(Config::parse(&config));
    }));
    fixture.tool("douyin_claim_sparks", json!({}));
    assert_eq!(
        fixture.app.lock().unwrap().claims,
        [(Reward::Sparks, vec!["c-bob".to_owned()], 0)]
    );
}

#[test]
fn marking_everything_read_needs_its_permission_and_a_token_that_reaches_every_chat() {
    let fixture = Fixture::start(json!({ "permissions": ["MARK_READ"] }));
    let listed = fixture.rpc("tools/list", json!({}));
    assert_eq!(listed["result"]["tools"][0]["name"], "douyin_mark_all_read");
    let (answer, failed) = fixture.tool("douyin_mark_all_read", json!({}));
    assert!(!failed);
    assert_eq!(answer, json!({ "status": "submitted" }));
    assert_eq!(
        fixture
            .refused("douyin_mark_all_read", json!({ "conversationId": "c-ann" }))
            .0,
        -32602
    );
    fixture.app.lock().unwrap().marked = Err(host_error("NOT_ADAPTED"));
    let (unadapted, failed) = fixture.tool("douyin_mark_all_read", json!({}));
    assert_eq!(
        (unadapted["errorCode"].as_str(), failed),
        (Some("NOT_ADAPTED"), true)
    );
    assert_eq!(fixture.app.lock().unwrap().marks, 2);

    let narrow = Fixture::start(
        json!({ "permissions": ["MARK_READ"], "allowAll": false, "conversations": ["c-ann"] }),
    );
    let (refused, failed) = narrow.tool("douyin_mark_all_read", json!({}));
    assert_eq!(
        (refused["errorCode"].as_str(), failed),
        (Some("ALL_CHATS_REQUIRED"), true)
    );
    let claimer = Fixture::start(json!({ "permissions": ["CLAIM_REWARDS"] }));
    assert_eq!(
        claimer.refused("douyin_mark_all_read", json!({})).1,
        json!("PERMISSION_DENIED")
    );
    let other = Fixture::start(json!({ "permissions": ["MARK_READ"] }));
    other.app.lock().unwrap().account = Ok("2002".to_owned());
    assert_eq!(
        other.refused("douyin_mark_all_read", json!({})).1,
        json!("ACCOUNT_MISMATCH")
    );
    for fixture in [&narrow, &claimer, &other] {
        assert_eq!(fixture.app.lock().unwrap().marks, 0);
    }
}

#[test]
fn a_server_for_the_local_network_still_answers_this_device() {
    let (stored, bearer) = token(7, json!({}));
    let fixture = Fixture::reaching(Reach::Lan, json!({ "tokens": [stored] }), bearer);
    assert_eq!(fixture.rpc("ping", json!({}))["result"], json!({}));
    assert_eq!(
        fixture
            .request("POST", "/mcp", "Content-Type: application/json\r\n", "{}")
            .status,
        401
    );
}

#[test]
fn a_send_the_app_did_not_confirm_is_an_error_that_says_so() {
    let fixture = Fixture::start(json!({}));
    let send = json!({ "conversationId": "c-ann", "text": "hi" });
    fixture.app.lock().unwrap().answer = Ok(Sent {
        status: SendStatus::Unknown,
        detail: "No answer from the host in 10 s".to_owned(),
        message_id: None,
    });
    let (unknown, failed) = fixture.tool("douyin_send_text", send.clone());
    assert!(failed);
    assert_eq!(
        (unknown["status"].as_str(), unknown["errorCode"].as_str()),
        (Some("unknown"), Some("SEND_UNCONFIRMED"))
    );
    fixture.app.lock().unwrap().answer = Ok(Sent {
        status: SendStatus::Failed,
        detail: "7: blocked".to_owned(),
        message_id: None,
    });
    let (refused, failed) = fixture.tool("douyin_send_text", send.clone());
    assert_eq!(
        (
            refused["errorCode"].as_str(),
            refused["detail"].as_str(),
            failed
        ),
        (Some("SEND_FAILED"), Some("7: blocked"), true)
    );
    fixture.app.lock().unwrap().answer = Err(host_error("NOT_ADAPTED"));
    let (unsupported, failed) = fixture.tool("douyin_send_text", send.clone());
    assert_eq!(
        (unsupported["errorCode"].as_str(), failed),
        (Some("NOT_ADAPTED"), true)
    );
    fixture.app.lock().unwrap().contacts = Err(host_error("CONTACTS_UNAVAILABLE"));
    let (no_contacts, failed) = fixture.tool("douyin_send_text", send);
    assert_eq!(
        (no_contacts["errorCode"].as_str(), failed),
        (Some("CONTACTS_UNAVAILABLE"), true)
    );
    assert_eq!(fixture.sent().len(), 3);
}

#[test]
fn a_token_acts_only_as_the_account_it_was_made_for() {
    let fixture = Fixture::start(json!({}));
    fixture.app.lock().unwrap().account = Ok("2002".to_owned());
    let send = json!({ "conversationId": "c-ann", "text": "hi" });
    assert_eq!(
        fixture.refused("douyin_send_text", send.clone()),
        (-32004, json!("ACCOUNT_MISMATCH"))
    );
    assert_eq!(fixture.refused("douyin_list_contacts", json!({})).0, -32004);
    fixture.app.lock().unwrap().account = Err(host_error("ACCOUNT_UNAVAILABLE"));
    let (signed_out, failed) = fixture.tool("douyin_send_text", send);
    assert_eq!(
        (signed_out["errorCode"].as_str(), failed),
        (Some("ACCOUNT_UNAVAILABLE"), true)
    );
    assert!(fixture.sent().is_empty());
}

#[test]
fn a_change_made_while_a_call_waits_for_the_app_is_followed() {
    let (stored, bearer) = token(7, json!({}));
    let fixture = Fixture::with(json!({ "tokens": [stored.clone()] }), bearer);
    let send = json!({ "conversationId": "c-ann", "text": "hi" });

    let mut narrowed = stored.clone();
    narrowed["allowAll"] = json!(false);
    narrowed["conversations"] = json!(["c-bob"]);
    let (server, config) = (
        fixture.server.clone(),
        json!({ "tokens": [narrowed] }).to_string(),
    );
    fixture.app.lock().unwrap().while_reading_contacts =
        Some(Box::new(move || server.configure(Config::parse(&config))));
    let (refused, _) = fixture.tool("douyin_send_text", send.clone());
    assert_eq!(refused["errorCode"], "CONVERSATION_NOT_ALLOWED");

    let server = fixture.server.clone();
    fixture.app.lock().unwrap().while_reading_contacts =
        Some(Box::new(move || server.configure(Config::parse("{}"))));
    assert_eq!(
        fixture.refused("douyin_list_contacts", json!({})),
        (-32001, json!("TOKEN_REVOKED"))
    );
    assert!(fixture.sent().is_empty());
    let ping = json!({ "jsonrpc": "2.0", "id": 1, "method": "ping" });
    assert_eq!(fixture.post_as(&fixture.bearer, &ping).status, 401);
}

#[test]
fn a_new_secret_replaces_the_old_one_under_the_same_id() {
    let (stored, old) = token(7, json!({}));
    let fixture = Fixture::with(json!({ "tokens": [stored.clone()] }), old.clone());
    let id = stored["id"].as_str().unwrap();
    let rotated = issue(&[9; ENTROPY_BYTES], Some(id)).unwrap();
    assert_eq!(rotated.id, id);
    assert_ne!(rotated.token, old);
    let mut replaced = stored.clone();
    replaced["secretHash"] = json!(rotated.secret_hash);
    fixture
        .server
        .configure(Config::parse(&json!({ "tokens": [replaced] }).to_string()));
    let ping = json!({ "jsonrpc": "2.0", "id": 1, "method": "ping" });
    assert_eq!(fixture.post_as(&old, &ping).status, 401);
    assert_eq!(fixture.post_as(&rotated.token, &ping).status, 200);
    assert!(issue(&[9; ENTROPY_BYTES - 1], None).is_none());
    assert!(issue(&[9; ENTROPY_BYTES], Some("not an id")).is_none());
}

#[test]
fn each_token_has_its_own_requests_per_minute() {
    let (limited, limited_bearer) = token(7, json!({ "limit": 2 }));
    let (other, other_bearer) = token(8, json!({}));
    let fixture = Fixture::with(
        json!({ "tokens": [limited, other] }),
        limited_bearer.clone(),
    );
    let ping = json!({ "jsonrpc": "2.0", "id": 1, "method": "ping" });
    assert_eq!(fixture.post_as(&limited_bearer, &ping).status, 200);
    assert_eq!(fixture.post_as(&limited_bearer, &ping).status, 200);
    let over = fixture.post_as(&limited_bearer, &ping);
    assert_eq!(over.status, 429);
    assert!(over.head.contains("Retry-After: 60"));
    assert_eq!(fixture.post_as(&other_bearer, &ping).status, 200);
}

#[test]
fn a_configuration_that_does_not_read_grants_nothing() {
    let (good, _) = token(7, json!({}));
    let mut copies = vec![good.clone(), good.clone()];
    for (key, value) in [
        ("id", json!("has space")),
        ("secretHash", json!("abc")),
        ("limit", json!(0)),
        ("limit", json!(601)),
        ("account", json!(" ")),
        ("name", json!("")),
        ("name", Value::Null),
    ] {
        let (mut broken, _) = token(9, json!({}));
        broken[key] = value;
        copies.push(broken);
    }
    // Only the first of the two equal tokens is kept; every broken one is left out.
    assert_eq!(
        Config::parse(&json!({ "tokens": copies }).to_string()).len(),
        1
    );
    assert!(Config::parse("not json").is_empty());
    assert!(Config::parse("[]").is_empty());
    let many: Vec<Value> = (0..80u8).map(|seed| token(seed, json!({})).0).collect();
    assert_eq!(
        Config::parse(&json!({ "tokens": many }).to_string()).len(),
        64
    );
}

#[test]
fn close_ends_the_workers_and_frees_the_port() {
    let mut fixture = Fixture::start(json!({}));
    let port = fixture.server.port();
    fixture.server.close();
    for worker in fixture.workers.drain(..) {
        worker.join().unwrap();
    }
    assert_eq!(Arc::strong_count(&fixture.server), 1);
    let (server, app) = (fixture.server.clone(), fixture.app.clone());
    drop(fixture);
    // A worker that arrives after the close leaves at once.
    server.serve(&mut FakeHost(app));
    drop(server);
    TcpListener::bind((Ipv4Addr::LOCALHOST, port)).unwrap();
}
