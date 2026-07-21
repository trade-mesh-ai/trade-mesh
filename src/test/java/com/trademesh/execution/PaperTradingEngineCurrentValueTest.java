package com.trademesh.execution;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.trademesh.strategy.TradeLeg;
import com.trademesh.strategy.enums.Action;
import com.trademesh.strategy.enums.OptionType;
import com.trademesh.zerodha.options.OptionChain;
import com.trademesh.zerodha.options.OptionStrike;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.data.redis.core.ReactiveValueOperations;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.mockito.Mockito.*;

/**
 * Unit tests for P&L netting fix in PaperTradingEngine.
 * Tests the signed logic for calculateSpreadCurrentValue(), calculateIronCondorCurrentValue(),
 * and calculateDefaultCurrentValue() methods.
 * <p>
 * The fix ensures that:
 * - SELL legs: ADD their LTP (cost to buy back the short position)
 * - BUY legs: SUBTRACT their LTP (value received when selling back the long position)
 * <p>
 * This prevents the buggy behavior of summing all LTPs without considering leg direction.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PaperTradingEngine P&L Netting Fix Tests")
class PaperTradingEngineCurrentValueTest {

    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
    @Mock
    private PaperTradeRepository paperTradeRepository;
    @Mock
    private ReactiveRedisTemplate<String, String> redisTemplate;
    @Mock
    private com.trademesh.zerodha.options.OptionChainService optionChainService;
    private PaperTradingEngine paperTradingEngine;

    private OptionChain optionChain;

    @BeforeEach
    void setUp() {
        paperTradingEngine = new PaperTradingEngine(
                paperTradeRepository, redisTemplate, null, objectMapper,
                null, null, null, null, optionChainService
        );
        optionChain = OptionChain.builder()
                .symbol("NIFTY50")
                .expiryDate(LocalDate.of(2026, 7, 9))
                .build();
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // FALLBACK BRANCH TESTS FOR calculateIronCondorPnl()
    // ═══════════════════════════════════════════════════════════════════════════════

    /**
     * Test: calculateIronCondorPnl() - No legs fallback.
     * Verifies that when trade has no legs (null or empty), the method returns
     * BigDecimal.valueOf(trade.getUnrealizedPnl()) instead of ZERO.
     */
    @Test
    @DisplayName("calculateIronCondorPnl: No legs (null) falls back to unrealizedPnl")
    void testCalculateIronCondorPnl_NoLegsNull_FallsBackToUnrealizedPnl() {
        // Arrange
        PaperTrade trade = new PaperTrade();
        trade.setId(java.util.UUID.randomUUID());
        trade.setSymbol("NIFTY50");
        trade.setLegs(null);
        trade.setUnrealizedPnl(BigDecimal.valueOf(-14.9));

        // Act & Assert
        StepVerifier.create(paperTradingEngine.calculateIronCondorPnl(trade))
                .expectNextMatches(bd -> bd.compareTo(BigDecimal.valueOf(-14.9)) == 0)
                .verifyComplete();
    }

    /**
     * Test: calculateIronCondorPnl() - Empty legs fallback.
     * Verifies that when trade has empty legs list, the method returns
     * BigDecimal.valueOf(trade.getUnrealizedPnl()) instead of ZERO.
     */
    @Test
    @DisplayName("calculateIronCondorPnl: Empty legs falls back to unrealizedPnl")
    void testCalculateIronCondorPnl_EmptyLegs_FallsBackToUnrealizedPnl() {
        // Arrange
        PaperTrade trade = new PaperTrade();
        trade.setId(java.util.UUID.randomUUID());
        trade.setSymbol("NIFTY50");
        trade.setLegs(List.of());
        trade.setUnrealizedPnl(BigDecimal.valueOf(-14.9));

        // Act & Assert
        StepVerifier.create(paperTradingEngine.calculateIronCondorPnl(trade))
                .expectNextMatches(bd -> bd.compareTo(BigDecimal.valueOf(-14.9)) == 0)
                .verifyComplete();
    }

    /**
     * Test: calculateIronCondorPnl() - Redis key missing fallback.
     * Verifies that when Redis key is missing (Mono.empty()), the method returns
     * BigDecimal.valueOf(trade.getUnrealizedPnl()) instead of ZERO.
     */
    @Test
    @DisplayName("calculateIronCondorPnl: Redis key missing falls back to unrealizedPnl")
    void testCalculateIronCondorPnl_RedisKeyMissing_FallsBackToUnrealizedPnl() {
        // Arrange
        PaperTrade trade = new PaperTrade();
        trade.setId(java.util.UUID.randomUUID());
        trade.setSymbol("NIFTY50");
        trade.setLegs(List.of(
                TradeLeg.builder().action(Action.SELL).optionType(OptionType.CE)
                        .strikePrice(BigDecimal.valueOf(24100.0)).limitPrice(BigDecimal.valueOf(50.0)).quantity(1).build()
        ));
        trade.setUnrealizedPnl(BigDecimal.valueOf(-14.9));

        mockRedisOptionChain("NIFTY50", null); // null = Mono.empty()

        // Act & Assert
        StepVerifier.create(paperTradingEngine.calculateIronCondorPnl(trade))
                .expectNextMatches(bd -> bd.compareTo(BigDecimal.valueOf(-14.9)) == 0)
                .verifyComplete();
    }

    /**
     * Test: calculateIronCondorPnl() - JSON deserialization failure fallback.
     * Verifies that when Redis returns malformed JSON, the method catches the
     * deserialization exception and returns BigDecimal.valueOf(trade.getUnrealizedPnl()).
     */
    @Test
    @DisplayName("calculateIronCondorPnl: JSON deserialization failure falls back to unrealizedPnl")
    void testCalculateIronCondorPnl_JsonDeserializationFailure_FallsBackToUnrealizedPnl() {
        // Arrange
        PaperTrade trade = new PaperTrade();
        trade.setId(java.util.UUID.randomUUID());
        trade.setSymbol("NIFTY50");
        trade.setLegs(List.of(
                TradeLeg.builder().action(Action.SELL).optionType(OptionType.CE)
                        .strikePrice(BigDecimal.valueOf(24100.0)).limitPrice(BigDecimal.valueOf(50.0)).quantity(1).build()
        ));
        trade.setUnrealizedPnl(BigDecimal.valueOf(-14.9));

        mockRedisOptionChain("NIFTY50", "{not valid json"); // Malformed JSON

        // Act & Assert
        StepVerifier.create(paperTradingEngine.calculateIronCondorPnl(trade))
                .expectNextMatches(bd -> bd.compareTo(BigDecimal.valueOf(-14.9)) == 0)
                .verifyComplete();
    }

    /**
     * Test: calculateIronCondorPnl() - Successful path with valid Redis data.
     * Verifies that when Redis returns valid option chain data, the method
     * correctly calculates P&L (not falling back), proving fallback logic
     * doesn't interfere with normal operation.
     */
    @Test
    @DisplayName("calculateIronCondorPnl: Valid Redis data calculates correct P&L")
    void testCalculateIronCondorPnl_ValidRedisData_CalculatesCorrectPnl() throws Exception {
        // Arrange
        PaperTrade trade = new PaperTrade();
        trade.setId(java.util.UUID.randomUUID());
        trade.setSymbol("NIFTY50");
        trade.setUnrealizedPnl(BigDecimal.valueOf(0.0));

        // 2 legs: SELL @ 50 (entry), current LTP 40 → profit 10
        //         BUY @ 30 (entry), current LTP 35 → profit 5
        // Total P&L = 10 + 5 = 15
        List<TradeLeg> legs = List.of(
                TradeLeg.builder().action(Action.SELL).optionType(OptionType.CE)
                        .strikePrice(BigDecimal.valueOf(24100.0)).limitPrice(BigDecimal.valueOf(50.0)).quantity(1).build(),
                TradeLeg.builder().action(Action.BUY).optionType(OptionType.CE)
                        .strikePrice(BigDecimal.valueOf(24200.0)).limitPrice(BigDecimal.valueOf(30.0)).quantity(1).build()
        );
        trade.setLegs(legs);

        // Create valid option chain with matching strikes and LTPs
        OptionChain chainData = OptionChain.builder()
                .symbol("NIFTY50")
                .expiryDate(LocalDate.of(2026, 7, 9))
                .strikes(List.of(
                        OptionStrike.builder().strikePrice(BigDecimal.valueOf(24100))
                                .callLTP(BigDecimal.valueOf(40.0)).build(),
                        OptionStrike.builder().strikePrice(BigDecimal.valueOf(24200))
                                .callLTP(BigDecimal.valueOf(35.0)).build()
                ))
                .build();

        when(optionChainService.fetchOptionChain("NIFTY50"))
                .thenReturn(Mono.just(chainData));

        // Act & Assert
        // Expected P&L: SELL leg (50 - 40) * 1 = 10, BUY leg (35 - 30) * 1 = 5, total = 15
        StepVerifier.create(paperTradingEngine.calculateIronCondorPnl(trade))
                .expectNextMatches(bd -> bd.compareTo(BigDecimal.valueOf(15.0)) == 0)
                .verifyComplete();
        verify(optionChainService, atLeastOnce()).fetchOptionChain("NIFTY50");
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // FALLBACK BRANCH TESTS FOR calculateSpreadPnl()
    // ═══════════════════════════════════════════════════════════════════════════════

    /**
     * Test: calculateSpreadPnl() - No legs fallback.
     * Verifies that when trade has no legs (null or empty), the method returns
     * BigDecimal.valueOf(trade.getUnrealizedPnl()) instead of ZERO.
     */
    @Test
    @DisplayName("calculateSpreadPnl: No legs (null) falls back to unrealizedPnl")
    void testCalculateSpreadPnl_NoLegsNull_FallsBackToUnrealizedPnl() {
        // Arrange
        PaperTrade trade = new PaperTrade();
        trade.setId(java.util.UUID.randomUUID());
        trade.setSymbol("NIFTY50");
        trade.setLegs(null);
        trade.setUnrealizedPnl(BigDecimal.valueOf(-14.9));

        // Act & Assert
        StepVerifier.create(paperTradingEngine.calculateSpreadPnl(trade))
                .expectNextMatches(bd -> bd.compareTo(BigDecimal.valueOf(-14.9)) == 0)
                .verifyComplete();
    }

    /**
     * Test: calculateSpreadPnl() - Empty legs fallback.
     * Verifies that when trade has empty legs list, the method returns
     * BigDecimal.valueOf(trade.getUnrealizedPnl()) instead of ZERO.
     */
    @Test
    @DisplayName("calculateSpreadPnl: Empty legs falls back to unrealizedPnl")
    void testCalculateSpreadPnl_EmptyLegs_FallsBackToUnrealizedPnl() {
        // Arrange
        PaperTrade trade = new PaperTrade();
        trade.setId(java.util.UUID.randomUUID());
        trade.setSymbol("NIFTY50");
        trade.setLegs(List.of());
        trade.setUnrealizedPnl(BigDecimal.valueOf(-14.9));

        // Act & Assert
        StepVerifier.create(paperTradingEngine.calculateSpreadPnl(trade))
                .expectNextMatches(bd -> bd.compareTo(BigDecimal.valueOf(-14.9)) == 0)
                .verifyComplete();
    }

    /**
     * Test: calculateSpreadPnl() - Redis key missing fallback.
     * Verifies that when Redis key is missing (Mono.empty()), the method returns
     * BigDecimal.valueOf(trade.getUnrealizedPnl()) instead of ZERO.
     */
    @Test
    @DisplayName("calculateSpreadPnl: Redis key missing falls back to unrealizedPnl")
    void testCalculateSpreadPnl_RedisKeyMissing_FallsBackToUnrealizedPnl() {
        // Arrange
        PaperTrade trade = new PaperTrade();
        trade.setId(java.util.UUID.randomUUID());
        trade.setSymbol("NIFTY50");
        trade.setLegs(List.of(
                TradeLeg.builder().action(Action.SELL).optionType(OptionType.PE)
                        .strikePrice(BigDecimal.valueOf(23900.0)).limitPrice(BigDecimal.valueOf(50.0)).quantity(1).build()
        ));
        trade.setUnrealizedPnl(BigDecimal.valueOf(-14.9));

        mockRedisOptionChain("NIFTY50", null); // null = Mono.empty()

        // Act & Assert
        StepVerifier.create(paperTradingEngine.calculateSpreadPnl(trade))
                .expectNextMatches(bd -> bd.compareTo(BigDecimal.valueOf(-14.9)) == 0)
                .verifyComplete();
    }

    /**
     * Test: calculateSpreadPnl() - JSON deserialization failure fallback.
     * Verifies that when Redis returns malformed JSON, the method catches the
     * deserialization exception and returns BigDecimal.valueOf(trade.getUnrealizedPnl()).
     */
    @Test
    @DisplayName("calculateSpreadPnl: JSON deserialization failure falls back to unrealizedPnl")
    void testCalculateSpreadPnl_JsonDeserializationFailure_FallsBackToUnrealizedPnl() {
        // Arrange
        PaperTrade trade = new PaperTrade();
        trade.setId(java.util.UUID.randomUUID());
        trade.setSymbol("NIFTY50");
        trade.setLegs(List.of(
                TradeLeg.builder().action(Action.SELL).optionType(OptionType.PE)
                        .strikePrice(BigDecimal.valueOf(23900.0)).limitPrice(BigDecimal.valueOf(50.0)).quantity(1).build()
        ));
        trade.setUnrealizedPnl(BigDecimal.valueOf(-14.9));

        mockRedisOptionChain("NIFTY50", "{not valid json"); // Malformed JSON

        // Act & Assert
        StepVerifier.create(paperTradingEngine.calculateSpreadPnl(trade))
                .expectNextMatches(bd -> bd.compareTo(BigDecimal.valueOf(-14.9)) == 0)
                .verifyComplete();
    }

    /**
     * Test: calculateSpreadPnl() - Successful path with valid Redis data.
     * Verifies that when Redis returns valid option chain data, the method
     * correctly calculates P&L (not falling back), proving fallback logic
     * doesn't interfere with normal operation.
     */
    @Test
    @DisplayName("calculateSpreadPnl: Valid Redis data calculates correct P&L")
    void testCalculateSpreadPnl_ValidRedisData_CalculatesCorrectPnl() throws Exception {
        // Arrange
        PaperTrade trade = new PaperTrade();
        trade.setId(java.util.UUID.randomUUID());
        trade.setSymbol("NIFTY50");
        trade.setUnrealizedPnl(BigDecimal.valueOf(0.0));

        // 2-leg spread: SELL @ 50 (entry), current LTP 40 → profit 10
        //              BUY @ 30 (entry), current LTP 35 → profit 5
        // Total P&L = 10 + 5 = 15
        List<TradeLeg> legs = List.of(
                TradeLeg.builder().action(Action.SELL).optionType(OptionType.PE)
                        .strikePrice(BigDecimal.valueOf(23900.0)).limitPrice(BigDecimal.valueOf(50.0)).quantity(1).build(),
                TradeLeg.builder().action(Action.BUY).optionType(OptionType.PE)
                        .strikePrice(BigDecimal.valueOf(23800.0)).limitPrice(BigDecimal.valueOf(30.0)).quantity(1).build()
        );
        trade.setLegs(legs);

        // Create valid option chain with matching strikes and LTPs
        OptionChain chainData = OptionChain.builder()
                .symbol("NIFTY50")
                .expiryDate(LocalDate.of(2026, 7, 9))
                .strikes(List.of(
                        OptionStrike.builder().strikePrice(BigDecimal.valueOf(23900))
                                .putLTP(BigDecimal.valueOf(40.0)).build(),
                        OptionStrike.builder().strikePrice(BigDecimal.valueOf(23800))
                                .putLTP(BigDecimal.valueOf(35.0)).build()
                ))
                .build();

        when(optionChainService.fetchOptionChain("NIFTY50"))
                .thenReturn(Mono.just(chainData));

        // Act & Assert
        // Expected P&L: SELL leg (50 - 40) * 1 = 10, BUY leg (35 - 30) * 1 = 5, total = 15
        StepVerifier.create(paperTradingEngine.calculateSpreadPnl(trade))
                .expectNextMatches(bd -> bd.compareTo(BigDecimal.valueOf(15.0)) == 0)
                .verifyComplete();
        verify(optionChainService, atLeastOnce()).fetchOptionChain("NIFTY50");
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // HELPER METHODS
    // ═══════════════════════════════════════════════════════════════════════════════

    /**
     * Mocks Redis option chain data for a given symbol.
     * If jsonData is null, returns Mono.empty() (key missing).
     * If jsonData is a string, returns Mono.just(jsonData).
     *
     * @param symbol   the symbol (e.g., "NIFTY50")
     * @param jsonData the JSON string to return, or null for Mono.empty()
     */
    private void mockRedisOptionChain(String symbol, String jsonData) {
        @SuppressWarnings("unchecked")
        ReactiveValueOperations<String, String> reactiveValueOps = org.mockito.Mockito.mock(ReactiveValueOperations.class);
        org.mockito.Mockito.lenient().when(redisTemplate.opsForValue()).thenReturn(reactiveValueOps);

        String redisKey = "optionchain:" + symbol;
        if (jsonData == null) {
            org.mockito.Mockito.lenient().when(reactiveValueOps.get(redisKey)).thenReturn(Mono.empty());
        } else {
            org.mockito.Mockito.lenient().when(reactiveValueOps.get(redisKey)).thenReturn(Mono.just(jsonData));
        }
    }

    // ─── Test Case 1: calculateSpreadCurrentValue() - 2-leg spread (SELL + BUY) ───
    @Test
    @DisplayName("calculateSpreadCurrentValue: 2-leg spread with SELL @ 50 and BUY @ 20 should return 30 (signed), not 70 (buggy sum)")
    void testCalculateSpreadCurrentValue_TwoLegSpread_SignedLogic() {
        // Setup: SELL leg at 50 points, BUY leg at 20 points
        // Buggy result (sum): 50 + 20 = 70
        // Correct result (signed): 50 - 20 = 30
        OptionStrike strike1 = OptionStrike.builder()
                .strikePrice(BigDecimal.valueOf(24000))
                .callLTP(BigDecimal.valueOf(50.0))
                .build();

        OptionStrike strike2 = OptionStrike.builder()
                .strikePrice(BigDecimal.valueOf(24100))
                .callLTP(BigDecimal.valueOf(20.0))
                .build();

        optionChain.setStrikes(List.of(strike1, strike2));

        TradeLeg sellLeg = TradeLeg.builder()
                .action(Action.SELL)
                .optionType(OptionType.CE)
                .strikePrice(BigDecimal.valueOf(24000.0))
                .limitPrice(BigDecimal.valueOf(50.0))
                .quantity(1)
                .build();

        TradeLeg buyLeg = TradeLeg.builder()
                .action(Action.BUY)
                .optionType(OptionType.CE)
                .strikePrice(BigDecimal.valueOf(24100.0))
                .limitPrice(BigDecimal.valueOf(20.0))
                .quantity(1)
                .build();

        List<TradeLeg> legs = List.of(sellLeg, buyLeg);

        // Act & Assert
        StepVerifier.create(paperTradingEngine.calculateSpreadCurrentValue(optionChain, legs))
                .expectNext(30.0)  // Correct signed result: 50 - 20 = 30
                .verifyComplete();
    }

    // ─── Test Case 2: calculateIronCondorCurrentValue() - 4-leg iron condor (2 SELL, 2 BUY) ───
    @Test
    @DisplayName("calculateIronCondorCurrentValue: 4-leg iron condor with signed logic should return 50, not 100 (buggy sum)")
    void testCalculateIronCondorCurrentValue_FourLegIronCondor_SignedLogic() {
        // Setup: 4-leg iron condor
        // SELL call @ 40, SELL put @ 35, BUY call @ 15, BUY put @ 10
        // Buggy result (sum): 40 + 35 + 15 + 10 = 100
        // Correct result (signed): 40 + 35 - 15 - 10 = 50
        OptionStrike shortCallStrike = OptionStrike.builder()
                .strikePrice(BigDecimal.valueOf(24100))
                .callLTP(BigDecimal.valueOf(40.0))
                .build();

        OptionStrike shortPutStrike = OptionStrike.builder()
                .strikePrice(BigDecimal.valueOf(23900))
                .putLTP(BigDecimal.valueOf(35.0))
                .build();

        OptionStrike longCallStrike = OptionStrike.builder()
                .strikePrice(BigDecimal.valueOf(24200))
                .callLTP(BigDecimal.valueOf(15.0))
                .build();

        OptionStrike longPutStrike = OptionStrike.builder()
                .strikePrice(BigDecimal.valueOf(23800))
                .putLTP(BigDecimal.valueOf(10.0))
                .build();

        optionChain.setStrikes(List.of(shortCallStrike, shortPutStrike, longCallStrike, longPutStrike));

        TradeLeg sellCallLeg = TradeLeg.builder()
                .action(Action.SELL)
                .optionType(OptionType.CE)
                .strikePrice(BigDecimal.valueOf(24100.0))
                .limitPrice(BigDecimal.valueOf(40.0))
                .quantity(1)
                .build();

        TradeLeg sellPutLeg = TradeLeg.builder()
                .action(Action.SELL)
                .optionType(OptionType.PE)
                .strikePrice(BigDecimal.valueOf(23900.0))
                .limitPrice(BigDecimal.valueOf(35.0))
                .quantity(1)
                .build();

        TradeLeg buyCallLeg = TradeLeg.builder()
                .action(Action.BUY)
                .optionType(OptionType.CE)
                .strikePrice(BigDecimal.valueOf(24200.0))
                .limitPrice(BigDecimal.valueOf(15.0))
                .quantity(1)
                .build();

        TradeLeg buyPutLeg = TradeLeg.builder()
                .action(Action.BUY)
                .optionType(OptionType.PE)
                .strikePrice(BigDecimal.valueOf(23800.0))
                .limitPrice(BigDecimal.valueOf(10.0))
                .quantity(1)
                .build();

        List<TradeLeg> legs = List.of(sellCallLeg, sellPutLeg, buyCallLeg, buyPutLeg);

        // Act & Assert
        StepVerifier.create(paperTradingEngine.calculateIronCondorCurrentValue(optionChain, legs))
                .expectNext(50.0)  // Correct signed result: 40 + 35 - 15 - 10 = 50
                .verifyComplete();
    }

    // ─── Test Case 3: calculateDefaultCurrentValue() - minimal case (SELL + BUY) ───
    @Test
    @DisplayName("calculateDefaultCurrentValue: minimal case with SELL @ 60 and BUY @ 25 should return 35 (signed), not 85 (buggy sum)")
    void testCalculateDefaultCurrentValue_MinimalCase_SignedLogic() {
        // Setup: SELL leg at 60 points, BUY leg at 25 points
        // Buggy result (sum): 60 + 25 = 85
        // Correct result (signed): 60 - 25 = 35
        OptionStrike strike1 = OptionStrike.builder()
                .strikePrice(BigDecimal.valueOf(24000))
                .putLTP(BigDecimal.valueOf(60.0))
                .build();

        OptionStrike strike2 = OptionStrike.builder()
                .strikePrice(BigDecimal.valueOf(24100))
                .putLTP(BigDecimal.valueOf(25.0))
                .build();

        optionChain.setStrikes(List.of(strike1, strike2));

        TradeLeg sellLeg = TradeLeg.builder()
                .action(Action.SELL)
                .optionType(OptionType.PE)
                .strikePrice(BigDecimal.valueOf(24000.0))
                .limitPrice(BigDecimal.valueOf(60.0))
                .quantity(1)
                .build();

        TradeLeg buyLeg = TradeLeg.builder()
                .action(Action.BUY)
                .optionType(OptionType.PE)
                .strikePrice(BigDecimal.valueOf(24100.0))
                .limitPrice(BigDecimal.valueOf(25.0))
                .quantity(1)
                .build();

        List<TradeLeg> legs = List.of(sellLeg, buyLeg);

        // Act & Assert
        StepVerifier.create(paperTradingEngine.calculateDefaultCurrentValue(optionChain, legs))
                .expectNext(35.0)  // Correct signed result: 60 - 25 = 35
                .verifyComplete();
    }

    // ─── Test Case 4: calculateSpreadCurrentValue() - edge case with null legs ───
    @Test
    @DisplayName("calculateSpreadCurrentValue: null legs should return 0.0")
    void testCalculateSpreadCurrentValue_NullLegs_ReturnsZero() {
        // Act & Assert
        StepVerifier.create(paperTradingEngine.calculateSpreadCurrentValue(optionChain, null))
                .expectNext(0.0)
                .verifyComplete();
    }

    // ─── Test Case 5: calculateSpreadCurrentValue() - edge case with empty legs ───
    @Test
    @DisplayName("calculateSpreadCurrentValue: empty legs should return 0.0")
    void testCalculateSpreadCurrentValue_EmptyLegs_ReturnsZero() {
        // Act & Assert
        StepVerifier.create(paperTradingEngine.calculateSpreadCurrentValue(optionChain, List.of()))
                .expectNext(0.0)
                .verifyComplete();
    }

    // ─── Test Case 6: calculateIronCondorCurrentValue() - edge case with null legs ───
    @Test
    @DisplayName("calculateIronCondorCurrentValue: null legs should return 0.0")
    void testCalculateIronCondorCurrentValue_NullLegs_ReturnsZero() {
        // Act & Assert
        StepVerifier.create(paperTradingEngine.calculateIronCondorCurrentValue(optionChain, null))
                .expectNext(0.0)
                .verifyComplete();
    }

    // ─── Test Case 7: calculateDefaultCurrentValue() - edge case with null legs ───
    @Test
    @DisplayName("calculateDefaultCurrentValue: null legs should return 0.0")
    void testCalculateDefaultCurrentValue_NullLegs_ReturnsZero() {
        // Act & Assert
        StepVerifier.create(paperTradingEngine.calculateDefaultCurrentValue(optionChain, null))
                .expectNext(0.0)
                .verifyComplete();
    }

    // ─── Test Case 8: calculateSpreadCurrentValue() - single SELL leg ───
    @Test
    @DisplayName("calculateSpreadCurrentValue: single SELL leg should return its LTP")
    void testCalculateSpreadCurrentValue_SingleSellLeg_ReturnsLtp() {
        // Setup: Single SELL leg at 45 points
        OptionStrike strike = OptionStrike.builder()
                .strikePrice(BigDecimal.valueOf(24000))
                .callLTP(BigDecimal.valueOf(45.0))
                .build();

        optionChain.setStrikes(List.of(strike));

        TradeLeg sellLeg = TradeLeg.builder()
                .action(Action.SELL)
                .optionType(OptionType.CE)
                .strikePrice(BigDecimal.valueOf(24000.0))
                .limitPrice(BigDecimal.valueOf(45.0))
                .quantity(1)
                .build();

        // Act & Assert
        StepVerifier.create(paperTradingEngine.calculateSpreadCurrentValue(optionChain, List.of(sellLeg)))
                .expectNext(45.0)
                .verifyComplete();
    }

    // ─── Test Case 9: calculateSpreadCurrentValue() - single BUY leg ───
    @Test
    @DisplayName("calculateSpreadCurrentValue: single BUY leg should return negative of its LTP")
    void testCalculateSpreadCurrentValue_SingleBuyLeg_ReturnsNegativeLtp() {
        // Setup: Single BUY leg at 30 points
        OptionStrike strike = OptionStrike.builder()
                .strikePrice(BigDecimal.valueOf(24000))
                .callLTP(BigDecimal.valueOf(30.0))
                .build();

        optionChain.setStrikes(List.of(strike));

        TradeLeg buyLeg = TradeLeg.builder()
                .action(Action.BUY)
                .optionType(OptionType.CE)
                .strikePrice(BigDecimal.valueOf(24000.0))
                .limitPrice(BigDecimal.valueOf(30.0))
                .quantity(1)
                .build();

        // Act & Assert
        StepVerifier.create(paperTradingEngine.calculateSpreadCurrentValue(optionChain, List.of(buyLeg)))
                .expectNext(-30.0)
                .verifyComplete();
    }

    // ─── Test Case 10: calculateIronCondorCurrentValue() - all SELL legs ───
    @Test
    @DisplayName("calculateIronCondorCurrentValue: all SELL legs should sum their LTPs")
    void testCalculateIronCondorCurrentValue_AllSellLegs_SumsLtps() {
        // Setup: 2 SELL legs at 40 and 35 points
        OptionStrike strike1 = OptionStrike.builder()
                .strikePrice(BigDecimal.valueOf(24100))
                .callLTP(BigDecimal.valueOf(40.0))
                .build();

        OptionStrike strike2 = OptionStrike.builder()
                .strikePrice(BigDecimal.valueOf(23900))
                .putLTP(BigDecimal.valueOf(35.0))
                .build();

        optionChain.setStrikes(List.of(strike1, strike2));

        TradeLeg sellCallLeg = TradeLeg.builder()
                .action(Action.SELL)
                .optionType(OptionType.CE)
                .strikePrice(BigDecimal.valueOf(24100.0))
                .limitPrice(BigDecimal.valueOf(40.0))
                .quantity(1)
                .build();

        TradeLeg sellPutLeg = TradeLeg.builder()
                .action(Action.SELL)
                .optionType(OptionType.PE)
                .strikePrice(BigDecimal.valueOf(23900.0))
                .limitPrice(BigDecimal.valueOf(35.0))
                .quantity(1)
                .build();

        // Act & Assert
        StepVerifier.create(paperTradingEngine.calculateIronCondorCurrentValue(optionChain, List.of(sellCallLeg, sellPutLeg)))
                .expectNext(75.0)  // 40 + 35 = 75
                .verifyComplete();
    }

    // ─── Test Case 11: calculateIronCondorCurrentValue() - all BUY legs ───
    @Test
    @DisplayName("calculateIronCondorCurrentValue: all BUY legs should negate their LTPs")
    void testCalculateIronCondorCurrentValue_AllBuyLegs_NegatesLtps() {
        // Setup: 2 BUY legs at 15 and 10 points
        OptionStrike strike1 = OptionStrike.builder()
                .strikePrice(BigDecimal.valueOf(24200))
                .callLTP(BigDecimal.valueOf(15.0))
                .build();

        OptionStrike strike2 = OptionStrike.builder()
                .strikePrice(BigDecimal.valueOf(23800))
                .putLTP(BigDecimal.valueOf(10.0))
                .build();

        optionChain.setStrikes(List.of(strike1, strike2));

        TradeLeg buyCallLeg = TradeLeg.builder()
                .action(Action.BUY)
                .optionType(OptionType.CE)
                .strikePrice(BigDecimal.valueOf(24200.0))
                .limitPrice(BigDecimal.valueOf(15.0))
                .quantity(1)
                .build();

        TradeLeg buyPutLeg = TradeLeg.builder()
                .action(Action.BUY)
                .optionType(OptionType.PE)
                .strikePrice(BigDecimal.valueOf(23800.0))
                .limitPrice(BigDecimal.valueOf(10.0))
                .quantity(1)
                .build();

        // Act & Assert
        StepVerifier.create(paperTradingEngine.calculateIronCondorCurrentValue(optionChain, List.of(buyCallLeg, buyPutLeg)))
                .expectNext(-25.0)  // -15 - 10 = -25
                .verifyComplete();
    }

    // ─── Test Case 12: calculateDefaultCurrentValue() - null chainData graceful fallback ───
    @Test
    @DisplayName("calculateDefaultCurrentValue: null chainData falls back to leg.getLimitPrice() for SELL leg")
    void testCalculateDefaultCurrentValue_NullChainData_FallsBackToLimitPrice() {
        // Setup: null optionChain triggers graceful fallback in extractLTP()
        // extractLTP() null-checks chainData (line 539) and returns leg.getLimitPrice()
        // For a SELL leg: totalCurrentValue += 50.0 (the limitPrice)
        TradeLeg leg = TradeLeg.builder()
                .action(Action.SELL)
                .optionType(OptionType.CE)
                .strikePrice(BigDecimal.valueOf(24000.0))
                .limitPrice(BigDecimal.valueOf(50.0))
                .quantity(1)
                .build();

        // Act & Assert
        StepVerifier.create(paperTradingEngine.calculateDefaultCurrentValue(null, List.of(leg)))
                .expectNext(50.0)  // Graceful fallback: SELL leg returns its limitPrice
                .verifyComplete();
    }
}
