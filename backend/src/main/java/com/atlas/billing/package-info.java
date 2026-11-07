/**
 * Invoices, invoice lines and credit notes. Owns tables: invoices, invoice_lines, credit_notes. Issued invoices are final; corrections are compensating credit notes.
 *
 * <p>Module boundary: other modules may call only this package's public application
 * services and DTOs, never its repositories or entities. Enforced from Phase 2 by
 * an architecture test.
 */
package com.atlas.billing;
