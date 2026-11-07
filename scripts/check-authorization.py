#!/usr/bin/env python3
"""
Static authorization check: every HTTP endpoint must either check a permission
or be on an explicit, justified list of exceptions.

How: for each @*Mapping method in a *Controller, follow its calls into the
injected service classes (and, transitively, into helper methods of the same
class) and collect every `tenant.require(Permissions.X)` /
`tenant.requireAny(...)` it reaches. An endpoint whose call tree reaches none,
and that is not listed below, fails the check.

The exception list must match reality both ways: a listed endpoint that no
longer exists fails too, so the list cannot rot.

This is a static check of the code's structure. It does not prove the check
runs before the data is read (the services do it first by convention, and the
integration tests exercise 403s); it does catch an endpoint added without one.

Usage: scripts/check-authorization.py [--markdown docs/security/endpoint-permissions.md]
"""
import argparse
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / "backend/src/main/java/com/atlas"

# Endpoints without a permission check, each with the reason. Public ones must also be
# permitAll() in SecurityConfig; the rest still require a valid access token.
EXCEPTIONS = {
    ("POST", "/api/v1/auth/register"): "public: creating an account",
    ("POST", "/api/v1/auth/login"): "public: signing in (lockout + login history)",
    ("POST", "/api/v1/auth/refresh"): "public: authenticated by the refresh-token cookie",
    ("POST", "/api/v1/auth/logout"): "public: revokes the caller's own refresh token family",
    ("GET", "/api/v1/users/me"): "authenticated: the caller's own profile",
    ("GET", "/api/v1/organizations"): "authenticated: only the caller's own memberships",
    ("POST", "/api/v1/organizations"): "authenticated: any user may create an organization (becomes its admin)",
    ("GET", "/api/v1/access"): "authenticated + tenant member: the caller's own roles in the selected tenant",
    ("GET", "/api/v1/system/info"): "platform admin: enforced by hasRole(PLATFORM_ADMIN) in SecurityConfig",
}
PUBLIC = {k for k, v in EXCEPTIONS.items() if v.startswith("public")}

MAPPING = re.compile(r"@(Get|Post|Put|Patch|Delete)Mapping(?:\(\s*(?:value\s*=\s*)?\"([^\"]*)\"[^)]*\)|\(\s*\)|)")
CLASS_MAPPING = re.compile(r"@RequestMapping\(\s*(?:value\s*=\s*)?\"([^\"]*)\"")
# The ER Schema Explorer is not tenant data: SchemaAccessPolicy decides (platform administrators
# today), checked against the users table on every request and audited when denied.
SCHEMA_ACCESS = re.compile(r"\.requireSchemaAccess\(")
REQUIRE = re.compile(r"\.require(?:Any)?\(\s*((?:Permissions\.\w+\s*,?\s*)+)\)")


def strip(src):
    """Blank out comments, string/char literals and text blocks, keeping offsets."""
    out = []
    i, n = 0, len(src)
    while i < n:
        if src.startswith('"""', i):
            j = src.find('"""', i + 3)
            j = n if j < 0 else j + 3
            out.append(" " * (j - i)); i = j
        elif src.startswith("//", i):
            j = src.find("\n", i); j = n if j < 0 else j
            out.append(" " * (j - i)); i = j
        elif src.startswith("/*", i):
            j = src.find("*/", i + 2); j = n if j < 0 else j + 2
            out.append(re.sub(r"[^\n]", " ", src[i:j])); i = j
        elif src[i] in "\"'":
            q = src[i]; j = i + 1
            while j < n and src[j] != q:
                j += 2 if src[j] == "\\" else 1
            out.append(" " * (j + 1 - i)); i = j + 1
        else:
            out.append(src[i]); i += 1
    return "".join(out)


def body_at(code, start):
    """The {...} block starting at the first '{' at or after start."""
    i = code.index("{", start)
    depth = 0
    for j in range(i, len(code)):
        if code[j] == "{":
            depth += 1
        elif code[j] == "}":
            depth -= 1
            if depth == 0:
                return code[i:j + 1]
    raise ValueError("unbalanced braces")


# A declaration: an identifier, a parameter list without nested parentheses, then a body.
# Keywords that look the same (if/for/catch...) are filtered out below.
METHOD_DECL = re.compile(r"\b(\w+)\s*\(([^()]*(?:\([^()]*\)[^()]*)*)\)\s*(?:throws\s+[\w.,\s]+)?\{")
KEYWORDS = {"if", "for", "while", "switch", "catch", "synchronized", "return", "new", "else", "try", "do"}


_METHODS = {}


