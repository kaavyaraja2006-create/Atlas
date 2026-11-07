package com.atlas.integrity;

import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Scheduled integrity checks for every organization with enabled rules (atlas.integrity.*). */
@Component
@EnableScheduling
@ConditionalOnProperty(name = "atlas.integrity.enabled", havingValue = "true", matchIfMissing = true)
class IntegrityScheduler {

    private static final Logger log = LoggerFactory.getLogger(IntegrityScheduler.class);

    private final IntegrityRepository repo;
    private final IntegrityService service;

    IntegrityScheduler(IntegrityRepository repo, IntegrityService service) {
        this.repo = repo;
        this.service = service;
    }

    @Scheduled(fixedDelayString = "${atlas.integrity.interval:PT1H}", initialDelayString = "${atlas.integrity.initial-delay:PT5M}")
    void checkAll() {
        for (UUID org : repo.organizationsWithRules()) {
            try {
                IntegrityService.CheckResult r = service.run(org, "SCHEDULED", null);
                if (r.opened() > 0 || r.cleared() > 0) {
                    log.info("Integrity check {}: {} finding(s), {} opened, {} cleared", org, r.findings(), r.opened(),
                            r.cleared());
                }
            } catch (RuntimeException e) {
                log.warn("Integrity check for organization {} failed", org, e);
            }
        }
    }
}
