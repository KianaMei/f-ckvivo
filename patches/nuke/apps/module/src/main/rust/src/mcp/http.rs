use serde_json::{json, Value};
use std::io::{self, ErrorKind, Read, Write};
use std::net::TcpStream;
use std::time::{Duration, Instant};

// Room for the largest picture a tool call may carry, as base64 inside its JSON-RPC message.
pub(super) const MAX_BODY_BYTES: usize = 8 * 1024 * 1024;
const MAX_REQUEST_LINE_BYTES: usize = 4 * 1024;
const MAX_HEADER_LINE_BYTES: usize = 8 * 1024;
const MAX_HEAD_BYTES: usize = 20 * 1024;
const MAX_HEADERS: usize = 64;
const READ_IDLE: Duration = Duration::from_secs(5);
const HEAD_END: &[u8] = b"\r\n\r\n";

/// The request line and headers of one HTTP/1.1 request. Header names are lowercase and unique.
#[derive(Debug)]
pub(super) struct Head {
    pub method: String,
    pub target: String,
    headers: Vec<(String, String)>,
    pub content_length: usize,
}

impl Head {
    pub fn header(&self, name: &str) -> Option<&str> {
        self.headers
            .iter()
            .find(|(known, _)| known == name)
            .map(|(_, value)| value.as_str())
    }
}

/// A request the server answers without looking at it further.
#[derive(Debug, PartialEq, Eq)]
pub(super) struct Failure {
    pub status: u16,
    pub code: &'static str,
}

impl Failure {
    const fn new(status: u16, code: &'static str) -> Self {
        Self { status, code }
    }

    pub fn response(&self) -> Response {
        Response::json(self.status, &json!({ "error": self.code }))
    }
}

/// Reads a socket under one deadline for the whole request and an idle limit per read, so a slow sender holds a worker
/// for a bounded time.
pub(super) struct Timed<'a> {
    stream: &'a TcpStream,
    deadline: Instant,
}

impl<'a> Timed<'a> {
    pub fn new(stream: &'a TcpStream, limit: Duration) -> Self {
        Self {
            stream,
            deadline: Instant::now() + limit,
        }
    }

    /// Starts the deadline again, for the next part of the request.
    pub fn restart(&mut self, limit: Duration) {
        self.deadline = Instant::now() + limit;
    }
}

impl Read for Timed<'_> {
    fn read(&mut self, buffer: &mut [u8]) -> io::Result<usize> {
        let remaining = self
            .deadline
            .checked_duration_since(Instant::now())
            .filter(|remaining| !remaining.is_zero())
            .ok_or(ErrorKind::TimedOut)?;
        let mut stream = self.stream;
        stream.set_read_timeout(Some(remaining.min(READ_IDLE)))?;
        stream.read(buffer)
    }
}

fn timed_out(error: &io::Error) -> bool {
    matches!(error.kind(), ErrorKind::TimedOut | ErrorKind::WouldBlock)
}

/// Reads up to the blank line that ends the head. Bytes read past it stay in `carry` for the body. No head at all, a
/// connection that closed before sending anything or broke while sending, is `Ok(None)` and gets no answer.
pub(super) fn read_head(
    reader: &mut impl Read,
    carry: &mut Vec<u8>,
) -> Result<Option<Head>, Failure> {
    let mut chunk = [0u8; 2048];
    let mut searched = 0;
    loop {
        if let Some(end) = find(&carry[searched..], HEAD_END) {
            let head = parse_head(&carry[..searched + end])?;
            carry.drain(..searched + end + HEAD_END.len());
            return Ok(Some(head));
        }
        if carry.len() > MAX_HEAD_BYTES {
            return Err(Failure::new(431, "HEAD_TOO_LARGE"));
        }
        searched = carry.len().saturating_sub(HEAD_END.len() - 1);
        match reader.read(&mut chunk) {
            Ok(0) if carry.is_empty() => return Ok(None),
            Ok(0) => return Err(Failure::new(400, "INCOMPLETE_HEAD")),
            Ok(count) => carry.extend_from_slice(&chunk[..count]),
            Err(error) if timed_out(&error) => return Err(Failure::new(408, "REQUEST_TIMEOUT")),
            Err(_) => return Ok(None),
        }
    }
}

