#!/usr/bin/env python3
"""
Seeds a DEMO organization with SYNTHETIC data through the ATLAS HTTP API.

Nothing here is real: every company, person, product and amount is invented and
labelled "(demo)". Writing through the API, not the database, means every record,
audit event and provenance edge is produced by the application itself, exactly as
if people had clicked through the product.

What it creates (deterministic for a given --seed):
  * Four demo users with random passwords, printed once (and optionally written to a
    0600 file). No password is stored in this repository.
  * One organization, "Demo Traders (synthetic demo data)", with an admin, an
    operations manager, a recovery approver (separation of duties) and an auditor.
  * A catalogue of 24 household products, 40 customers, and orders in two waves:
    confirmed, invoiced, issued, plus some drafts and cancellations.
  * Two legitimate price changes, and ONE PLANTED MISTAKE: the price of the steel
    water bottle is typed with an extra zero (649 -> 6490). Orders and invoices after
    it consume the wrong price. This is the incident to recover in a live demo.

Safety:
  * Refuses to run without --yes-this-is-a-demo-environment.
  * Refuses a non-local API unless --allow-remote is also given.
  * Refuses if the demo organization already exists for the admin user.

Rate limits: the API allows 600 calls/min per user by default; the seed waits out
429 responses (Retry-After), so a full run takes a few minutes. Set
ATLAS_RATELIMIT_APIPERMINUTE higher on a dedicated demo stack to make it faster.

Usage:
  scripts/seed-demo.py --api http://127.0.0.1:8080 --yes-this-is-a-demo-environment \
      [--seed 42] [--orders 180] [--credentials-out demo-credentials.txt]
Only the Python standard library is used.
"""
import argparse
import json
import os
import random
import secrets
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
from decimal import ROUND_HALF_UP, Decimal

# The seed only talks to the given API: never route it through a system proxy (on Windows,
# urllib reads the Internet Options proxy and would send even 127.0.0.1 requests there).
urllib.request.install_opener(urllib.request.build_opener(urllib.request.ProxyHandler({})))

ORG_NAME = "Demo Traders (synthetic demo data)"
ORG_SLUG_PREFIX = "demo-traders"
EMAIL_DOMAIN = "demo.example.com"   # RFC 2606: reserved for examples, never a real mailbox

USERS = [  # (key, display name, roles; the first user creates the organization and becomes its admin)
    ("admin", "Asha Admin (demo)", None),
    ("ops", "Omar Operations (demo)", ["OPERATIONS_MANAGER"]),
    ("approver", "Priya Approver (demo)", ["RECOVERY_APPROVER"]),
    ("auditor", "Arun Auditor (demo)", ["AUDITOR"]),
]

# (sku, name, price INR, cost INR, popularity weight)
CATALOGUE = [
    ("KIT-001", "Stainless steel water bottle 750 ml", "649", "410", 9),
    ("KIT-002", "Non-stick frying pan 24 cm", "1299", "820", 6),
    ("KIT-003", "Pressure cooker 3 L", "2199", "1480", 5),
    ("KIT-004", "Glass storage jars, set of 3", "549", "310", 5),
    ("KIT-005", "Chef's knife 20 cm", "899", "520", 3),
    ("KIT-006", "Cast iron tawa 28 cm", "1149", "690", 3),
    ("HOM-001", "Cotton bedsheet, double", "1499", "880", 6),
    ("HOM-002", "Microfibre pillow", "699", "380", 5),
    ("HOM-003", "Blackout curtain 7 ft", "1199", "700", 3),
    ("HOM-004", "Bath towel, 500 GSM", "449", "240", 7),
    ("HOM-005", "Doormat, coir", "349", "170", 4),
    ("HOM-006", "Wall clock, 30 cm", "799", "430", 2),
    ("CLN-001", "Floor cleaner 1 L", "199", "110", 9),
    ("CLN-002", "Dishwash liquid 750 ml", "169", "95", 9),
    ("CLN-003", "Microfibre cloths, pack of 6", "299", "150", 6),
    ("CLN-004", "Spin mop with bucket", "1599", "1050", 3),
    ("CLN-005", "Laundry basket 45 L", "649", "380", 2),
    ("CLN-006", "Toilet brush with holder", "249", "120", 4),
    ("ELE-001", "LED bulb 9 W, pack of 4", "399", "230", 8),
    ("ELE-002", "Extension board, 4 sockets", "599", "350", 5),
    ("ELE-003", "Table fan 400 mm", "2499", "1720", 2),
    ("ELE-004", "Electric kettle 1.5 L", "1099", "690", 4),
    ("ELE-005", "Emergency torch, rechargeable", "699", "420", 3),
    ("ELE-006", "Mosquito racket", "449", "260", 4),
]
FAULTY_SKU = "KIT-001"
FAULTY_PRICE = "6490"          # the typo: an extra zero
LEGIT_CHANGES = [("CLN-001", "219", "Supplier cost increase"), ("ELE-001", "379", "Festive season discount")]

