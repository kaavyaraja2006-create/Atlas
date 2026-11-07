package com.atlas.sales;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

public record Customer(UUID id, UUID organizationId, String name, String email, Instant createdAt,
                       Instant updatedAt, long version) {

    Map<String, Object> snapshot() {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("name", name);
        s.put("email", email);
        return s;
    }

    /** null leaves a field unchanged; an empty email clears it. */
    Customer withChanges(String newName, String newEmail) {
        String email = newEmail == null ? this.email : (newEmail.isBlank() ? null : newEmail.trim());
        return new Customer(id, organizationId, newName == null ? name : newName.trim(), email,
                createdAt, updatedAt, version);
    }
}
