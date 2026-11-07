/**
 * The provenance graph: which recorded version of a record was computed from which other recorded version. Owns tables: provenance_edges. Phase 4.
 *
 * <p>Module boundary: other modules may call only this package's public application
 * services and DTOs, never its repositories or entities. Enforced from Phase 2 by
 * an architecture test.
 */
package com.atlas.provenance;
