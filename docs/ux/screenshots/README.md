# UI screenshots (Phases 3–4)

Rendered from the production build in headless Chromium by
`frontend/e2e/screenshots.mjs`. **The API was not running:** Playwright
answered `/api/v1/**` with fake fixture data defined in that script (names
end in "(fixture)" / "(demo)"). They show layout, states and theming only —
not live data, and not a working backend.

| File | Shows |
|---|---|
| 01-sign-in-error.png | Server's generic 401 message after a failed sign-in |
| 02-catalogue.png | Catalogue table, INR formatting, status badges |
| 03-product-conflict.png | 409 from If-Match: conflict banner, save disabled, nothing overwritten |
| 04-order-detail.png | Draft order with lines, recomputed total, add-line form |
| 05-invoice-detail.png | Issued invoice, credit note, amount due |
| 06-catalogue-dark.png | Dark theme |
| 07-orders-mobile.png | 390 px wide; table scrolls inside its card, no page overflow |
| 09-audit.png | Audit history with actor names; chain verification result |
| 10-audit-diff.png | One change: field-level before/after, chain position, request id |
| 11-history-faulty-version.png | Product v1 (the faulty price): what it fed into, linked at the exact version |
| 12-history-mobile.png | Record history at 390 px |
| 13-recovery-list.png | Recovery incidents with status |
| 14-incident-proposed.png | A proposed plan: every step with before → after, prerequisite steps, credit note amount; the stored impact below |
| 15-incident-executed-verified.png | Executed plan: step outcomes, a manual step acknowledged, verification PASSED, incident RESOLVED |
| 16-incident-failed-dark.png | A step whose record changed is not overwritten; its dependents are BLOCKED; verification lists only the failed checks (dark) |
| 17-incident-mobile.png | Incident at 390 px; the step table scrolls inside its card |
| 18-execute-confirm.png | Execution needs the plan id typed; the dialog says what will and will not be written |
| 19-audit-open-incident.png | Opening an incident from an UPDATE in the audit drawer |
| 20-sign-in-keyboard-only.png | Signing in with the keyboard only (Tab, type, Enter) |

Since Phase 9 every state above is also checked with axe-core (WCAG 2.1 A/AA): `npm run a11y`, 0 violations.

The audit drawer (10) now also offers “Open recovery incident” for UPDATE events to users with `recovery.create`.
| 21-incident-conflicts.png | ATLAS X: conflicts per field: kind, evidence, what the plan does with each, a same-field conflict waiting for review |
| 22-incident-simulation.png | ATLAS X: six strategies simulated on one snapshot, constraints met or broken, the recommended one, risk factors |
| 23-integrity.png | ATLAS X: open findings, versioned rules with checks still to add, recent check runs |
| 24-integrity-finding-dark.png | ATLAS X: one finding with what the check found, acknowledge/resolve with a note, its history (dark) |
| 25-lineage-table.png | ATLAS X: multi-hop lineage as a table, with operation and confidence per edge |
| 26-lineage-react-flow.png | Graph Intelligence: lineage on React Flow (ELK layered layout, minimap, edge labels, zoom/fit, SVG/PNG export), production CSP enforced |
| 27-lineage-react-flow-dark.png | The same in dark theme with a node selected and its details (expand 2 hops) |
| 28-lineage-export.png | The PNG exported by the browser from that graph (html-to-image) |
| 29-schema-er-diagram.png | ER diagram of the REAL schema of migrations V1–V15 (catalog read by the graph SQL harness, classified by the Java code; fixture file `frontend/e2e/fixtures/schema-v15.json`), ELK layout |
| 30-schema-er-focus-dark.png | `orders` and its neighbours only, with the selected table's relationships (dark) |
| 31-schema-denied.png | A signed-in user who is not a platform administrator: permission denied, no schema request made |
| 33-schema-export.png | The PNG the browser exported of the whole diagram, after the export was recorded in the platform audit trail |
