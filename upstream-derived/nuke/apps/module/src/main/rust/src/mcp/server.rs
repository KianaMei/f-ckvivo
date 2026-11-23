use super::http::{self, Failure, Head, Response, Timed};
use super::rpc;
use super::token::{Config, RateLimiter, Token};
use super::tools::{Audit, Host};
use serde_json::{json, Value};
use std::collections::{HashMap, VecDeque};
use std::io::{self, Read};
use std::net::{IpAddr, Ipv4Addr, Shutdown, SocketAddr, TcpListener, TcpStream};
use std::panic::{catch_unwind, AssertUnwindSafe};
use std::sync::atomic::{AtomicBool, AtomicUsize, Ordering};
use std::sync::{Arc, Mutex, MutexGuard, PoisonError};
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};

const ENDPOINT: &str = "/mcp";
const REQUEST_DEADLINE: Duration = Duration::from_secs(10);
// A body is read only for a caller with a token, and may be a picture coming over a slow network.
const BODY_DEADLINE: Duration = Duration::from_secs(30);
const WRITE_TIMEOUT: Duration = Duration::from_secs(5);
const DRAIN_TIMEOUT: Duration = Duration::from_millis(250);
const WAKE_TIMEOUT: Duration = Duration::from_millis(200);
const ACCEPT_RETRY: Duration = Duration::from_millis(50);
const MAX_AUDIT_ENTRIES: usize = 100;

/// Who may connect to a server.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Reach {
    /// This device only: the server binds 127.0.0.1.
    Loopback,
    /// The networks this device is on: the server binds every IPv4 interface and answers only private, link-local and
    /// loopback addresses, so a public address that can reach the port, as on a mobile network, is still turned away.
    Lan,
}

impl Reach {
    fn admits(self, peer: IpAddr) -> bool {
        match (self, peer) {
            (_, peer) if peer.is_loopback() => true,
            (Reach::Lan, IpAddr::V4(peer)) => peer.is_private() || peer.is_link_local(),
            _ => false,
        }
    }
}

/// An MCP server on the loopback interface or the local network, speaking Streamable HTTP with JSON responses on `POST /mcp`. It owns the
/// socket, the tokens and the activity record, and no threads: every thread that calls `serve` becomes a worker that
/// answers one connection at a time with the `Host` it brought.
pub struct Server {
    listener: TcpListener,
    port: u16,
    reach: Reach,
    closed: AtomicBool,
    workers: AtomicUsize,
    state: Mutex<State>,
}

struct State {
    config: Arc<Config>,
    limiter: RateLimiter,
    audit: VecDeque<Value>,
    last_used: HashMap<String, u64>,
}

struct Worker<'a>(&'a AtomicUsize);

impl Drop for Worker<'_> {
    fn drop(&mut self) {
        self.0.fetch_sub(1, Ordering::SeqCst);
    }
}

impl Server {
    /// Binds `port` on the address `reach` asks for; 0 takes any free port.
    pub fn open(port: u16, reach: Reach, config: Config) -> io::Result<Server> {
        let address = match reach {
            Reach::Loopback => Ipv4Addr::LOCALHOST,
            Reach::Lan => Ipv4Addr::UNSPECIFIED,
        };
        let listener = TcpListener::bind((address, port))?;
        Ok(Server {
            port: listener.local_addr()?.port(),
            listener,
            reach,
            closed: AtomicBool::new(false),
            workers: AtomicUsize::new(0),
            state: Mutex::new(State {
                config: Arc::new(config),
                limiter: RateLimiter::default(),
                audit: VecDeque::new(),
                last_used: HashMap::new(),
            }),
        })
    }

    pub fn port(&self) -> u16 {
        self.port
    }

    /// Replaces the tokens. A request that is being answered sees the change before it acts for its token again.
    pub fn configure(&self, config: Config) {
        let mut state = self.state();
        state.limiter.retain(&config);
        state
            .last_used
            .retain(|id, _| config.ids().any(|known| known == id));
        state.config = Arc::new(config);
    }

    /// The port, the activity record with the newest line first, and when each token was last used, in milliseconds
    /// since the epoch. Both are kept in memory only.
    pub fn snapshot(&self) -> Value {
        let state = self.state();
        json!({ "port": self.port, "audit": state.audit, "lastUsed": state.last_used })
    }

    /// Answers connections on the calling thread until `close`. A request that panics loses its connection, not the
    /// worker.
    pub fn serve(&self, host: &mut dyn Host) {
        self.workers.fetch_add(1, Ordering::SeqCst);
        let _worker = Worker(&self.workers);
        while !self.closed.load(Ordering::SeqCst) {
            let Ok((stream, peer)) = self.listener.accept() else {
                std::thread::sleep(ACCEPT_RETRY);
                continue;
            };
            if self.closed.load(Ordering::SeqCst) {
                break;
            }
            // An address the server does not serve gets no answer at all, only a closed connection.
            if !self.reach.admits(peer.ip()) {
                continue;
            }
            let _ = catch_unwind(AssertUnwindSafe(|| self.answer(&stream, host)));
        }
    }

    /// Stops the workers: each one blocked in `accept` is woken by a connection of its own, and one that is answering
    /// a request leaves when it is done. The port is free once every worker has returned and the server is dropped.
    pub fn close(&self) {
        if self.closed.swap(true, Ordering::SeqCst) {
            return;
        }
        let address = SocketAddr::from((Ipv4Addr::LOCALHOST, self.port));
        for _ in 0..self.workers.load(Ordering::SeqCst) {
            let _ = TcpStream::connect_timeout(&address, WAKE_TIMEOUT);
        }
    }

