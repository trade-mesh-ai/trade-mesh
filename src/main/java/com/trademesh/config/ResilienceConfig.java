package com.trademesh.config;

import com.trademesh.common.resilience.CircuitBreaker;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Configures application-wide resilience components.
 */
@Configuration
public class ResilienceConfig {

    @Bean
    public CircuitBreaker orderCircuitBreaker() {
        return new CircuitBreaker("orders", 5, 2, 30);
    }

}
