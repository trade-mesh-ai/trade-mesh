package com.trademesh.config;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Custom Jackson serializer for BigDecimal fields.
 * <p>
 * Ensures all BigDecimal values are serialized with exactly 2 decimal places,
 * regardless of the source database column scale (e.g., NUMERIC(18,4)).
 * <p>
 * Usage: Apply @JsonSerialize(using = BigDecimalTwoDecimalSerializer.class)
 * to any BigDecimal field that should serialize with 2-decimal precision.
 */
public class BigDecimalTwoDecimalSerializer extends JsonSerializer<BigDecimal> {

    /**
     * Serializes a BigDecimal value with exactly 2 decimal places.
     * <p>
     * Rounds the value to 2 decimal places using HALF_UP rounding mode,
     * then writes it as a JSON number.
     *
     * @param value the BigDecimal value to serialize
     * @param gen the JsonGenerator to write to
     * @param serializers the SerializerProvider (unused)
     * @throws IOException if an I/O error occurs during serialization
     */
    @Override
    public void serialize(BigDecimal value, JsonGenerator gen, SerializerProvider serializers) throws IOException {
        if (value == null) {
            gen.writeNull();
        } else {
            gen.writeNumber(value.setScale(2, RoundingMode.HALF_UP));
        }
    }
}
