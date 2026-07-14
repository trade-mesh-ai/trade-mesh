package com.trademesh.intelligence;

import com.trademesh.domain.NiftyDailyPrice;
import com.trademesh.domain.NiftyDailyPriceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.mockito.Mockito.when;

/**
 * Unit tests for NiftyDailyPriceService.calculateTrendPct().
 */
@ExtendWith(MockitoExtension.class)
class NiftyDailyPriceServiceTest {

    @Mock
    private NiftyDailyPriceRepository repository;

    private NiftyDailyPriceService service;

    @BeforeEach
    void setUp() {
        service = new NiftyDailyPriceService(repository);
    }

    /**
     * Test: Positive trend (upward movement).
     * Synthetic data: 5 days of prices [100, 102, 104, 106, 108]
     * Trend = (108 - 100) / 100 * 100 = 8.0%
     */
    @Test
    void testCalculateTrendPct_PositiveTrend() {
        // Arrange: Create synthetic price series (oldest to newest)
        NiftyDailyPrice day1 = createPrice(LocalDate.of(2026, 7, 10), BigDecimal.valueOf(100.0));
        NiftyDailyPrice day2 = createPrice(LocalDate.of(2026, 7, 11), BigDecimal.valueOf(102.0));
        NiftyDailyPrice day3 = createPrice(LocalDate.of(2026, 7, 12), BigDecimal.valueOf(104.0));
        NiftyDailyPrice day4 = createPrice(LocalDate.of(2026, 7, 13), BigDecimal.valueOf(106.0));
        NiftyDailyPrice day5 = createPrice(LocalDate.of(2026, 7, 14), BigDecimal.valueOf(108.0));

        when(repository.findAll())
                .thenReturn(Flux.just(day5, day4, day3, day2, day1)); // Newest first

        // Act & Assert
        StepVerifier.create(service.calculateTrendPct(4))
                .expectNext(new BigDecimal("8.00"))
                .verifyComplete();
    }

    /**
     * Test: Negative trend (downward movement).
     * Synthetic data: 5 days of prices [100, 98, 96, 94, 92]
     * Trend = (92 - 100) / 100 * 100 = -8.0%
     */
    @Test
    void testCalculateTrendPct_NegativeTrend() {
        // Arrange
        NiftyDailyPrice day1 = createPrice(LocalDate.of(2026, 7, 10), BigDecimal.valueOf(100.0));
        NiftyDailyPrice day2 = createPrice(LocalDate.of(2026, 7, 11), BigDecimal.valueOf(98.0));
        NiftyDailyPrice day3 = createPrice(LocalDate.of(2026, 7, 12), BigDecimal.valueOf(96.0));
        NiftyDailyPrice day4 = createPrice(LocalDate.of(2026, 7, 13), BigDecimal.valueOf(94.0));
        NiftyDailyPrice day5 = createPrice(LocalDate.of(2026, 7, 14), BigDecimal.valueOf(92.0));

        when(repository.findAll())
                .thenReturn(Flux.just(day5, day4, day3, day2, day1)); // Newest first

        // Act & Assert
        StepVerifier.create(service.calculateTrendPct(4))
                .expectNext(new BigDecimal("-8.00"))
                .verifyComplete();
    }

    /**
     * Test: Zero trend (flat market).
     * Synthetic data: 3 days of prices [100, 100, 100]
     * Trend = (100 - 100) / 100 * 100 = 0.0%
     */
    @Test
    void testCalculateTrendPct_ZeroTrend() {
        // Arrange
        NiftyDailyPrice day1 = createPrice(LocalDate.of(2026, 7, 12), BigDecimal.valueOf(100.0));
        NiftyDailyPrice day2 = createPrice(LocalDate.of(2026, 7, 13), BigDecimal.valueOf(100.0));
        NiftyDailyPrice day3 = createPrice(LocalDate.of(2026, 7, 14), BigDecimal.valueOf(100.0));

        when(repository.findAll())
                .thenReturn(Flux.just(day3, day2, day1)); // Newest first

        // Act & Assert
        StepVerifier.create(service.calculateTrendPct(2))
                .expectNext(new BigDecimal("0.00"))
                .verifyComplete();
    }

    /**
     * Test: Insufficient data (less than 2 days).
     * Should return empty Mono.
     */
    @Test
    void testCalculateTrendPct_InsufficientData() {
        // Arrange
        NiftyDailyPrice day1 = createPrice(LocalDate.of(2026, 7, 14), BigDecimal.valueOf(100.0));

        when(repository.findAll())
                .thenReturn(Flux.just(day1));

        // Act & Assert
        StepVerifier.create(service.calculateTrendPct(5))
                .verifyComplete(); // No emission, empty Mono
    }

    /**
     * Test: Large positive trend.
     * Synthetic data: 2 days [100, 115]
     * Trend = (115 - 100) / 100 * 100 = 15.0%
     */
    @Test
    void testCalculateTrendPct_LargePositiveTrend() {
        // Arrange
        NiftyDailyPrice day1 = createPrice(LocalDate.of(2026, 7, 13), BigDecimal.valueOf(100.0));
        NiftyDailyPrice day2 = createPrice(LocalDate.of(2026, 7, 14), BigDecimal.valueOf(115.0));

        when(repository.findAll())
                .thenReturn(Flux.just(day2, day1)); // Newest first

        // Act & Assert
        StepVerifier.create(service.calculateTrendPct(1))
                .expectNext(new BigDecimal("15.00"))
                .verifyComplete();
    }

    /**
     * Test: Large negative trend.
     * Synthetic data: 2 days [100, 85]
     * Trend = (85 - 100) / 100 * 100 = -15.0%
     */
    @Test
    void testCalculateTrendPct_LargeNegativeTrend() {
        // Arrange
        NiftyDailyPrice day1 = createPrice(LocalDate.of(2026, 7, 13), BigDecimal.valueOf(100.0));
        NiftyDailyPrice day2 = createPrice(LocalDate.of(2026, 7, 14), BigDecimal.valueOf(85.0));

        when(repository.findAll())
                .thenReturn(Flux.just(day2, day1)); // Newest first

        // Act & Assert
        StepVerifier.create(service.calculateTrendPct(1))
                .expectNext(new BigDecimal("-15.00"))
                .verifyComplete();
    }

    // Helper method to create a NiftyDailyPrice with a specific close price
    private NiftyDailyPrice createPrice(LocalDate date, BigDecimal close) {
        NiftyDailyPrice price = new NiftyDailyPrice();
        price.setTradeDate(date);
        price.setClose(close);
        return price;
    }
}
