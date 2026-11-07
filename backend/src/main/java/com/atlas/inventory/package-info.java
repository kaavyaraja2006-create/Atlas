/**
 * Stock on hand, valued at unit_cost. Owns tables: inventory_items. References products by FK but does not derive from price (the FK-is-not-derivation case).
 *
 * <p>Module boundary: other modules may call only this package's public application
 * services and DTOs, never its repositories or entities. Enforced from Phase 2 by
 * an architecture test.
 */
package com.atlas.inventory;
