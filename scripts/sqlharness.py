"""Shared helpers for the SQL harnesses: extract the application's SQL from the Java
source (text blocks, also concatenated with shared fragments), bind :named parameters as
SQL literals, run them with psql, and count checks."""
import re
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
JAVA = ROOT / "backend/src/main/java"
MIGRATIONS = ROOT / "backend/src/main/resources/db/migration"

# Files whose SQL belongs to the ATLAS X modules (exercised by verify-atlasx-sql.py).
ATLASX_FILES = [
    "com/atlas/provenance/ProvenanceQueries.java",
    "com/atlas/recovery/ConflictRepository.java",
    "com/atlas/recovery/SimulationRepository.java",
    "com/atlas/integrity/IntegrityRepository.java",
    "com/atlas/history/HistoryController.java",
]

# Files whose SQL belongs to Graph Intelligence (exercised by verify-graph-sql.py).
GRAPH_FILES = [
    "com/atlas/graph/schema/SchemaIntrospector.java",
    "com/atlas/graph/schema/SchemaSnapshotRepository.java",
    "com/atlas/graph/schema/PlatformAudit.java",
    "com/atlas/graph/schema/PlatformAdminSchemaAccess.java",
]

_BLOCK = r'"""\n(?:.*?)"""'
_PATTERN = re.compile(r'static final String (\w+) =\s*((?:' + _BLOCK + r'|\w+)(?:\s*\+\s*(?:' + _BLOCK + r'|\w+))*);', re.S)
_PART = re.compile(r'"""\n(.*?)"""|(\w+)', re.S)


def statements_in(path):
    raw = dict(_PATTERN.findall(Path(path).read_text()))

    def resolve(expr):
        return "".join(text if not ref else resolve(raw[ref]) for text, ref in _PART.findall(expr))
    fragments = {ref for expr in raw.values() for _, ref in _PART.findall(expr) if ref}
    return {name: resolve(expr).replace("\\\\", "\\") for name, expr in raw.items() if name not in fragments}


def extract(paths=None):
    """All statements by name. Two statements with one name would make a test run the wrong SQL: refused."""
    found, where = {}, {}
    for path in (paths or JAVA.rglob("*.java")):
        for name, sql in statements_in(path).items():
            if name in found:
                raise SystemExit(f"SQL statement name {name} is defined in both {where[name]} and {path.name}")
            found[name], where[name] = sql, path.name
    return found


def atlasx_statement_names():
    return {name for f in ATLASX_FILES if (JAVA / f).exists() for name in statements_in(JAVA / f)}


def graph_statement_names():
    return {name for f in GRAPH_FILES if (JAVA / f).exists() for name in statements_in(JAVA / f)}


class Raw:
    def __init__(self, sql):
        self.sql = sql


def literal(value):
    if value is None:
        return "NULL"
    if isinstance(value, bool):
        return "true" if value else "false"
    if isinstance(value, (int, float)):
        return str(value)
    if isinstance(value, Raw):
        return value.sql
    return "'" + str(value).replace("'", "''") + "'"


TAG = re.compile(r"^(INSERT 0 \d+|UPDATE \d+|DELETE \d+)$")


class Rows(list):
    tags = []


class Harness:
    def __init__(self, db, sql):
        self.db, self.sql, self.used, self.passes, self.failures = db, sql, set(), 0, 0

    def bind(self, name, params):
        self.used.add(name)
        text = self.sql[name]
        names = set(re.findall(r"(?<![:\w]):([A-Za-z]\w*)", text))
        missing = names - params.keys()
        if missing:
            raise SystemExit(f"{name}: no value for {sorted(missing)}")
        return re.sub(r"(?<![:\w]):([A-Za-z]\w*)", lambda m: literal(params[m.group(1)]), text)

    def psql(self, sql, expect_error=None):
        r = subprocess.run(["psql", "-X", "-At", "-F", "|", "-v", "ON_ERROR_STOP=1", "-d", self.db, "-c", sql],
                           capture_output=True, text=True)
        if expect_error:
            return r.returncode != 0 and expect_error in r.stderr, r.stderr.strip()
        if r.returncode != 0:
            raise RuntimeError(r.stderr.strip())
        rows = Rows(line for line in r.stdout.splitlines() if line != "" and not TAG.match(line))
        rows.tags = [line for line in r.stdout.splitlines() if TAG.match(line)]
        return rows

    def run(self, _statement, **params):
        return self.psql(self.bind(_statement, params))

    def check(self, label, condition, detail=""):
        if condition:
            self.passes += 1
            print(f"   PASS  {label}")
        else:
            self.failures += 1
            print(f"   FAIL  {label}  {detail}")


def create_database(db):
    subprocess.run(["psql", "-X", "-q", "-d", "postgres", "-c", f"CREATE DATABASE {db}"], check=True)
    for f in sorted(MIGRATIONS.glob("V*__*.sql"), key=lambda p: int(p.name[1:].split("__")[0])):
        subprocess.run(["psql", "-X", "-q", "-v", "ON_ERROR_STOP=1", "-d", db, "--single-transaction",
                        "-f", str(f)], check=True, capture_output=True)


def drop_database(db):
    subprocess.run(["psql", "-X", "-q", "-d", "postgres", "-c", f"DROP DATABASE IF EXISTS {db}"])
