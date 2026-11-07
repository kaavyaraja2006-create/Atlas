package com.atlas.common.idempotency;

import com.atlas.common.error.ApiException;
import com.atlas.common.json.Json;
import com.atlas.common.tenancy.TenantContext;
import java.util.function.Supplier;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Makes a create/execute POST safe to retry. With an Idempotency-Key, the
 * key is claimed, the action runs and its response is stored, all in ONE
 * transaction: if the action fails, the claim rolls back too and the client
 * may retry. A retry of a completed request replays the stored response
 * (with header Idempotent-Replayed: true) instead of acting twice.
 *
 * <p>Only the status and body are replayed; headers such as ETag or
 * Location are not stored.
 */
@Component
public class Idempotency {

    public static final String REPLAYED_HEADER = "Idempotent-Replayed";

    private final IdempotencyRepository keys;
    private final Json json;
    private final TransactionTemplate tx;

    Idempotency(IdempotencyRepository keys, Json json, TransactionTemplate tx) {
        this.keys = keys;
        this.json = json;
        this.tx = tx;
    }

    public ResponseEntity<Object> execute(TenantContext tenant, String key, String method, String path,
                                          Object request, Supplier<ResponseEntity<?>> action) {
        if (key == null) {
            return widen(action.get());
        }
        IdempotencyKeys.validate(key);
        byte[] hash = IdempotencyKeys.requestHash(method, path, json.write(request));
        return tx.execute(status -> {
            if (keys.claim(tenant.organizationId(), key, tenant.userId(), method, path, hash)) {
                ResponseEntity<?> response = action.get();
                keys.complete(tenant.organizationId(), key, response.getStatusCode().value(),
                        json.write(response.getBody()));
                return widen(response);
            }
            IdempotencyRepository.StoredKey stored = keys.find(tenant.organizationId(), key)
                    .orElseThrow(() -> new IllegalStateException("Claimed key vanished: " + key));
            if (!stored.sameRequest(tenant.userId(), method, path, hash)) {
                throw ApiException.unprocessable("This Idempotency-Key was already used for a different request.");
            }
            if (!stored.completed()) {
                throw ApiException.conflict("A request with this Idempotency-Key is still being processed.");
            }
            return ResponseEntity.status(stored.responseStatus())
                    .header(REPLAYED_HEADER, "true")
                    .body((Object) json.tree(stored.responseBody()));
        });
    }

    private static ResponseEntity<Object> widen(ResponseEntity<?> response) {
        return ResponseEntity.status(response.getStatusCode())
                .headers(response.getHeaders())
                .body((Object) response.getBody());
    }
}