CUSTOMER_STEMS = [
    "Lakeview Apartments", "Green Leaf Cafe", "Sunrise Hostel", "Hilltop Guest House", "Blue Door Bakery",
    "Riverbank Clinic", "Corner Stationers", "Maple Montessori", "Harbour Hardware", "Old Town Tailors",
    "Silver Spoon Caterers", "Banyan Co-working", "Saffron Kitchen", "Northgate Gym", "Lotus Yoga Studio",
    "Pebble Creek Resort", "City Book Depot", "Orchid Florists", "Metro Dental Care", "Coastal Cold Storage",
    "Peacock Paying Guest", "Red Brick Boutique", "Morning Dew Dairy", "Tea Garden Lounge", "Quickfix Repairs",
    "Bright Minds Tuition", "Palm Grove Villas", "Starlight Events", "Evergreen Nursery", "Copper Pot Diner",
    "Seaside Laundromat", "Golden Grain Mill", "Bamboo Furniture", "Cloud Nine Salon", "Fresh Basket Grocers",
    "Moonlight Hotel", "Rainbow Play School", "Pine Ridge Hostel", "Urban Nest Rentals", "Sparrow Print Shop",
]

PAISE = Decimal("0.01")


class ApiError(Exception):
    pass


class Api:
    def __init__(self, base, verbose=False):
        self.base = base.rstrip("/")
        self.token = None
        self.org = None
        self.calls = 0
        self.waited = 0.0
        self.verbose = verbose

    def request(self, method, path, body=None, *, if_match=None, idempotent=False, tenanted=True, expect=(200, 201)):
        headers = {"Accept": "application/json"}
        data = None
        if body is not None:
            data = json.dumps(body).encode()
            headers["Content-Type"] = "application/json"
        if self.token:
            headers["Authorization"] = "Bearer " + self.token
        if tenanted and self.org:
            headers["X-Organization-Id"] = self.org
        if if_match is not None:
            headers["If-Match"] = f'"{if_match}"'
        if idempotent:
            headers["Idempotency-Key"] = str(uuid.uuid4())   # same key reused on a 429 retry
        while True:
            self.calls += 1
            req = urllib.request.Request(self.base + path, data=data, method=method, headers=headers)
            try:
                with urllib.request.urlopen(req, timeout=30) as r:
                    status, text = r.status, r.read().decode()
            except urllib.error.HTTPError as e:
                status, text = e.code, e.read().decode()
                if status == 429:
                    # Rejected by the rate-limit filter before any handler ran: safe to repeat.
                    wait = int(e.headers.get("Retry-After", "5"))
                    if wait > 60 or self.verbose:
                        print(f"   rate limited on {method} {path.split('?')[0]}, waiting {wait}s"
                              + (" (registration allows 5 per hour per address; restarting the backend resets"
                                 " the in-memory limits)" if path.endswith("/register") else ""), file=sys.stderr)
                    self.waited += wait
                    time.sleep(wait)
                    continue
            if status not in expect:
                raise ApiError(f"{method} {path} -> {status}: {text[:500]}")
            return json.loads(text) if text else None

    def login(self, email, password):
        r = self.request("POST", "/api/v1/auth/login", {"email": email, "password": password}, tenanted=False)
        self.token = r["accessToken"]


def money(v):
    return str(Decimal(v).quantize(PAISE, rounding=ROUND_HALF_UP))


def check_target(api_url, allow_remote):
    host = urllib.parse.urlparse(api_url).hostname or ""
    if host not in ("localhost", "127.0.0.1", "::1") and not allow_remote:
        sys.exit(f"refusing to seed {host}: not a local API. Add --allow-remote if this really is a demo stack.")


