package com.trademesh.execution;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.trademesh.alert.TelegramNotificationBot;
import com.trademesh.config.LotSizeService;
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
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for markToMarket() STOP_LOSS/TARGET_HIT decision extraction.
 * <p>
 * STEP 2: Exhaustive tests for the pure decision method determineMarkToMarketExitTrigger().
 * STEP 3: Two lightweight integration tests for markToMarket()'s wiring.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PaperTradingEngine markToMarket Decision Tests")
class PaperTradingEngineMarkToMarketDecisionTest {

    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
    @Mock
    private PaperTradeRepository paperTradeRepository;
    @Mock
    private ReactiveRedisTemplate<String, String> redisTemplate;
    @Mock
    private TelegramNotificationBot telegramNotificationBot;
    @Mock
    private LotSizeService lotSizeService;
    @Mock
    private OptionChainService optionChainService;

    @Mock
    private com.trademesh.zerodha.options.KiteMarginService kiteMarginService;

    private PaperTradingEngine engine;

    @BeforeEach
    void setUp() {
        engine = new PaperTradingEngine(
                paperTradeRepository, redisTemplate, telegramNotificationBot,
                objectMapper, null, lotSizeService, null, null, optionChainService, kiteMarginService, null
        );
        lenient().when(lotSizeService.getLotSize("NIFTY50")).thenReturn(75);
        lenient().when(kiteMarginService.fetchBasketMargin(any(), any())).thenReturn(Mono.empty());
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // STEP 2: EXHAUSTIVE PURE FUNCTION TESTS (determineMarkToMarketExitTrigger)
    // ═══════════════════════════════════════════════════════════════════════════════

    /**
     * Test 1: currentValue exactly 2x entryCredit (200.0) → Optional.empty()
     * Boundary is > not >=, so exact equality does NOT trigger STOP_LOSS.
     */
    @Test
    @DisplayName("determineMarkToMarketExitTrigger: currentValue = 2x entryCredit (boundary) → empty")
    void determineMarkToMarketExitTrigger_ExactlyTwoX_ReturnsEmpty() {
        BigDecimal currentValueBD = BigDecimal.valueOf(200.0);
        BigDecimal entryCreditBD = BigDecimal.valueOf(100.0);

        Optional<PaperTradingEngine.MarkToMarketExitTrigger> result =
                engine.determineMarkToMarketExitTrigger(currentValueBD, entryCreditBD);

        assertEquals(Optional.empty(), result);
    }

    /**
     * Test 2: currentValue just above 2x (200.01) → Optional.of(STOP_LOSS)
     */
    @Test
    @DisplayName("determineMarkToMarketExitTrigger: currentValue > 2x entryCredit → STOP_LOSS")
    void determineMarkToMarketExitTrigger_JustAboveTwoX_ReturnsStopLoss() {
        BigDecimal currentValueBD = BigDecimal.valueOf(200.01);
        BigDecimal entryCreditBD = BigDecimal.valueOf(100.0);

        Optional<PaperTradingEngine.MarkToMarketExitTrigger> result =
                engine.determineMarkToMarketExitTrigger(currentValueBD, entryCreditBD);

        assertEquals(Optional.of(PaperTradingEngine.MarkToMarketExitTrigger.STOP_LOSS), result);
    }

    /**
     * Test 3: currentValue just below 2x (199.99) → Optional.empty()
     */
    @Test
    @DisplayName("determineMarkToMarketExitTrigger: currentValue < 2x entryCredit → empty")
    void determineMarkToMarketExitTrigger_JustBelowTwoX_ReturnsEmpty() {
        BigDecimal currentValueBD = BigDecimal.valueOf(199.99);
        BigDecimal entryCreditBD = BigDecimal.valueOf(100.0);

        Optional<PaperTradingEngine.MarkToMarketExitTrigger> result =
                engine.determineMarkToMarketExitTrigger(currentValueBD, entryCreditBD);

        assertEquals(Optional.empty(), result);
    }

    /**
     * Test 4: currentValue exactly 0.5x entryCredit (50.0) → Optional.empty()
     * Boundary is < not <=, so exact equality does NOT trigger TARGET_HIT.
     */
    @Test
    @DisplayName("determineMarkToMarketExitTrigger: currentValue = 0.5x entryCredit (boundary) → empty")
    void determineMarkToMarketExitTrigger_ExactlyHalfX_ReturnsEmpty() {
        BigDecimal currentValueBD = BigDecimal.valueOf(50.0);
        BigDecimal entryCreditBD = BigDecimal.valueOf(100.0);

        Optional<PaperTradingEngine.MarkToMarketExitTrigger> result =
                engine.determineMarkToMarketExitTrigger(currentValueBD, entryCreditBD);

        assertEquals(Optional.empty(), result);
    }

    /**
     * Test 5: currentValue just below 0.5x (49.99) → Optional.of(TARGET_HIT)
     */
    @Test
    @DisplayName("determineMarkToMarketExitTrigger: currentValue < 0.5x entryCredit → TARGET_HIT")
    void determineMarkToMarketExitTrigger_JustBelowHalfX_ReturnsTargetHit() {
        BigDecimal currentValueBD = BigDecimal.valueOf(49.99);
        BigDecimal entryCreditBD = BigDecimal.valueOf(100.0);

        Optional<PaperTradingEngine.MarkToMarketExitTrigger> result =
                engine.determineMarkToMarketExitTrigger(currentValueBD, entryCreditBD);

        assertEquals(Optional.of(PaperTradingEngine.MarkToMarketExitTrigger.TARGET_HIT), result);
    }

    /**
     * Test 6: currentValue just above 0.5x (50.01) → Optional.empty()
     */
    @Test
    @DisplayName("determineMarkToMarketExitTrigger: currentValue > 0.5x entryCredit → empty")
    void determineMarkToMarketExitTrigger_JustAboveHalfX_ReturnsEmpty() {
        BigDecimal currentValueBD = BigDecimal.valueOf(50.01);
        BigDecimal entryCreditBD = BigDecimal.valueOf(100.0);

        Optional<PaperTradingEngine.MarkToMarketExitTrigger> result =
                engine.determineMarkToMarketExitTrigger(currentValueBD, entryCreditBD);

        assertEquals(Optional.empty(), result);
    }

    /**
     * Test 7: currentValue comfortably in the middle (75.0) → Optional.empty()
     */
    @Test
    @DisplayName("determineMarkToMarketExitTrigger: currentValue in middle range → empty")
    void determineMarkToMarketExitTrigger_MiddleRange_ReturnsEmpty() {
        BigDecimal currentValueBD = BigDecimal.valueOf(75.0);
        BigDecimal entryCreditBD = BigDecimal.valueOf(100.0);

        Optional<PaperTradingEngine.MarkToMarketExitTrigger> result =
                engine.determineMarkToMarketExitTrigger(currentValueBD, entryCreditBD);

        assertEquals(Optional.empty(), result);
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // STEP 3: LIGHTWEIGHT INTEGRATION TESTS (markToMarket wiring)
    // ═══════════════════════════════════════════════════════════════════════════════

    /**
     * Test A: STOP_LOSS trigger correctly invokes closeTrade().
     * <p>
     * Setup:
     * - entryCredit = 100.0 points
     * - 2 legs: SELL @ 23900 PE (entry 50.0), BUY @ 23800 PE (entry 30.0)
     * - Redis returns: SELL leg putLTP = 220.0, BUY leg putLTP = 10.0
     * <p>
     * Arithmetic (fetchCurrentPrices signed netting):
     * SELL leg: +220.0 (SELL adds LTP)
     * BUY leg:  -10.0 (BUY subtracts LTP)
     * currentValue = 220.0 - 10.0 = 210.0
     * <p>
     * STOP_LOSS check: currentValue (210.0) > entryCredit × 2 (200.0)?
     * 210.0 > 200.0? YES → STOP_LOSS triggered ✓
     * realizedPnl = entryCredit - finalValue = 100.0 - 210.0 = -110.0
     */
    @Test
    @DisplayName("markToMarket: STOP_LOSS trigger invokes closeTrade()")
    void markToMarket_StopLossTriggered_InvokesCloseTrade() {
        // Arrange: Create trade with entryCredit = 100.0
        PaperTrade trade = new PaperTrade();
        trade.setId(UUID.randomUUID());
        trade.setSymbol("NIFTY50");
        trade.setStrategyName("EXPIRY_DAY_STRANGLE");
        trade.setStatus(TradeStatus.OPEN);
        trade.setEntryCredit(BigDecimal.valueOf(100.0));
        trade.setCurrentValue(BigDecimal.valueOf(100.0));
        trade.setUnrealizedPnl(BigDecimal.valueOf(0.0));
        trade.setEntryTime(LocalDateTime.now());
        trade.setTradeDate(LocalDate.now());

        // 2 legs: SELL CE @ 23950 (entry 120.0), SELL PE @ 23850 (entry 90.0)
        // Naked strangle: both SELL, different option types
        List<TradeLeg> legs = List.of(
                TradeLeg.builder()
                        .action(Action.SELL)
                        .optionType(OptionType.CE)
                        .strikePrice(BigDecimal.valueOf(23950.0))
                        .limitPrice(BigDecimal.valueOf(120.0))
                        .quantity(1)
                        .expiryDate(LocalDate.now().plusDays(7))
                        .build(),
                TradeLeg.builder()
                        .action(Action.SELL)
                        .optionType(OptionType.PE)
                        .strikePrice(BigDecimal.valueOf(23850.0))
                        .limitPrice(BigDecimal.valueOf(90.0))
                        .quantity(1)
                        .expiryDate(LocalDate.now().plusDays(7))
                        .build()
        );
        trade.setLegs(legs);

        // Mock Redis to return option chain with LTPs that produce currentValue = 210.0
        // SELL CE leg (23950): callLTP = 120.0 → adds 120.0
        // SELL PE leg (23850): putLTP = 90.0 → adds 90.0
        // Signed netting: 120.0 + 90.0 = 210.0
        OptionChain optionChain = OptionChain.builder()
                .symbol("NIFTY50")
                .expiryDate(LocalDate.of(2026, 7, 9))
                .strikes(List.of(
                        OptionStrike.builder()
                                .strikePrice(BigDecimal.valueOf(23950))
                                .callLTP(BigDecimal.valueOf(120.0))  // SELL CE leg LTP
                                .build(),
                        OptionStrike.builder()
                                .strikePrice(BigDecimal.valueOf(23850))
                                .putLTP(BigDecimal.valueOf(90.0))   // SELL PE leg LTP
                                .build()
                ))
                .build();

        when(optionChainService.fetchOptionChain("NIFTY50"))
                .thenReturn(Mono.just(optionChain));

        // Mock paperTradeRepository.findById() for closeTrade's internal re-fetch
        // Return a FRESH trade object with status OPEN (so closeTrade's guard doesn't skip)
        PaperTrade freshTrade = new PaperTrade();
        freshTrade.setId(trade.getId());
        freshTrade.setSymbol("NIFTY50");
        freshTrade.setStrategyName("BULL_PUT_SPREAD");
        freshTrade.setStatus(TradeStatus.OPEN);  // CRITICAL: must be OPEN
        freshTrade.setEntryCredit(BigDecimal.valueOf(100.0));
        freshTrade.setCurrentValue(BigDecimal.valueOf(100.0));
        freshTrade.setUnrealizedPnl(BigDecimal.valueOf(0.0));
        freshTrade.setEntryTime(LocalDateTime.now());
        freshTrade.setTradeDate(LocalDate.now());
        freshTrade.setLegs(legs);

        when(paperTradeRepository.findById(trade.getId()))
                .thenReturn(Mono.just(freshTrade));

        // Mock paperTradeRepository.save() to echo back the mutated trade
        when(paperTradeRepository.save(any(PaperTrade.class)))
                .thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));

        // Mock telegramNotificationBot.sendAlert()
        lenient().when(telegramNotificationBot.sendAlert(any()))
                .thenReturn(Mono.empty());

        // Act & Assert
        StepVerifier.create(engine.markToMarket(trade))
                .expectNextMatches(result -> {
                    // Assert
                    return result.getStatus() == TradeStatus.CLOSED &&
                            result.getCurrentValue().compareTo(BigDecimal.valueOf(210.0)) == 0 &&  // Verify actual computed value
                            result.getExitReason().equals("STOP_LOSS") &&
                            result.getRealizedPnl().compareTo(BigDecimal.valueOf(-110.0)) == 0;  // 100.0 - 210.0 = -110.0
                })
                .verifyComplete();

        // Verify closeTrade path was invoked (findById called)
        verify(paperTradeRepository, times(1)).findById(trade.getId());
        // Verify save was called (from closeTrade)
        verify(paperTradeRepository, times(1)).save(any(PaperTrade.class));
        // Verify alert was sent
        verify(telegramNotificationBot, times(1)).sendAlert(any());
    }

