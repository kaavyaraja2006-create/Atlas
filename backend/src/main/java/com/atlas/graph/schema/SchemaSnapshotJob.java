package com.atlas.graph.schema;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Stores a schema snapshot at start-up (after Flyway has migrated) when the structure is new.
 * A failure is logged and does not stop the application: the explorer still reads the live
 * schema, it just has one version fewer to compare against.
 */
@Component
class SchemaSnapshotJob {

    private static final Logger log = LoggerFactory.getLogger(SchemaSnapshotJob.class);

    private final SchemaService service;

    SchemaSnapshotJob(SchemaService service) {
        this.service = service;
    }

    @EventListener(ApplicationReadyEvent.class)
    void capture() {
        try {
            service.captureSnapshot().ifPresentOrElse(
                    id -> log.info("Stored schema snapshot {}", id),
                    () -> log.debug("Schema unchanged since the last snapshot"));
        } catch (RuntimeException e) {
            log.warn("Could not store a schema snapshot", e);
        }
    }
}
