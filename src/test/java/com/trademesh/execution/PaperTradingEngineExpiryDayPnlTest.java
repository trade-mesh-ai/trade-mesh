package com.trademesh.execution;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.trademesh.strategy.TradeLeg;
import com.trademesh.strategy.enums.Action;
import com.trademesh.strategy.enums.OptionType;
import com.trademesh.zerodha.options.OptionChain;
import com.trademesh.zerodha.options.OptionChainService;
import com.trademesh.zerodha.options.OptionStrike;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import reactor.test.StepVerifier;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.when;

/**
 * Unit tests for calculateExpiryDayStranglePnl() with 4-leg hedged structure.
 * <p>
 * Tests the P&L calculation for Expiry Day Strangle with hedge legs:
 * - SELL CE (short call)
 * - SELL PE (short put)
 * - BUY CE (call hedge, farther OTM)
 * - BUY PE (put hedge, farther OTM)
 * <p>
 * Validates:
 * - SELL legs: profit on decay (entry LTP > current LTP)
 * - BUY legs: profit on appreciation (current LTP > entry LTP)
 * - Sign correctness: BUY legs use opposite sign from SELL legs
 * - Summed P&L across all 4 legs matches hand-calculated expectation
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PaperTradingEngine Expiry Day Strangle 4-Leg P&L Tests")
class PaperTradingEngineExpiryDayPnlTest {

    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
    @Mock
    private PaperTradeRepository paperTradeRepository;
    @Mock
    private ReactiveRedisTemplate<String, String> redisTemplate;
    @Mock
    private OptionChainService optionChainService;
    @Mock
    private com.trademesh.zerodha.options.KiteMarginService kiteMarginService;
    @Mock
    private com.trademesh.zerodha.auth.ZerodhaAuthService authService;
    private PaperTradingEngine engine;

    @BeforeEach
    void setUp() {
        engine = new PaperTradingEngine(
                paperTradeRepository, redisTemplate, null, objectMapper,
                null, null, null, null, optionChainService,
                kiteMarginService, authService
        );
    }

    /**
     * Test: 4-leg P&L with profitable SELL legs and profitable BUY hedge legs.
     * <p>
     * Setup:
     * - SELL CE @ 23950: entry 120.0, current 80.0 → profit = (120.0 - 80.0) × 1 = 40.0
     * - SELL PE @ 23850: entry 90.0, current 50.0 → profit = (90.0 - 50.0) × 1 = 40.0
     * - BUY CE @ 24050: entry 30.0, current 50.0 → profit = (50.0 - 30.0) × 1 = 20.0
     * - BUY PE @ 23750: entry 20.0, current 40.0 → profit = (40.0 - 20.0) × 1 = 20.0
     * <p>
     * Expected total P&L = 40.0 + 40.0 + 20.0 + 20.0 = 120.0
     */
    @Test
    @DisplayName("calculateExpiryDayStranglePnl: All 4 legs profitable (SELL decay + BUY appreciation)")
    void testCalculateExpiryDayStranglePnl_AllLegsProfit() {
        // Arrange
        PaperTrade trade = new PaperTrade();
        trade.setId(UUID.randomUUID());
        trade.setSymbol("NIFTY50");
        trade.setStrategyName("EXPIRY_DAY_STRANGLE");

        // 4 legs: 2 SELL (short strangle) + 2 BUY (hedge)
        List<TradeLeg> legs = List.of(
                // SELL CE @ 23950: entry 120.0
                TradeLeg.builder()
                        .action(Action.SELL)
                        .optionType(OptionType.CE)
                        .strikePrice(BigDecimal.valueOf(23950.0))
                        .limitPrice(BigDecimal.valueOf(120.0))
                        .quantity(1)
                        .expiryDate(LocalDate.now().plusDays(7))
                        .build(),
                // SELL PE @ 23850: entry 90.0
                TradeLeg.builder()
                        .action(Action.SELL)
                        .optionType(OptionType.PE)
                        .strikePrice(BigDecimal.valueOf(23850.0))
                        .limitPrice(BigDecimal.valueOf(90.0))
                        .quantity(1)
                        .expiryDate(LocalDate.now().plusDays(7))
                        .build(),
                // BUY CE @ 24050: entry 30.0
                TradeLeg.builder()
                        .action(Action.BUY)
                        .optionType(OptionType.CE)
                        .strikePrice(BigDecimal.valueOf(24050.0))
                        .limitPrice(BigDecimal.valueOf(30.0))
                        .quantity(1)
                        .expiryDate(LocalDate.now().plusDays(7))
                        .build(),
                // BUY PE @ 23750: entry 20.0
                TradeLeg.builder()
                        .action(Action.BUY)
                        .optionType(OptionType.PE)
                        .strikePrice(BigDecimal.valueOf(23750.0))
                        .limitPrice(BigDecimal.valueOf(20.0))
                        .quantity(1)
                        .expiryDate(LocalDate.now().plusDays(7))
                        .build()
        );
        trade.setLegs(legs);

        // Mock option chain with current LTPs
        OptionChain optionChain = OptionChain.builder()
                .symbol("NIFTY50")
                .expiryDate(LocalDate.now().plusDays(7))
                .strikes(List.of(
                        OptionStrike.builder()
                                .strikePrice(BigDecimal.valueOf(23950))
                                .callLTP(BigDecimal.valueOf(80.0))  // SELL CE current
                                .build(),
                        OptionStrike.builder()
                                .strikePrice(BigDecimal.valueOf(23850))
                                .putLTP(BigDecimal.valueOf(50.0))   // SELL PE current
                                .build(),
                        OptionStrike.builder()
                                .strikePrice(BigDecimal.valueOf(24050))
                                .callLTP(BigDecimal.valueOf(50.0))  // BUY CE current
                                .build(),
                        OptionStrike.builder()
                                .strikePrice(BigDecimal.valueOf(23750))
                                .putLTP(BigDecimal.valueOf(40.0))   // BUY PE current
                                .build()
                ))
                .build();

        when(optionChainService.fetchOptionChain("NIFTY50"))
                .thenReturn(reactor.core.publisher.Mono.just(optionChain));

        // Act & Assert
        // Expected: SELL CE (120-80)×1 + SELL PE (90-50)×1 + BUY CE (50-30)×1 + BUY PE (40-20)×1
        //         = 40 + 40 + 20 + 20 = 120.0
        StepVerifier.create(engine.calculateExpiryDayStranglePnl(trade))
                .expectNextMatches(pnl ->
                        pnl.compareTo(BigDecimal.valueOf(120.0)) == 0
                )
                .verifyComplete();
    }

    /**
     * Test: 4-leg P&L with profitable SELL legs but losing BUY hedge legs.
     * <p>
     * Setup:
     * - SELL CE @ 23950: entry 120.0, current 80.0 → profit = (120.0 - 80.0) × 1 = 40.0
     * - SELL PE @ 23850: entry 90.0, current 50.0 → profit = (90.0 - 50.0) × 1 = 40.0
     * - BUY CE @ 24050: entry 50.0, current 30.0 → loss = (30.0 - 50.0) × 1 = -20.0
     * - BUY PE @ 23750: entry 40.0, current 20.0 → loss = (20.0 - 40.0) × 1 = -20.0
     * <p>
     * Expected total P&L = 40.0 + 40.0 - 20.0 - 20.0 = 40.0
     * <p>
     * This test confirms that BUY legs use the opposite sign (current - entry) from SELL legs,
     * and that losses on hedge legs correctly reduce overall P&L.
     */
    @Test
    @DisplayName("calculateExpiryDayStranglePnl: SELL legs profit, BUY hedge legs loss (sign correctness)")
    void testCalculateExpiryDayStranglePnl_SellProfitBuyLoss() {
        // Arrange
        PaperTrade trade = new PaperTrade();
        trade.setId(UUID.randomUUID());
        trade.setSymbol("NIFTY50");
        trade.setStrategyName("EXPIRY_DAY_STRANGLE");

        // 4 legs: 2 SELL (short strangle) + 2 BUY (hedge)
        List<TradeLeg> legs = List.of(
                // SELL CE @ 23950: entry 120.0
                TradeLeg.builder()
                        .action(Action.SELL)
                        .optionType(OptionType.CE)
                        .strikePrice(BigDecimal.valueOf(23950.0))
                        .limitPrice(BigDecimal.valueOf(120.0))
                        .quantity(1)
                        .expiryDate(LocalDate.now().plusDays(7))
                        .build(),
                // SELL PE @ 23850: entry 90.0
                TradeLeg.builder()
                        .action(Action.SELL)
                        .optionType(OptionType.PE)
                        .strikePrice(BigDecimal.valueOf(23850.0))
                        .limitPrice(BigDecimal.valueOf(90.0))
                        .quantity(1)
                        .expiryDate(LocalDate.now().plusDays(7))
                        .build(),
                // BUY CE @ 24050: entry 50.0 (higher entry than current)
                TradeLeg.builder()
                        .action(Action.BUY)
                        .optionType(OptionType.CE)
                        .strikePrice(BigDecimal.valueOf(24050.0))
                        .limitPrice(BigDecimal.valueOf(50.0))
                        .quantity(1)
                        .expiryDate(LocalDate.now().plusDays(7))
                        .build(),
                // BUY PE @ 23750: entry 40.0 (higher entry than current)
                TradeLeg.builder()
                        .action(Action.BUY)
                        .optionType(OptionType.PE)
                        .strikePrice(BigDecimal.valueOf(23750.0))
                        .limitPrice(BigDecimal.valueOf(40.0))
                        .quantity(1)
                        .expiryDate(LocalDate.now().plusDays(7))
                        .build()
        );
        trade.setLegs(legs);

        // Mock option chain with current LTPs (lower than BUY entry prices)
        OptionChain optionChain = OptionChain.builder()
                .symbol("NIFTY50")
                .expiryDate(LocalDate.now().plusDays(7))
                .strikes(List.of(
                        OptionStrike.builder()
                                .strikePrice(BigDecimal.valueOf(23950))
                                .callLTP(BigDecimal.valueOf(80.0))  // SELL CE current
                                .build(),
                        OptionStrike.builder()
                                .strikePrice(BigDecimal.valueOf(23850))
                                .putLTP(BigDecimal.valueOf(50.0))   // SELL PE current
                                .build(),
                        OptionStrike.builder()
                                .strikePrice(BigDecimal.valueOf(24050))
                                .callLTP(BigDecimal.valueOf(30.0))  // BUY CE current (loss)
                                .build(),
                        OptionStrike.builder()
                                .strikePrice(BigDecimal.valueOf(23750))
                                .putLTP(BigDecimal.valueOf(20.0))   // BUY PE current (loss)
                                .build()
                ))
                .build();

        when(optionChainService.fetchOptionChain("NIFTY50"))
                .thenReturn(reactor.core.publisher.Mono.just(optionChain));

        // Act & Assert
        // Expected: SELL CE (120-80)×1 + SELL PE (90-50)×1 + BUY CE (30-50)×1 + BUY PE (20-40)×1
        //         = 40 + 40 - 20 - 20 = 40.0
        StepVerifier.create(engine.calculateExpiryDayStranglePnl(trade))
                .expectNextMatches(pnl ->
                        pnl.compareTo(BigDecimal.valueOf(40.0)) == 0
                )
                .verifyComplete();
    }

    /**
     * Test: 4-leg P&L with mixed outcomes (some legs profit, some lose).
     * <p>
     * Setup:
     * - SELL CE @ 23950: entry 120.0, current 100.0 → profit = (120.0 - 100.0) × 1 = 20.0
     * - SELL PE @ 23850: entry 90.0, current 110.0 → loss = (90.0 - 110.0) × 1 = -20.0
     * - BUY CE @ 24050: entry 30.0, current 40.0 → profit = (40.0 - 30.0) × 1 = 10.0
     * - BUY PE @ 23750: entry 20.0, current 15.0 → loss = (15.0 - 20.0) × 1 = -5.0
     * <p>
     * Expected total P&L = 20.0 - 20.0 + 10.0 - 5.0 = 5.0
     */
    @Test
    @DisplayName("calculateExpiryDayStranglePnl: Mixed outcomes (some profit, some loss)")
    void testCalculateExpiryDayStranglePnl_MixedOutcomes() {
        // Arrange
        PaperTrade trade = new PaperTrade();
        trade.setId(UUID.randomUUID());
        trade.setSymbol("NIFTY50");
        trade.setStrategyName("EXPIRY_DAY_STRANGLE");

        // 4 legs: 2 SELL (short strangle) + 2 BUY (hedge)
        List<TradeLeg> legs = List.of(
                // SELL CE @ 23950: entry 120.0, current 100.0 (profit)
                TradeLeg.builder()
                        .action(Action.SELL)
                        .optionType(OptionType.CE)
                        .strikePrice(BigDecimal.valueOf(23950.0))
                        .limitPrice(BigDecimal.valueOf(120.0))
                        .quantity(1)
                        .expiryDate(LocalDate.now().plusDays(7))
                        .build(),
                // SELL PE @ 23850: entry 90.0, current 110.0 (loss)
                TradeLeg.builder()
                        .action(Action.SELL)
                        .optionType(OptionType.PE)
                        .strikePrice(BigDecimal.valueOf(23850.0))
                        .limitPrice(BigDecimal.valueOf(90.0))
                        .quantity(1)
                        .expiryDate(LocalDate.now().plusDays(7))
                        .build(),
                // BUY CE @ 24050: entry 30.0, current 40.0 (profit)
                TradeLeg.builder()
                        .action(Action.BUY)
                        .optionType(OptionType.CE)
                        .strikePrice(BigDecimal.valueOf(24050.0))
                        .limitPrice(BigDecimal.valueOf(30.0))
                        .quantity(1)
                        .expiryDate(LocalDate.now().plusDays(7))
                        .build(),
                // BUY PE @ 23750: entry 20.0, current 15.0 (loss)
                TradeLeg.builder()
                        .action(Action.BUY)
                        .optionType(OptionType.PE)
                        .strikePrice(BigDecimal.valueOf(23750.0))
                        .limitPrice(BigDecimal.valueOf(20.0))
                        .quantity(1)
                        .expiryDate(LocalDate.now().plusDays(7))
                        .build()
        );
        trade.setLegs(legs);

        // Mock option chain with mixed current LTPs
        OptionChain optionChain = OptionChain.builder()
                .symbol("NIFTY50")
                .expiryDate(LocalDate.now().plusDays(7))
                .strikes(List.of(
                        OptionStrike.builder()
                                .strikePrice(BigDecimal.valueOf(23950))
                                .callLTP(BigDecimal.valueOf(100.0))  // SELL CE current
                                .build(),
                        OptionStrike.builder()
                                .strikePrice(BigDecimal.valueOf(23850))
                                .putLTP(BigDecimal.valueOf(110.0))   // SELL PE current
                                .build(),
                        OptionStrike.builder()
                                .strikePrice(BigDecimal.valueOf(24050))
                                .callLTP(BigDecimal.valueOf(40.0))   // BUY CE current
                                .build(),
                        OptionStrike.builder()
                                .strikePrice(BigDecimal.valueOf(23750))
                                .putLTP(BigDecimal.valueOf(15.0))    // BUY PE current
                                .build()
                ))
                .build();

        when(optionChainService.fetchOptionChain("NIFTY50"))
                .thenReturn(reactor.core.publisher.Mono.just(optionChain));

        // Act & Assert
        // Expected: SELL CE (120-100)×1 + SELL PE (90-110)×1 + BUY CE (40-30)×1 + BUY PE (15-20)×1
        //         = 20 - 20 + 10 - 5 = 5.0
        StepVerifier.create(engine.calculateExpiryDayStranglePnl(trade))
                .expectNextMatches(pnl ->
                        pnl.compareTo(BigDecimal.valueOf(5.0)) == 0
                )
                .verifyComplete();
    }
}
