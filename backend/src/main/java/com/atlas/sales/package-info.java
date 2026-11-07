/**
 * Customers, orders and order lines. Owns tables: customers, orders, order_lines. Pricing a line records a DERIVED_VALUE provenance edge from the product version it consumed.
 *
 * <p>Module boundary: other modules may call only this package's public application
 * services and DTOs, never its repositories or entities. Enforced from Phase 2 by
 * an architecture test.
 */
package com.atlas.sales;
