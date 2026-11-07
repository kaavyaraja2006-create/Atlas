package com.atlas.graph.schema;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** ER Schema Explorer API. Not tenant-scoped: the schema is shared; access is platform-admin only. */
@RestController
@RequestMapping("/api/v1/graph/schema")
class SchemaController {

    record ExportRequest(String format, String version, int tables) {
    }

    private final SchemaService service;

    SchemaController(SchemaService service) {
        this.service = service;
    }

    @GetMapping
    SchemaService.LiveSchema live(@AuthenticationPrincipal Jwt jwt) {
        return service.live(UUID.fromString(jwt.getSubject()));
    }

    @GetMapping("/versions")
    List<SchemaSnapshotRepository.Snapshot> versions(@AuthenticationPrincipal Jwt jwt) {
        return service.versions(UUID.fromString(jwt.getSubject()));
    }

    @GetMapping("/diff")
    SchemaService.DiffView diff(@AuthenticationPrincipal Jwt jwt, @RequestParam String from,
                                @RequestParam(defaultValue = "live") String to) {
        return service.diff(UUID.fromString(jwt.getSubject()), from, to);
    }

    @PostMapping("/exports")
    @ResponseStatus(HttpStatus.CREATED)
    Map<String, UUID> export(@AuthenticationPrincipal Jwt jwt, @RequestBody ExportRequest request) {
        return Map.of("auditEventId", service.recordExport(UUID.fromString(jwt.getSubject()), request.format(),
                request.version(), request.tables()));
    }
}