    fn state(&self) -> MutexGuard<'_, State> {
        self.state.lock().unwrap_or_else(PoisonError::into_inner)
    }

    fn config(&self) -> Arc<Config> {
        self.state().config.clone()
    }

    fn answer(&self, stream: &TcpStream, host: &mut dyn Host) {
        let _ = stream.set_nodelay(true);
        let _ = stream.set_write_timeout(Some(WRITE_TIMEOUT));
        let mut reader = Timed::new(stream, REQUEST_DEADLINE);
        let mut carry = Vec::new();
        let (response, unread) = match http::read_head(&mut reader, &mut carry) {
            Ok(None) => return,
            // How much of a refused request is still on its way is unknown, so only the time bounds what is taken.
            Err(failure) => (failure.response(), usize::MAX),
            Ok(Some(head)) => self.respond(&head, &mut reader, &mut carry, host),
        };
        let mut out = stream;
        if response.write(&mut out).is_ok() && unread > 0 {
            drain(stream, unread);
        }
    }

    // The response, and how many bytes of the request were left unread.
    fn respond(
        &self,
        head: &Head,
        reader: &mut Timed<'_>,
        carry: &mut Vec<u8>,
        host: &mut dyn Host,
    ) -> (Response, usize) {
        let unread = head.content_length.saturating_sub(carry.len());
        let refuse = |status, code| (Failure { status, code }.response(), unread);
        // No MCP client sends Origin; a browser does, and a page must never reach this server.
        if head.header("origin").is_some() {
            return refuse(403, "ORIGIN_NOT_ALLOWED");
        }
        if head.target != ENDPOINT {
            return refuse(404, "NOT_FOUND");
        }
        if head.method != "POST" {
            let (response, unread) = refuse(405, "METHOD_NOT_ALLOWED");
            return (response.header("Allow", "POST"), unread);
        }
        if !http::is_json(head.header("content-type")) {
            return refuse(415, "UNSUPPORTED_MEDIA_TYPE");
        }
        if head
            .header("mcp-protocol-version")
            .is_some_and(|version| !rpc::supports(version))
        {
            return refuse(400, "UNSUPPORTED_PROTOCOL_VERSION");
        }
        let unauthorized = |unread| {
            let response = Failure {
                status: 401,
                code: "UNAUTHORIZED",
            }
            .response();
            (response.header("WWW-Authenticate", "Bearer"), unread)
        };
        // Checked before the body is read, so a caller without a token cannot make the server buffer anything.
        let authorization = head.header("authorization");
        if self.config().authenticate(authorization).is_none() {
            return unauthorized(unread);
        }
        reader.restart(BODY_DEADLINE);
        let body = match http::read_body(reader, carry, head.content_length) {
            Ok(body) => body,
            Err(failure) => return (failure.response(), 0),
        };
        let token = {
            let mut state = self.state();
            let config = state.config.clone();
            let Some(token) = config.authenticate(authorization) else {
                return unauthorized(0);
            };
            if !state.limiter.admit(token, Instant::now()) {
                let (response, _) = refuse(429, "RATE_LIMITED");
                return (response.header("Retry-After", "60"), 0);
            }
            state.last_used.insert(token.id.clone(), now_millis());
            token.clone()
        };
        let Ok(message) = serde_json::from_slice::<Value>(&body) else {
            let error = rpc::error(Value::Null, rpc::PARSE_ERROR, "Invalid JSON", None);
            return (Response::json(400, &error), 0);
        };
        let current = || self.config().current(&token).cloned();
        let handled = rpc::handle(&message, &token, &current, host);
        if let Some(audit) = handled.audit {
            self.record(&token, audit);
        }
        let response = match handled.response {
            Some(response) => Response::json(200, &response),
            None => Response::empty(202),
        };
        (response, 0)
    }

    fn record(&self, token: &Token, audit: Audit) {
        let mut state = self.state();
        state.audit.push_front(json!({
            "at": now_millis(),
            "token": token.id,
            "name": token.name,
            "tool": audit.tool,
            "target": audit.target,
            "ok": audit.ok,
            "code": audit.code,
        }));
        state.audit.truncate(MAX_AUDIT_ENTRIES);
    }
}

// Closing with request bytes unread resets the connection, which can cost the client the response it was just sent.
// The response is finished first, then what the client is still sending is taken off the socket for a moment.
fn drain(stream: &TcpStream, mut remaining: usize) {
    let _ = stream.shutdown(Shutdown::Write);
    let mut reader = Timed::new(stream, DRAIN_TIMEOUT);
    let mut buffer = [0u8; 4096];
    while remaining > 0 {
        let wanted = remaining.min(buffer.len());
        match reader.read(&mut buffer[..wanted]) {
            Ok(0) | Err(_) => break,
            Ok(count) => remaining -= count,
        }
    }
}

fn now_millis() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map_or(0, |elapsed| {
            u64::try_from(elapsed.as_millis()).unwrap_or(u64::MAX)
        })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn each_reach_admits_only_its_addresses() {
        let ip = |text: &str| text.parse::<IpAddr>().unwrap();
        for local in ["127.0.0.1", "127.8.9.1", "::1"] {
            assert!(Reach::Loopback.admits(ip(local)), "{local}");
            assert!(Reach::Lan.admits(ip(local)), "{local}");
        }
        for near in ["10.0.0.7", "172.16.4.2", "192.168.1.20", "169.254.3.4"] {
            assert!(!Reach::Loopback.admits(ip(near)), "{near}");
            assert!(Reach::Lan.admits(ip(near)), "{near}");
        }
        for far in [
            "8.8.8.8",
            "100.64.0.1",
            "172.32.0.1",
            "fe80::1",
            "2001:db8::1",
        ] {
            assert!(!Reach::Loopback.admits(ip(far)), "{far}");
            assert!(!Reach::Lan.admits(ip(far)), "{far}");
        }
    }
}
