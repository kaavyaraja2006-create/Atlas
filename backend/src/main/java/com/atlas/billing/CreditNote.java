package com.atlas.billing;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

public record CreditNote(UUID id, UUID invoiceId, String creditNoteNumber, BigDecimal amount, String reason,
                         String status, Instant issuedAt, Instant createdAt, long version) {

    public static final String ENTITY_TYPE = "credit_note";

    public Map<String, Object> snapshot() {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("invoice_id", invoiceId.toString());
        s.put("credit_note_number", creditNoteNumber);
        s.put("amount", amount);
        s.put("reason", reason);
        s.put("status", status);
        s.put("issued_at", issuedAt == null ? null : issuedAt.toString());
        return s;
    }
}
