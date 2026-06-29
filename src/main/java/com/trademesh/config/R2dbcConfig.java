package com.trademesh.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.r2dbc.spi.ConnectionFactory;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.r2dbc.convert.R2dbcCustomConversions;
import org.springframework.data.r2dbc.dialect.PostgresDialect;

import java.util.List;

/**
 * R2DBC configuration for custom type conversions.
 * <p>
 * Registers custom converters for complex types that need special handling
 * during R2DBC read/write operations (e.g., JSONB fields).
 */
@Configuration
@RequiredArgsConstructor
public class R2dbcConfig {

    private final ObjectMapper objectMapper;

    /**
     * Registers custom R2DBC converters.
     * <p>
     * Currently registers:
     * - PaperTradeLegsWritingConverter: List<TradeLeg> → JSONB
     * - PaperTradeLegsReadingConverter: JSONB → List<TradeLeg>
     *
     * @return R2dbcCustomConversions bean with registered converters
     */
    @Bean
    public R2dbcCustomConversions r2dbcCustomConversions(ConnectionFactory connectionFactory) {
        List<Object> converters = List.of(
                new PaperTradeLegsConverter.PaperTradeLegsWritingConverter(objectMapper),
                new PaperTradeLegsConverter.PaperTradeLegsReadingConverter(objectMapper)
        );
        return R2dbcCustomConversions.of(PostgresDialect.INSTANCE, converters);
    }
}
