"""Helper for web-tier-check.sh: `stub PORT` runs a fake backend; `check URL` runs the checks."""
import re
import sys
import urllib.error
import urllib.request
from http.server import BaseHTTPRequestHandler, HTTPServer

API_CSP = "default-src 'none'; frame-ancestors 'none'"


class Stub(BaseHTTPRequestHandler):
    def log_message(self, *a):
        pass

    def do_GET(self):
        self.reply()

    do_POST = do_GET

    def reply(self):
        ready = self.path == "/actuator/health/readiness"
        body = b'{"status":"UP"}' if ready else b'{"status":401}'
        self.send_response(200 if ready else 401)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Security-Policy", API_CSP)
        self.send_header("X-Seen-Path", self.path)
        self.send_header("X-Seen-Forwarded", self.headers.get("X-Forwarded-For", ""))
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)


def get(base, path, method="GET", headers=None):
    req = urllib.request.Request(base + path, method=method, headers=headers or {})
    try:
        r = urllib.request.urlopen(req)
    except urllib.error.HTTPError as e:
        r = e
    return r.status, {k.lower(): v for k, v in r.headers.items()}, r.read()


def check(base):
    fails = 0

    def ok(name, cond):
        nonlocal fails
        print(("PASS " if cond else "FAIL ") + name)
        fails += 0 if cond else 1

    s, h, b = get(base, "/sign-in")
    ok("SPA route serves index.html with the strict CSP",
       s == 200 and b'id="root"' in b and "script-src 'self' 'sha256-" in h.get("content-security-policy", ""))
    s, h, b = get(base, "/recovery/some-id")
    ok("deep link falls back to index.html", s == 200 and b'id="root"' in b)
    s, h, b = get(base, "/")
    ok("index.html is revalidated (no-cache)", h.get("cache-control") == "no-cache")
    asset = re.search(rb"/assets/[^\"]+\.js", b).group().decode()
    s, h, _ = get(base, asset)
    ok("hashed assets are immutable", s == 200 and "immutable" in h.get("cache-control", ""))
    s, h, _ = get(base, "/api/v1/auth/refresh", "POST")
    ok("/api is proxied and keeps the backend's own CSP",
       s == 401 and h.get("content-security-policy") == API_CSP and h.get("x-seen-path") == "/api/v1/auth/refresh")
    s, h, _ = get(base, "/healthz")
    ok("/healthz is the backend's readiness", s == 200 and h.get("x-seen-path") == "/actuator/health/readiness")
    s, _, _ = get(base, "/actuator/prometheus")
    ok("management endpoints are not reachable through the web tier", s == 404)
    s, h, _ = get(base, "/sign-in")
    ok("security headers set, Server header removed",
       h.get("x-content-type-options") == "nosniff" and h.get("referrer-policy") == "no-referrer"
       and "server" not in h and "max-age" in h.get("strict-transport-security", ""))
    # The load balancer (a private address, here 127.0.0.1) appends the client to X-Forwarded-For.
    for sent, want in (("6.6.6.6, 203.0.113.9", "203.0.113.9"), ("6.6.6.6, 203.0.113.9, 10.0.1.5", "203.0.113.9"),
                       ("203.0.113.9", "203.0.113.9")):
        _, h, _ = get(base, "/api/v1/auth/login", "POST", {"X-Forwarded-For": sent})
        ok(f"backend sees the real client {want}, not a spoofed prefix ({sent!r})", h.get("x-seen-forwarded") == want)
    print(f"{fails} failed")
    sys.exit(1 if fails else 0)


if __name__ == "__main__":
    if sys.argv[1] == "stub":
        HTTPServer(("127.0.0.1", int(sys.argv[2])), Stub).serve_forever()
    check(sys.argv[2])
