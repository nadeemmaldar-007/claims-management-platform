package com.apac.claims.config;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The single source of "now". Code reads time through this bean instead of calling {@code Instant.now()}, so a
 * test can replace it with a fixed or adjustable clock.
 */
@Configuration
public class ClockConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