def methods(code):
    """name -> list of bodies (overloads kept together); cached per source text."""
    key = id(code)
    if key in _METHODS:
        return _METHODS[key]
    out = {}
    for m in METHOD_DECL.finditer(code):
        name = m.group(1)
        if name in KEYWORDS:
            continue
        # Skip calls that are followed by a block, e.g. lambdas "x -> {": a declaration's
        # name is preceded by a type, i.e. an identifier or '>' or ']' and whitespace.
        before = code[:m.start()].rstrip()
        if not before or not (before[-1].isalnum() or before[-1] in ">]_"):
            continue
        prev_word = re.search(r"(\w+)$", before)
        if prev_word and prev_word.group(1) in KEYWORDS | {"return", "throw", "else"}:
            continue
        out.setdefault(name, []).append(body_at(code, m.end() - 1))
    _METHODS[key] = out
    return out


def field_types(code):
    return dict((n, t) for t, n in re.findall(r"private\s+final\s+([A-Z]\w*)\s+(\w+)\s*;", code))


CLASSES = {}
for p in SRC.rglob("*.java"):
    raw = p.read_text()
    m = re.search(r"\b(?:class|record|interface)\s+([A-Z]\w*)", strip(raw))
    if m:
        CLASSES[m.group(1)] = (p, raw, strip(raw))


def permissions_reached(cls, method, seen):
    """Permissions required anywhere in the call tree of cls.method (same class + injected fields)."""
    if (cls, method) in seen or cls not in CLASSES:
        return set()
    seen.add((cls, method))
    _, raw, code = CLASSES[cls]
    found = set()
    fields = field_types(code)
    for body in methods(code).get(method, []):
        # Permissions appear as identifiers (not strings), so the stripped body is enough.
        if SCHEMA_ACCESS.search(body):
            found.add("PLATFORM_ADMIN_SCHEMA_ACCESS")
        for group in REQUIRE.findall(body):
            found |= set(re.findall(r"Permissions\.(\w+)", group))
        for target, called in re.findall(r"\b(\w+)\.(\w+)\(", body):
            if target in fields:
                found |= permissions_reached(fields[target], called, seen)
        for called in re.findall(r"(?<![.\w])(\w+)\(", body):
            if called in methods(code) and called != method:
                found |= permissions_reached(cls, called, seen)
    return found


def endpoints():
    for name, (path, raw, code) in sorted(CLASSES.items()):
        if not name.endswith("Controller"):
            continue
        base = CLASS_MAPPING.search(raw)
        base = base.group(1) if base else ""
        for m in MAPPING.finditer(raw):
            verb = m.group(1).upper()
            sub = m.group(2) or ""
            decl = METHOD_DECL.search(code, m.end())
            method = decl.group(1)
            params = decl.group(2)
            url = (base + sub) or "/"
            yield verb, url, name, method, params, path


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--markdown")
    a = ap.parse_args()
    rows, failures, seen = [], [], set()
    for verb, url, cls, method, params, path in endpoints():
        key = (verb, url)
        seen.add(key)
        perms = sorted(permissions_reached(cls, method, set()))
        if perms:
            rule = ", ".join(("platform admin (SchemaAccessPolicy, audited)" if p == "PLATFORM_ADMIN_SCHEMA_ACCESS" else p.lower()) for p in perms)
            if key in EXCEPTIONS:
                failures.append(f"{verb} {url}: listed as an exception but checks {rule}; remove it from EXCEPTIONS")
        elif key in EXCEPTIONS:
            rule = EXCEPTIONS[key]
        else:
            rule = "NONE"
            failures.append(f"{verb} {url} ({cls}.{method}): no permission check reached and not an approved exception")
        rows.append((url, verb, rule, f"{cls}.{method}"))
    for key in EXCEPTIONS:
        if key not in seen:
            failures.append(f"{key[0]} {key[1]}: in EXCEPTIONS but no such endpoint")
    # Public exceptions must be the permitAll() matchers, and nothing else may be.
    sec = CLASSES["SecurityConfig"][1]
    permit = set()
    for m in re.finditer(r"requestMatchers\(HttpMethod\.(\w+),([^)]*)\)\s*\.permitAll\(\)", sec):
        for u in re.findall(r"\"([^\"]+)\"", m.group(2)):
            permit.add((m.group(1), u))
    if permit != PUBLIC:
        failures.append(f"SecurityConfig permitAll() {sorted(permit)} != public exceptions {sorted(PUBLIC)}")
    rows.sort()
    width = max(len(r[0]) for r in rows)
    for url, verb, rule, where in rows:
        print(f"{verb:6} {url:{width}}  {rule}")
    if a.markdown:
        lines = ["# Endpoint permissions", "",
                 "Generated by `scripts/check-authorization.py` from the source; CI fails if an endpoint has",
                 "no permission check and is not an approved exception. Do not edit by hand.", "",
                 "| Method | Path | Requires | Handler |", "|---|---|---|---|"]
        lines += [f"| {v} | `{u}` | {r} | `{w}` |" for u, v, r, w in rows]
        Path(a.markdown).write_text("\n".join(lines) + "\n")
    print(f"\n{len(rows)} endpoints, {len(failures)} problems")
    for f in failures:
        print("FAIL", f)
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