/// The body of `length` bytes: what `read_head` already took, then the rest from the reader.
pub(super) fn read_body(
    reader: &mut impl Read,
    carry: &mut Vec<u8>,
    length: usize,
) -> Result<Vec<u8>, Failure> {
    let mut body = std::mem::take(carry);
    body.truncate(length);
    let mut filled = body.len();
    body.resize(length, 0);
    while filled < length {
        match reader.read(&mut body[filled..]) {
            Ok(0) => return Err(Failure::new(400, "INCOMPLETE_BODY")),
            Ok(count) => filled += count,
            Err(error) if timed_out(&error) => return Err(Failure::new(408, "REQUEST_TIMEOUT")),
            Err(_) => return Err(Failure::new(400, "INCOMPLETE_BODY")),
        }
    }
    Ok(body)
}

fn find(haystack: &[u8], needle: &[u8]) -> Option<usize> {
    haystack
        .windows(needle.len())
        .position(|window| window == needle)
}

// Strict on purpose: the server speaks to MCP clients only, so anything a lenient parser would have to guess about
// (bare line feeds, folded or repeated headers, transfer codings) is refused instead.
fn parse_head(head: &[u8]) -> Result<Head, Failure> {
    let mut lines = lines(head)?.into_iter();
    let request_line = lines.next().unwrap_or_default();
    if request_line.len() > MAX_REQUEST_LINE_BYTES {
        return Err(Failure::new(431, "LINE_TOO_LARGE"));
    }
    let mut parts = request_line.split(|byte| *byte == b' ');
    let (Some(method), Some(target), Some(version), None) =
        (parts.next(), parts.next(), parts.next(), parts.next())
    else {
        return Err(Failure::new(400, "INVALID_REQUEST_LINE"));
    };
    if method.is_empty() || !method.iter().all(u8::is_ascii_uppercase) {
        return Err(Failure::new(400, "INVALID_METHOD"));
    }
    if version != b"HTTP/1.1" {
        return Err(Failure::new(505, "HTTP_VERSION_NOT_SUPPORTED"));
    }
    if target.first() != Some(&b'/') || !target.iter().all(u8::is_ascii_graphic) {
        return Err(Failure::new(400, "INVALID_TARGET"));
    }

    let mut headers: Vec<(String, String)> = Vec::new();
    for line in lines {
        if line.len() > MAX_HEADER_LINE_BYTES {
            return Err(Failure::new(431, "LINE_TOO_LARGE"));
        }
        if headers.len() == MAX_HEADERS {
            return Err(Failure::new(431, "TOO_MANY_HEADERS"));
        }
        let Some(colon) = line.iter().position(|byte| *byte == b':') else {
            return Err(Failure::new(400, "INVALID_HEADER"));
        };
        let (name, value) = (&line[..colon], &line[colon + 1..]);
        if name.is_empty() || !name.iter().copied().all(is_token_byte) {
            return Err(Failure::new(400, "INVALID_HEADER_NAME"));
        }
        if value
            .iter()
            .any(|byte| (*byte < 0x20 && *byte != b'\t') || *byte == 0x7f)
        {
            return Err(Failure::new(400, "INVALID_HEADER_VALUE"));
        }
        let name = String::from_utf8_lossy(name).to_ascii_lowercase();
        if headers.iter().any(|(known, _)| *known == name) {
            return Err(Failure::new(400, "DUPLICATE_HEADER"));
        }
        let value = String::from_utf8_lossy(value);
        headers.push((name, value.trim_matches([' ', '\t']).to_owned()));
    }

    let head = Head {
        method: String::from_utf8_lossy(method).into_owned(),
        target: String::from_utf8_lossy(target).into_owned(),
        headers,
        content_length: 0,
    };
    if head.header("host").is_none_or(str::is_empty) {
        return Err(Failure::new(400, "HOST_REQUIRED"));
    }
    if head.header("transfer-encoding").is_some() {
        return Err(Failure::new(501, "TRANSFER_ENCODING_NOT_SUPPORTED"));
    }
    if head
        .header("content-encoding")
        .is_some_and(|encoding| !encoding.eq_ignore_ascii_case("identity"))
    {
        return Err(Failure::new(415, "CONTENT_ENCODING_NOT_SUPPORTED"));
    }
    if head.header("expect").is_some() {
        return Err(Failure::new(417, "EXPECTATION_NOT_SUPPORTED"));
    }
    let content_length = match head.header("content-length") {
        None if head.method == "POST" => return Err(Failure::new(411, "CONTENT_LENGTH_REQUIRED")),
        None => 0,
        Some(digits) if digits.is_empty() || !digits.bytes().all(|byte| byte.is_ascii_digit()) => {
            return Err(Failure::new(400, "INVALID_CONTENT_LENGTH"));
        }
        // More digits than fit is larger than any body the server takes.
        Some(digits) => digits.parse::<usize>().unwrap_or(usize::MAX),
    };
    if content_length > MAX_BODY_BYTES {
        return Err(Failure::new(413, "BODY_TOO_LARGE"));
    }
    Ok(Head {
        content_length,
        ..head
    })
}

