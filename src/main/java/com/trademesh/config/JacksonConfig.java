package com.trademesh.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Jackson configuration for JSON serialization/deserialization.
 * Provides a singleton ObjectMapper bean with proper configuration for Java time types.
 */
@Configuration
public class JacksonConfig {

    /**
     * Creates a configured ObjectMapper bean.
     * Registers JavaTimeModule for proper handling of java.time types.
     * Disables writing dates as timestamps to use ISO-8601 format.
     *
     * @return configured ObjectMapper instance
     */
    @Bean
    public ObjectMapper objectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        return mapper;
    }
}
