package com.trademesh.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.trademesh.strategy.TradeLeg;
import io.r2dbc.postgresql.codec.Json;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.convert.converter.Converter;
import org.springframework.data.convert.ReadingConverter;
import org.springframework.data.convert.WritingConverter;

import java.util.List;

/**
 * R2DBC converters for PaperTrade legs field (JSONB ↔ List<TradeLeg>).
 * <p>
 * Handles automatic conversion between PostgreSQL JSONB and Java List<TradeLeg>
 * during R2DBC read/write operations.
 */
@Slf4j
public class PaperTradeLegsConverter {

    /**
     * Converts List<TradeLeg> to PostgreSQL JSONB format.
     * Used when writing PaperTrade entities to the database.
     */
    @WritingConverter
    @RequiredArgsConstructor
    public static class PaperTradeLegsWritingConverter implements Converter<List<TradeLeg>, Json> {

        private final ObjectMapper objectMapper;

        @Override
        public Json convert(List<TradeLeg> source) {
            if (source == null) {
                return null;
            }
            try {
                String jsonString = objectMapper.writeValueAsString(source);
                return Json.of(jsonString);
            } catch (Exception e) {
                log.error("Error serializing trade legs to JSON", e);
                return Json.of("[]");
            }
        }
    }

    /**
     * Converts PostgreSQL JSONB to List<TradeLeg>.
     * Used when reading PaperTrade entities from the database.
     */
    @ReadingConverter
    @RequiredArgsConstructor
    public static class PaperTradeLegsReadingConverter implements Converter<Json, List<TradeLeg>> {

        private final ObjectMapper objectMapper;

        @Override
        public List<TradeLeg> convert(Json source) {
            if (source == null) {
                return null;
            }
            try {
                String jsonString = source.asString();
                return objectMapper.readValue(
                        jsonString,
                        objectMapper.getTypeFactory().constructCollectionType(List.class, TradeLeg.class)
                );
            } catch (Exception e) {
                log.error("Error deserializing trade legs from JSON", e);
                return List.of();
            }
        }
    }
}