// The head without its closing blank line, cut at each CRLF. A line feed without its carriage return is refused.
fn lines(head: &[u8]) -> Result<Vec<&[u8]>, Failure> {
    let mut lines = Vec::new();
    let mut rest = head;
    while let Some(feed) = rest.iter().position(|byte| *byte == b'\n') {
        if feed == 0 || rest[feed - 1] != b'\r' {
            return Err(Failure::new(400, "CRLF_REQUIRED"));
        }
        lines.push(&rest[..feed - 1]);
        rest = &rest[feed + 1..];
    }
    lines.push(rest);
    Ok(lines)
}

fn is_token_byte(byte: u8) -> bool {
    byte.is_ascii_alphanumeric() || b"!#$%&'*+-.^_`|~".contains(&byte)
}

/// Whether a `Content-Type` value is JSON, with at most a UTF-8 charset parameter.
pub(super) fn is_json(content_type: Option<&str>) -> bool {
    let Some(content_type) = content_type else {
        return false;
    };
    let mut parts = content_type.split(';');
    let media = parts.next().unwrap_or_default().trim();
    let parameter = parts.next();
    if !media.eq_ignore_ascii_case("application/json") || parts.next().is_some() {
        return false;
    }
    parameter.is_none_or(|parameter| {
        parameter.split_once('=').is_some_and(|(name, value)| {
            name.trim().eq_ignore_ascii_case("charset")
                && value.trim().trim_matches('"').eq_ignore_ascii_case("utf-8")
        })
    })
}

/// One response. Every connection carries one request, so each response closes it.
#[derive(Debug)]
pub(super) struct Response {
    pub status: u16,
    headers: Vec<(&'static str, String)>,
    body: Option<Vec<u8>>,
}

impl Response {
    pub fn json(status: u16, body: &Value) -> Self {
        Self {
            status,
            headers: Vec::new(),
            body: Some(body.to_string().into_bytes()),
        }
    }

    pub fn empty(status: u16) -> Self {
        Self {
            status,
            headers: Vec::new(),
            body: None,
        }
    }

    pub fn header(mut self, name: &'static str, value: &str) -> Self {
        self.headers.push((name, value.to_owned()));
        self
    }

