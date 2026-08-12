package com.trademesh.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.ZoneId;

/**
 * Spring configuration for Clock bean.
 * Provides a system clock in Asia/Kolkata timezone for dependency injection.
 */
@Configuration
public class ClockConfig {

    /**
     * Provides a system clock in Asia/Kolkata timezone.
     * Used by strategies and services that need to read the current time.
     *
     * @return Clock configured for Asia/Kolkata timezone
     */
    @Bean
    public Clock clock() {
        return Clock.system(ZoneId.of("Asia/Kolkata"));
    }
}
