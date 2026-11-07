# ATLAS — UI/UX Page Map and Design System

Every screen reads from real APIs; there is no hardcoded business data.
Pages are listed with the phase that ships them.

## Layout

Persistent left sidebar (deep navy) · top bar with organization switcher,
global search, notifications, profile menu · breadcrumbs · main content ·
right-hand drawer for record details and diffs.

## Page map

| # | Page | Key contents | Phase | Tier |
|---|---|---|---|---|
| 1 | Sign in | Email, password, show/hide, errors, lockout message | 3 | MUST |
| 2 | Create organization | Name, slug, currency; creator becomes admin | 3 | MUST |
| 3 | Members | Invite, roles, remove | 3 | MUST |
| 4 | Catalogue | Product table (search, sort, paginate); edit drawer with ETag conflict handling | 3 | MUST |
| 5 | Customers | Table + create/edit | 3 | MUST |
| 6 | Orders | Table; order detail with lines, totals, confirm, invoice | 3 | MUST |
| 7 | Invoices | Table; detail; issue, void; credit notes | 3 | MUST |
| 8 | Audit explorer | Filterable event table; drawer with field-level before/after diff; chain-verify button | 4 | MUST |
| 9 | Record history | Version timeline of one record with upstream/downstream provenance links | 4 | MUST |
| 10 | Recovery center — incidents | List and open incident from an audit event ("This change was wrong") | 5 | MUST |
| 11 | Recovery center — impact | Summary counts by classification; affected-record table; conflict warnings | 5 | MUST |
| 12 | Recovery center — plan | Ordered steps, expected before/after, risk, invariants; approve / reject / request changes | 5–6 | MUST |
| 13 | Recovery center — execution | Live step progress; verification report; downloadable report | 6 | MUST |
| 14 | Dependency explorer | React Flow graph from the faulty version; affected vs untouched nodes; legend; node drawer | 5 | SHOULD |
| 15 | Dashboard | Real counts: open incidents, recent changes, orders/invoices | 3+ | SHOULD |
| 16 | Settings / profile | Password, sessions, theme | 3 | SHOULD |
| 17 | Admin: system health | Health, schema version, job status | 8 | LATER |

## Design tokens (Tailwind theme + CSS variables)

| Token group | Values |
|---|---|
| Spacing | 4 px scale: 4, 8, 12, 16, 20, 24, 32, 40, 48, 64 |
| Radius | `sm` 4 px (inputs), `md` 6 px (buttons, cards), `lg` 10 px (dialogs) |
| Type | Inter; 12 / 13 (table) / 14 (body) / 16 / 20 / 24 / 30; tabular numerals for money |
| Neutrals (light) | bg `#F7F8FA`, surface `#FFFFFF`, border `#E3E6EB`, text `#14181F`, muted `#5B6472` |
| Neutrals (dark) | bg `#0B0F17`, surface `#121826`, border `#232B3A`, text `#E6E9EF`, muted `#98A2B3` |
| Sidebar | `#0F1B2D` with `#C9D3E3` text, active item `#1D4ED8` 12 % tint |
| Accent | blue `#1D4ED8` (light) / `#5B8CFF` (dark) |
| Semantic | success `#166534`, warning `#92400E`, danger `#B91C1C`, info `#0369A1` (light theme; success and warning darkened in Phase 9 after axe found badge text below 4.5:1 on their 10 % tints) |
| Impact colours | directly affected = danger, indirectly = warning, conflicted = purple `#7E22CE`, non-reversible = slate with lock icon, untouched = neutral |

Colour is never the only signal: every impact class also has an icon and a
text label (accessibility, colour-blind users).

## Component patterns

shadcn/ui primitives wrapped in ATLAS components: `DataTable` (TanStack Table,
server-side pagination/sort/filter), `RecordDrawer`, `DiffView` (field, before,
after), `ConfirmDialog` (types the record identifier for destructive actions),
`StatusBadge`, `EmptyState`, `ErrorState` (shows correlation id), `ConflictBanner`
(409: "changed by someone else — reload").

## State rules

Every data view has explicit loading (skeleton), empty, error and success
states. Mutations show pending, success toast and inline error. Forms use
React Hook Form + Zod, mirroring server validation; the server remains
authoritative.