    pub fn write(&self, out: &mut impl Write) -> io::Result<()> {
        let body = self.body.as_deref().unwrap_or_default();
        let mut head = format!("HTTP/1.1 {} {}\r\n", self.status, reason(self.status));
        if self.body.is_some() {
            head.push_str("Content-Type: application/json; charset=utf-8\r\n");
        }
        head.push_str(&format!("Content-Length: {}\r\n", body.len()));
        head.push_str(
            "Connection: close\r\nCache-Control: no-store\r\nX-Content-Type-Options: nosniff\r\n",
        );
        for (name, value) in &self.headers {
            head.push_str(&format!("{name}: {value}\r\n"));
        }
        head.push_str("\r\n");
        out.write_all(head.as_bytes())?;
        out.write_all(body)?;
        out.flush()
    }
}

fn reason(status: u16) -> &'static str {
    match status {
        200 => "OK",
        202 => "Accepted",
        400 => "Bad Request",
        401 => "Unauthorized",
        403 => "Forbidden",
        404 => "Not Found",
        405 => "Method Not Allowed",
        408 => "Request Timeout",
        411 => "Length Required",
        413 => "Payload Too Large",
        415 => "Unsupported Media Type",
        417 => "Expectation Failed",
        429 => "Too Many Requests",
        431 => "Request Header Fields Too Large",
        501 => "Not Implemented",
        505 => "HTTP Version Not Supported",
        _ => "Error",
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn head(text: &str) -> Result<Option<Head>, Failure> {
        read_head(&mut text.as_bytes(), &mut Vec::new())
    }

    fn failure(text: &str) -> (u16, &'static str) {
        let failure = head(text).unwrap_err();
        (failure.status, failure.code)
    }

    #[test]
    fn reads_a_head_and_leaves_the_body_in_the_carry() {
        let mut carry = Vec::new();
        let request =
            "POST /mcp HTTP/1.1\r\nHost: a\r\nContent-Length: 4\r\nX-Other:\t v \r\n\r\nbody";
        let head = read_head(&mut request.as_bytes(), &mut carry)
            .unwrap()
            .unwrap();
        assert_eq!(
            (head.method.as_str(), head.target.as_str()),
            ("POST", "/mcp")
        );
        assert_eq!(head.content_length, 4);
        assert_eq!(head.header("x-other"), Some("v"));
        assert_eq!(
            read_body(&mut "".as_bytes(), &mut carry, 4).unwrap(),
            b"body"
        );
    }

    #[test]
    fn a_body_split_across_reads_is_joined() {
        let mut carry = b"ab".to_vec();
        assert_eq!(
            read_body(&mut "cdEXTRA".as_bytes(), &mut carry, 4).unwrap(),
            b"abcd"
        );
        let mut carry = b"ab".to_vec();
        assert_eq!(
            read_body(&mut "c".as_bytes(), &mut carry, 4)
                .unwrap_err()
                .code,
            "INCOMPLETE_BODY"
        );
    }

    #[test]
    fn a_connection_that_sends_nothing_gets_no_answer() {
        assert!(head("").unwrap().is_none());
        assert_eq!(
            failure("GET / HTTP/1.1\r\nHost: a\r\n").1,
            "INCOMPLETE_HEAD"
        );
    }

    #[test]
    fn refuses_what_it_would_have_to_guess_about() {
        assert_eq!(
            failure("GET / HTTP/1.1\nHost: a\r\n\r\n"),
            (400, "CRLF_REQUIRED")
        );
        assert_eq!(
            failure("GET  / HTTP/1.1\r\nHost: a\r\n\r\n"),
            (400, "INVALID_REQUEST_LINE")
        );
        assert_eq!(
            failure("get / HTTP/1.1\r\nHost: a\r\n\r\n"),
            (400, "INVALID_METHOD")
        );
        assert_eq!(
            failure("GET / HTTP/1.0\r\nHost: a\r\n\r\n"),
            (505, "HTTP_VERSION_NOT_SUPPORTED")
        );
        assert_eq!(
            failure("GET mcp HTTP/1.1\r\nHost: a\r\n\r\n"),
            (400, "INVALID_TARGET")
        );
        assert_eq!(failure("GET / HTTP/1.1\r\n\r\n"), (400, "HOST_REQUIRED"));
        assert_eq!(
            failure("GET / HTTP/1.1\r\nHost: a\r\n folded\r\n\r\n"),
            (400, "INVALID_HEADER")
        );
        assert_eq!(
            failure("GET / HTTP/1.1\r\nHost: a\r\nBad Name: 1\r\n\r\n"),
            (400, "INVALID_HEADER_NAME")
        );
        assert_eq!(
            failure("GET / HTTP/1.1\r\nHost: a\r\nX: a\u{1}b\r\n\r\n"),
            (400, "INVALID_HEADER_VALUE")
        );
        assert_eq!(
            failure("GET / HTTP/1.1\r\nHost: a\r\nhost: b\r\n\r\n"),
            (400, "DUPLICATE_HEADER")
        );
        assert_eq!(
            failure("POST / HTTP/1.1\r\nHost: a\r\nTransfer-Encoding: chunked\r\n\r\n"),
            (501, "TRANSFER_ENCODING_NOT_SUPPORTED")
        );
        assert_eq!(
            failure(
                "POST / HTTP/1.1\r\nHost: a\r\nContent-Encoding: gzip\r\nContent-Length: 0\r\n\r\n"
            ),
            (415, "CONTENT_ENCODING_NOT_SUPPORTED")
        );
        assert_eq!(
            failure(
                "POST / HTTP/1.1\r\nHost: a\r\nExpect: 100-continue\r\nContent-Length: 0\r\n\r\n"
            ),
            (417, "EXPECTATION_NOT_SUPPORTED")
        );
    }

    #[test]
    fn bounds_the_body_and_the_head() {
        assert_eq!(
            failure("POST / HTTP/1.1\r\nHost: a\r\n\r\n"),
            (411, "CONTENT_LENGTH_REQUIRED")
        );
        assert_eq!(
            failure("POST / HTTP/1.1\r\nHost: a\r\nContent-Length: -1\r\n\r\n"),
            (400, "INVALID_CONTENT_LENGTH")
        );
        assert_eq!(
            failure(&format!(
                "POST / HTTP/1.1\r\nHost: a\r\nContent-Length: {}\r\n\r\n",
                MAX_BODY_BYTES + 1
            )),
            (413, "BODY_TOO_LARGE")
        );
        assert_eq!(
            failure(
                "POST / HTTP/1.1\r\nHost: a\r\nContent-Length: 99999999999999999999999\r\n\r\n"
            ),
            (413, "BODY_TOO_LARGE")
        );
        let long = format!(
            "GET / HTTP/1.1\r\nHost: a\r\nX: {}\r\n\r\n",
            "v".repeat(MAX_HEADER_LINE_BYTES)
        );
        assert_eq!(failure(&long), (431, "LINE_TOO_LARGE"));
        let many = format!(
            "GET / HTTP/1.1\r\nHost: a\r\n{}\r\n",
            (0..MAX_HEADERS)
                .map(|index| format!("X-{index}: 1\r\n"))
                .collect::<String>()
        );
        assert_eq!(failure(&many), (431, "TOO_MANY_HEADERS"));
        let endless = format!("GET / HTTP/1.1\r\nHost: {}", "a".repeat(MAX_HEAD_BYTES + 1));
        assert_eq!(failure(&endless), (431, "HEAD_TOO_LARGE"));
    }

    #[test]
    fn json_content_types() {
        assert!(is_json(Some("application/json")));
        assert!(is_json(Some("Application/JSON; charset=\"UTF-8\"")));
        assert!(!is_json(Some("application/json; charset=latin1")));
        assert!(!is_json(Some("application/json; charset=utf-8; x=1")));
        assert!(!is_json(Some("text/plain")));
        assert!(!is_json(None));
    }

    #[test]
    fn writes_a_closing_response() {
        let mut out = Vec::new();
        Response::json(401, &json!({ "error": "X" }))
            .header("WWW-Authenticate", "Bearer")
            .write(&mut out)
            .unwrap();
        let text = String::from_utf8(out).unwrap();
        assert!(text.starts_with("HTTP/1.1 401 Unauthorized\r\n"));
        assert!(text.contains("\r\nContent-Length: 13\r\n"));
        assert!(text.contains("\r\nConnection: close\r\n"));
        assert!(text.contains("\r\nWWW-Authenticate: Bearer\r\n"));
        assert!(text.ends_with("\r\n\r\n{\"error\":\"X\"}"));
        let mut out = Vec::new();
        Response::empty(202).write(&mut out).unwrap();
        let text = String::from_utf8(out).unwrap();
        assert!(text.contains("\r\nContent-Length: 0\r\n") && !text.contains("\r\nContent-Type:"));
    }
}
