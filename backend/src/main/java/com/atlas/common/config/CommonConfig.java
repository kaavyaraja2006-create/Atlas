package com.atlas.common.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class CommonConfig {

    /** Injected everywhere "now" matters, so tests can control time. */
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
