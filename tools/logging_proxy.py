#!/usr/bin/env python3
"""Logging proxy: logs full URLs for plain HTTP, hostnames for CONNECT (TLS passthrough)."""
import socket, threading, sys, time
from urllib.parse import urlsplit

LOG = open(r"C:\Users\jaqenze\.zcode\workspace\default\proxy_log.txt", "a", encoding="utf-8", buffering=1)
LOCK = threading.Lock()

def log(msg):
    with LOCK:
        LOG.write(f"[{time.strftime('%H:%M:%S')}] {msg}\n")

def relay(a, b):
    try:
        while True:
            data = a.recv(65536)
            if not data:
                break
            b.sendall(data)
    except OSError:
        pass
    finally:
        try: a.close()
        except OSError: pass
        try: b.close()
        except OSError: pass

def handle(client):
    try:
        client.settimeout(30)
        req = b""
        while b"\r\n\r\n" not in req:
            chunk = client.recv(65536)
            if not chunk:
                client.close(); return
            req += chunk
            if len(req) > 65536: break
        head = req.split(b"\r\n")[0].decode("utf-8", "replace")
        if head.startswith("CONNECT"):
            host = head.split()[1]
            log(f"CONNECT {host}  (TLS passthrough)")
            hostpart, port = host.rsplit(":", 1) if ":" in host else (host, "443")
            upstream = socket.create_connection((hostpart, int(port)), timeout=15)
            client.sendall(b"HTTP/1.1 200 Connection Established\r\n\r\n")
            relay(client, upstream)
        else:
            # absolute-URI plain HTTP proxy request
            log(f"PLAIN {head}")
            # dump remaining headers too (UA etc.)
            for line in req.split(b"\r\n\r\n")[0].split(b"\r\n")[1:]:
                log(f"    | {line.decode('utf-8','replace')}")
            parts = head.split()
            url = parts[1] if len(parts) > 1 else ""
            sp = urlsplit(url)
            upstream = socket.create_connection((sp.hostname, sp.port or 80), timeout=15)
            upstream.sendall(req)
            relay(client, upstream)
    except Exception as e:
        log(f"ERR {e}")
        try: client.close()
        except OSError: pass

def main():
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 8888
    srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    srv.bind(("0.0.0.0", port))
    srv.listen(64)
    log(f"=== proxy listening on 0.0.0.0:{port} ===")
    while True:
        c, addr = srv.accept()
        log(f"conn from {addr[0]}:{addr[1]}")
        threading.Thread(target=handle, args=(c,), daemon=True).start()

if __name__ == "__main__":
    main()
