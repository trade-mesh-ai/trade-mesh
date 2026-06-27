package com.trademesh.config;

import com.trademesh.alert.TelegramProperties;
import com.trademesh.strategy.config.StrategiesConfig;
import com.trademesh.zerodha.auth.ZerodhaConfig;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.connection.ReactiveRedisConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Test configuration that provides mock/test beans for external dependencies.
 * This configuration is loaded when running tests with @SpringBootTest.
 */
@TestConfiguration
@EnableConfigurationProperties({
        ZerodhaConfig.class,
        TelegramProperties.class,
        StrategiesConfig.class
})
public class TradeMeshTestConfiguration {

    /**
     * Provides a test ReactiveRedisConnectionFactory using Lettuce.
     * Connects to a local Redis instance (or embedded Redis if available).
     */
    @Bean
    @Primary
    public ReactiveRedisConnectionFactory testRedisConnectionFactory() {
        return new LettuceConnectionFactory();
    }

    /**
     * Provides a test ReactiveStringRedisTemplate using the test connection factory.
     */
    @Bean
    @Primary
    public ReactiveStringRedisTemplate testRedisTemplate(
            ReactiveRedisConnectionFactory connectionFactory) {
        return new ReactiveStringRedisTemplate(connectionFactory);
    }

    /**
     * Provides a test WebClient.Builder for HTTP requests.
     */
    @Bean
    @Primary
    public WebClient.Builder testWebClientBuilder() {
        return WebClient.builder();
    }
}
