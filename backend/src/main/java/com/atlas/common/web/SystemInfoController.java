package com.atlas.common.web;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationInfoService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Reports the live schema version straight from Flyway's history table, so
 * "which migrations are applied" is read from the database, not hardcoded.
 * Phase 2 restricts this endpoint to platform administrators.
 */
@RestController
@RequestMapping("/api/v1/system")
class SystemInfoController {

    private final Flyway flyway;

    SystemInfoController(Flyway flyway) {
        this.flyway = flyway;
    }

    @GetMapping("/info")
    SystemInfo info() {
        MigrationInfoService migrations = flyway.info();
        MigrationInfo current = migrations.current();
        return new SystemInfo(
                "ATLAS",
                current == null ? null : current.getVersion().getVersion(),
                migrations.pending().length);
    }

    record SystemInfo(String application, String schemaVersion, int pendingMigrations) {
    }
}