def seed(args):
    rnd = random.Random(args.seed)
    run_tag = secrets.token_hex(3)   # emails and slug unique per run, so a re-run never collides
    passwords = {k: secrets.token_urlsafe(18) for k, _, _ in USERS}
    emails = {k: f"{k}.{run_tag}@{EMAIL_DOMAIN}" for k, _, _ in USERS}
    slug = f"{ORG_SLUG_PREFIX}-{run_tag}"

    admin = Api(args.api, args.verbose)
    for key, name, _ in USERS:
        admin.request("POST", "/api/v1/auth/register",
                      {"email": emails[key], "displayName": name, "password": passwords[key]},
                      tenanted=False, expect=(201,))
    admin.login(emails["admin"], passwords["admin"])
    mine = admin.request("GET", "/api/v1/organizations", tenanted=False)
    if any(o["name"] == ORG_NAME for o in mine):
        sys.exit("this admin already has the demo organization; nothing done")
    org = admin.request("POST", "/api/v1/organizations", {"name": ORG_NAME, "slug": slug}, tenanted=False,
                        expect=(200, 201))
    admin.org = org["id"]
    for key, _, roles in USERS[1:]:
        admin.request("POST", "/api/v1/members", {"email": emails[key], "roles": roles}, expect=(200, 201))
    print(f"organization {ORG_NAME} ({slug}) with {len(USERS)} members")

    ops = Api(args.api, args.verbose)
    ops.login(emails["ops"], passwords["ops"])
    ops.org = org["id"]

    products = {}
    for sku, name, price, cost, weight in CATALOGUE:
        p = ops.request("POST", "/api/v1/products",
                        {"sku": sku, "name": name, "unitPrice": price, "unitCost": cost, "active": True},
                        idempotent=True, expect=(200, 201))
        products[sku] = {"id": p["id"], "version": p["version"], "weight": weight, "price": price}
    customers = []
    for stem in CUSTOMER_STEMS:
        c = ops.request("POST", "/api/v1/customers", {"name": f"{stem} (demo)"}, idempotent=True, expect=(200, 201))
        customers.append(c["id"])
    print(f"{len(products)} products, {len(customers)} customers")

    skus = [s for s, *_ in CATALOGUE]
    weights = [w for *_, w in CATALOGUE]
    counts = {"orders": 0, "confirmed": 0, "cancelled": 0, "drafts": 0, "invoices": 0, "issued": 0,
              "confirmedOrdersWithFaultyPrice": 0, "issuedInvoicesWithFaultyPrice": 0}

    def order_wave(n, after_fault):
        for _ in range(n):
            o = ops.request("POST", "/api/v1/orders", {"customerId": rnd.choice(customers)}, idempotent=True,
                            expect=(200, 201))
            counts["orders"] += 1
            chosen = []
            # The faulty product is popular; after the mistake, a share of orders keeps buying it.
            want_faulty = after_fault and rnd.random() < 0.35
            for _ in range(rnd.choices([1, 2, 3, 4], [35, 35, 20, 10])[0]):
                sku = rnd.choices(skus, weights)[0]
                if sku not in chosen:
                    chosen.append(sku)
            if want_faulty and FAULTY_SKU not in chosen:
                chosen.append(FAULTY_SKU)
            for sku in chosen:
                o = ops.request("POST", f"/api/v1/orders/{o['id']}/lines",
                                {"productId": products[sku]["id"], "quantity": rnd.choices([1, 2, 3, 4, 6], [40, 25, 15, 12, 8])[0]},
                                expect=(200, 201))
            faulty = after_fault and FAULTY_SKU in chosen
            r = rnd.random()
            if r < 0.07:
                ops.request("POST", f"/api/v1/orders/{o['id']}/cancel", if_match=o["version"])
                counts["cancelled"] += 1
                continue
            if r < 0.15:
                counts["drafts"] += 1   # left as a draft: someone is still working on it
                continue
            o = ops.request("POST", f"/api/v1/orders/{o['id']}/confirm", if_match=o["version"])
            counts["confirmed"] += 1
            counts["confirmedOrdersWithFaultyPrice"] += 1 if faulty else 0
            if rnd.random() < 0.8:
                inv = ops.request("POST", f"/api/v1/orders/{o['id']}/invoice", idempotent=True, expect=(200, 201))
                counts["invoices"] += 1
                if rnd.random() < 0.9:
                    ops.request("POST", f"/api/v1/invoices/{inv['id']}/issue", if_match=inv["version"])
                    counts["issued"] += 1
                    counts["issuedInvoicesWithFaultyPrice"] += 1 if faulty else 0

    def change_price(sku, price, reason):
        p = products[sku]
        r = ops.request("PATCH", f"/api/v1/products/{p['id']}", {"unitPrice": price, "reason": reason},
                        if_match=p["version"])
        p["version"], p["price"] = r["version"], price

    first = int(args.orders * 0.65)
    order_wave(first // 2, False)
    for sku, price, reason in LEGIT_CHANGES:
        change_price(sku, price, reason)
    order_wave(first - first // 2, False)
    change_price(FAULTY_SKU, FAULTY_PRICE, "Monsoon price update")   # THE PLANTED MISTAKE
    order_wave(args.orders - first, True)

    # Find the faulty change in the audit log, as a person would on the History page.
    faulty_id = products[FAULTY_SKU]["id"]
    # Audit access is the admin's (operations managers have no audit.read).
    events = admin.request("GET", "/api/v1/audit/events?" + urllib.parse.urlencode(
        {"entityType": "product", "entityId": faulty_id, "size": 20}))
    fault = next(e for e in events["items"] if "unit_price" in e["changedFields"]
                 and str(e["after"].get("unit_price")).startswith(FAULTY_PRICE))
    chain = admin.request("POST", "/api/v1/audit/verify")

    summary = {
        "data": "SYNTHETIC DEMO DATA - not real people, companies or transactions",
        "organization": {"id": org["id"], "slug": slug, "name": ORG_NAME},
        "users": {k: emails[k] for k, *_ in USERS},
        "counts": counts,
        "plantedMistake": {"product": "Stainless steel water bottle 750 ml", "sku": FAULTY_SKU,
                           "from": "649.00", "to": money(FAULTY_PRICE), "productId": faulty_id,
                           "changeEventId": fault["id"], "chainIndex": fault["chainIndex"]},
        "auditChain": chain,
        "apiCalls": admin.calls + ops.calls,
        "secondsWaitedForRateLimits": round(admin.waited + ops.waited),
    }
    print(json.dumps(summary, indent=2))
    if args.summary_out:
        with open(args.summary_out, "w") as f:
            json.dump(summary, f, indent=2)
    creds = "\n".join(f"{k:9} {emails[k]}  {passwords[k]}" for k, *_ in USERS)
    if args.credentials_out:
        fd = os.open(args.credentials_out, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
        with os.fdopen(fd, "w") as f:
            f.write("# ATLAS demo sign-ins (synthetic demo data). Do not commit.\n" + creds + "\n")
        print(f"sign-ins written to {args.credentials_out} (mode 600)")
    else:
        print("\nDemo sign-ins (shown once, not stored anywhere):\n" + creds)
    print("\nDemo: Omar (ops) made the mistake. Sign in as 'admin', open Audit, find the 6,490.00 price change "
          "of the water bottle and open a recovery incident; generate a plan; sign in as 'approver' to approve "
          "it; back as 'admin', execute and verify.")
    return summary


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--api", default="http://localhost:8080")
    ap.add_argument("--seed", type=int, default=42)
    ap.add_argument("--orders", type=int, default=180)
    ap.add_argument("--credentials-out")
    ap.add_argument("--summary-out", help="also write the summary JSON to this file (for CI)")
    ap.add_argument("--yes-this-is-a-demo-environment", dest="confirmed", action="store_true")
    ap.add_argument("--allow-remote", action="store_true")
    ap.add_argument("--verbose", action="store_true")
    args = ap.parse_args()
    if not args.confirmed:
        sys.exit("refusing: this writes synthetic demo data. Re-run with --yes-this-is-a-demo-environment.")
    if args.orders < 20:
        sys.exit("--orders must be at least 20")
    check_target(args.api, args.allow_remote)
    try:
        seed(args)
    except ApiError as e:
        sys.exit(f"seed failed: {e}")


if __name__ == "__main__":
    main()
