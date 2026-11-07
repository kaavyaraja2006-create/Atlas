package com.atlas.audit;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/** Takes anchors on a fixed delay. Does nothing unless anchoring is enabled. */
@Configuration
@EnableScheduling
class AnchorJob {

    private final Anchoring anchoring;

    AnchorJob(Anchoring anchoring) {
        this.anchoring = anchoring;
    }

    @Scheduled(initialDelayString = "PT1M", fixedDelayString = "${atlas.audit.anchor.interval:PT5M}")
    void run() {
        anchoring.anchorAll();
    }
}
