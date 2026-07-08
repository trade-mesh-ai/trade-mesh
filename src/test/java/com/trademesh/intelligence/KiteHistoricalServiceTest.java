package com.trademesh.intelligence;

import com.trademesh.domain.NiftyDailyPrice;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.ArrayList;
import java.util.List;

/**
 * Unit tests for KiteHistoricalService.parseCandle() null-handling behavior.
 * <p>
 * Verifies that malformed candles (returning null from parseCandle) are correctly
 * handled by the .flatMap(candle -> Mono.justOrEmpty(parseCandle(candle))) pattern
 * in the fetchHistoricalOHLC() reactive chain.
 */
class KiteHistoricalServiceTest {

    private final KiteHistoricalService service = new KiteHistoricalService(null, null, null);

    /**
     * Test 1: Candle list with one malformed entry (< 6 elements) mixed with valid entries.
     * <p>
     * Verifies that:
     * - The malformed candle returns null from parseCandle()
     * - Mono.justOrEmpty(null) converts it to an empty Mono
     * - flatMap omits the empty Mono from the stream
     * - Valid candles are parsed successfully and emitted
     */
    @Test
    void testParseCandle_FiltersMalformedCandles() {
        // Arrange: Create a list with one malformed candle (only 3 elements) and two valid ones
        List<List<?>> candles = new ArrayList<>();

        // Valid candle 1
        List<?> validCandle1 = List.of(
                "2024-01-01 15:30:00",
                100.5,
                101.0,
                100.0,
                100.8,
                1000L
        );

        // Malformed candle (only 3 elements, will return null)
        List<?> malformedCandle = List.of(
                "2024-01-02 15:30:00",
                101.0,
                101.5
        );

        // Valid candle 2
        List<?> validCandle2 = List.of(
                "2024-01-03 15:30:00",
                101.5,
                102.0,
                101.0,
                101.8,
                1200L
        );

        candles.add(validCandle1);
        candles.add(malformedCandle);
        candles.add(validCandle2);

        // Act: Parse all candles using flatMap+justOrEmpty pattern
        Flux<NiftyDailyPrice> result = Flux.fromIterable(candles)
                .flatMap(candle -> Mono.justOrEmpty(service.parseCandle(candle)));

        // Assert: Verify that only 2 valid candles are emitted (malformed one omitted)
        StepVerifier.create(result)
                .expectNextCount(2)  // Only the 2 valid candles
                .verifyComplete();
    }

    /**
     * Test 2: Candle list where parseCandle() throws an exception internally.
     * <p>
     * Verifies that:
     * - A candle with invalid data types (non-numeric OHLC) causes parseCandle to return null
     * - Mono.justOrEmpty(null) converts it to an empty Mono
     * - flatMap omits the empty Mono from the stream
     * - Valid candles still parse successfully and are emitted
     */
    @Test
    void testParseCandle_FiltersExceptionCandles() {
        // Arrange: Create a list with one candle that will throw an exception during parsing
        List<List<?>> candles = new ArrayList<>();

        // Valid candle 1
        List<?> validCandle1 = List.of(
                "2024-01-01 15:30:00",
                100.5,
                101.0,
                100.0,
                100.8,
                1000L
        );

        // Candle with invalid data type (non-numeric OHLC) - will throw exception in parseCandle
        List<?> exceptionCandle = List.of(
                "2024-01-02 15:30:00",
                "INVALID_OPEN",  // This will cause NumberFormatException
                101.0,
                101.0,
                101.0,
                1100L
        );

        // Valid candle 2
        List<?> validCandle2 = List.of(
                "2024-01-03 15:30:00",
                101.5,
                102.0,
                101.0,
                101.8,
                1200L
        );

        candles.add(validCandle1);
        candles.add(exceptionCandle);
        candles.add(validCandle2);

        // Act: Parse all candles using flatMap+justOrEmpty pattern
        Flux<NiftyDailyPrice> result = Flux.fromIterable(candles)
                .flatMap(candle -> Mono.justOrEmpty(service.parseCandle(candle)));

        // Assert: Verify that only 2 valid candles are emitted (exception candle omitted)
        StepVerifier.create(result)
                .expectNextCount(2)  // Only the 2 valid candles
                .verifyComplete();
    }

    /**
     * Test 3: All candles are valid - verify no filtering occurs.
     * <p>
     * Verifies that when all candles are valid, all are emitted without any omission.
     */
    @Test
    void testParseCandle_AllValidCandles() {
        // Arrange: Create a list with only valid candles
        List<List<?>> candles = new ArrayList<>();

        List<?> candle1 = List.of("2024-01-01 15:30:00", 100.5, 101.0, 100.0, 100.8, 1000L);
        List<?> candle2 = List.of("2024-01-02 15:30:00", 101.0, 101.5, 100.5, 101.2, 1100L);
        List<?> candle3 = List.of("2024-01-03 15:30:00", 101.5, 102.0, 101.0, 101.8, 1200L);

        candles.add(candle1);
        candles.add(candle2);
        candles.add(candle3);

        // Act: Parse all candles using flatMap+justOrEmpty pattern
        Flux<NiftyDailyPrice> result = Flux.fromIterable(candles)
                .flatMap(candle -> Mono.justOrEmpty(service.parseCandle(candle)));

        // Assert: Verify that all 3 valid candles are emitted
        StepVerifier.create(result)
                .expectNextCount(3)  // All 3 valid candles
                .verifyComplete();
    }
}