    /**
     * Test B: No trigger → paperTradeRepository.save() is called directly,
     * closeTrade() is NOT invoked.
     * <p>
     * Setup:
     * - entryCredit = 100.0 points
     * - 2 legs: SELL @ 23900 PE, BUY @ 23800 PE
     * - Redis returns: SELL leg putLTP = 75.0, BUY leg putLTP = 25.0
     * <p>
     * Arithmetic:
     * currentValue = 75.0 - 25.0 = 50.0
     * 50.0 > 200.0? NO (not STOP_LOSS)
     * 50.0 < 50.0? NO (not TARGET_HIT)
     * → No trigger, save() called directly
     */
    @Test
    @DisplayName("markToMarket: No trigger → save() called directly, closeTrade() NOT invoked")
    void markToMarket_NoTrigger_SavesDirectly() {
        // Arrange: Create trade with entryCredit = 100.0
        PaperTrade trade = new PaperTrade();
        trade.setId(UUID.randomUUID());
        trade.setSymbol("NIFTY50");
        trade.setStrategyName("EXPIRY_DAY_STRANGLE");
        trade.setStatus(TradeStatus.OPEN);
        trade.setEntryCredit(BigDecimal.valueOf(100.0));
        trade.setCurrentValue(BigDecimal.valueOf(100.0));
        trade.setUnrealizedPnl(BigDecimal.valueOf(0.0));
        trade.setEntryTime(LocalDateTime.now());
        trade.setTradeDate(LocalDate.now());

        // 2 legs: SELL CE @ 23950 (entry 30.0), SELL PE @ 23850 (entry 20.0)
        // Naked strangle: both SELL, different option types
        List<TradeLeg> legs = List.of(
                TradeLeg.builder()
                        .action(Action.SELL)
                        .optionType(OptionType.CE)
                        .strikePrice(BigDecimal.valueOf(23950.0))
                        .limitPrice(BigDecimal.valueOf(30.0))
                        .quantity(1)
                        .expiryDate(LocalDate.now().plusDays(7))
                        .build(),
                TradeLeg.builder()
                        .action(Action.SELL)
                        .optionType(OptionType.PE)
                        .strikePrice(BigDecimal.valueOf(23850.0))
                        .limitPrice(BigDecimal.valueOf(20.0))
                        .quantity(1)
                        .expiryDate(LocalDate.now().plusDays(7))
                        .build()
        );
        trade.setLegs(legs);

        // Mock Redis to return option chain with LTPs that produce currentValue = 50.0
        // SELL CE leg (23950): callLTP = 30.0 → adds 30.0
        // SELL PE leg (23850): putLTP = 20.0 → adds 20.0
        // Signed netting: 30.0 + 20.0 = 50.0
        // 50.0 is in the safe range (not > 200.0, not < 50.0)
        OptionChain optionChain = OptionChain.builder()
                .symbol("NIFTY50")
                .expiryDate(LocalDate.of(2026, 7, 9))
                .strikes(List.of(
                        OptionStrike.builder()
                                .strikePrice(BigDecimal.valueOf(23950))
                                .callLTP(BigDecimal.valueOf(30.0))
                                .build(),
                        OptionStrike.builder()
                                .strikePrice(BigDecimal.valueOf(23850))
                                .putLTP(BigDecimal.valueOf(20.0))
                                .build()
                ))
                .build();

        when(optionChainService.fetchOptionChain("NIFTY50"))
                .thenReturn(Mono.just(optionChain));

        // Mock paperTradeRepository.save() to echo back the mutated trade
        when(paperTradeRepository.save(any(PaperTrade.class)))
                .thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));

        // NOTE: findById() is NOT stubbed — it should never be called
        // If it is called, the test will fail with "Unexpected invocation"

        // Act & Assert
        StepVerifier.create(engine.markToMarket(trade))
                .expectNextMatches(result ->
                        result.getStatus() == TradeStatus.OPEN &&
                                result.getCurrentValue().compareTo(BigDecimal.valueOf(50.0)) == 0 &&
                                result.getUnrealizedPnl().compareTo(BigDecimal.valueOf(50.0)) == 0  // entryCredit - currentValue = 100.0 - 50.0 = 50.0
                )
                .verifyComplete();

        // Verify save() was called directly (not through closeTrade)
        verify(paperTradeRepository, times(1)).save(any(PaperTrade.class));

        // Verify findById() was NOT called (unique to closeTrade path)
        verify(paperTradeRepository, never()).findById(any(UUID.class));

        // Verify alert was NOT sent (no close)
        verify(telegramNotificationBot, never()).sendAlert(any());
    }
}
