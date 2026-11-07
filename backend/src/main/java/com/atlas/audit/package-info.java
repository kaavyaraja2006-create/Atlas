/**
 * Append-only, hash-chained change events and chain verification. Owns tables: change_events, audit_chain_heads. Other modules write through its API only.
 *
 * <p>Module boundary: other modules may call only this package's public application
 * services and DTOs, never its repositories or entities. Enforced from Phase 2 by
 * an architecture test.
 */
package com.atlas.audit;
