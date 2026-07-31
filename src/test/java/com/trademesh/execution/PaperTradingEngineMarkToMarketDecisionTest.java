package com.trademesh.execution;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.trademesh.alert.TelegramNotificationBot;
import com.trademesh.config.LotSizeService;
import com.trademesh.strategy.TradeLeg;
import com.trademesh.strategy.config.StrategiesConfig;
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
    private StrategiesConfig strategiesConfig;
    @Mock
    private com.trademesh.zerodha.options.KiteMarginService kiteMarginService;
    @Mock
    private com.trademesh.zerodha.auth.ZerodhaAuthService authService;
    private PaperTradingEngine engine;

    @BeforeEach
    void setUp() {
        engine = new PaperTradingEngine(
                paperTradeRepository, redisTemplate, telegramNotificationBot,
                objectMapper, strategiesConfig, lotSizeService, null, null, optionChainService, kiteMarginService, authService
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
      * Test A: STOP_LOSS trigger correctly invokes closeExpiryDayTrade().
      * <p>
      * Setup:
      * - entryCredit = 50.0 points (net credit after hedge cost)
      * - stopDistancePoints = 10.0 (50.0 × 20% stop-loss)
      * - 4 legs: 2 SELL (short) + 2 BUY (hedge)
      * - lots = 1
      * <p>
      * Arithmetic (calculateExpiryDayStranglePnl):
      * SELL CE: entry 30.0, current 45.0 → legPnl = (30.0 - 45.0) × 1 = -15.0
      * SELL PE: entry 20.0, current 35.0 → legPnl = (20.0 - 35.0) × 1 = -15.0
      * BUY CE:  entry 5.0, current 8.0 → legPnl = (8.0 - 5.0) × 1 = 3.0
      * BUY PE:  entry 5.0, current 8.0 → legPnl = (8.0 - 5.0) × 1 = 3.0
      * totalPnl = -15.0 - 15.0 + 3.0 + 3.0 = -24.0
      * <p>
      * STOP_LOSS check (new logic):
      * stopLossThreshold = stopDistancePoints × lots × -1 = 10.0 × 1 × -1 = -10.0
      * pnl (-24.0) < stopLossThreshold (-10.0)? YES → STOP_LOSS triggered ✓
      */
     @Test
     @DisplayName("markToMarket: STOP_LOSS trigger (via stopDistancePoints) invokes closeExpiryDayTrade()")
     void markToMarket_StopLossTriggered_ViaStopDistancePoints_InvokesCloseExpiryDayTrade() {
         // Arrange: Create trade with entryCredit = 50.0, stopDistancePoints = 10.0
         PaperTrade trade = new PaperTrade();
         trade.setId(UUID.randomUUID());
         trade.setSymbol("NIFTY50");
         trade.setStrategyName("EXPIRY_DAY_STRANGLE");
         trade.setStatus(TradeStatus.OPEN);
         trade.setEntryCredit(BigDecimal.valueOf(50.0));
         trade.setCurrentValue(BigDecimal.valueOf(50.0));
         trade.setUnrealizedPnl(BigDecimal.valueOf(0.0));
         trade.setStopDistancePoints(BigDecimal.valueOf(10.0));  // 50.0 × 20%
         trade.setTargetThreshold(BigDecimal.valueOf(5.0));      // 50.0 × 10%
         trade.setEntryTime(LocalDateTime.now());
         trade.setTradeDate(LocalDate.now());
 
         // 4 legs: 2 SELL (short) + 2 BUY (hedge)
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
                         .build(),
                 TradeLeg.builder()
                         .action(Action.BUY)
                         .optionType(OptionType.CE)
                         .strikePrice(BigDecimal.valueOf(24050.0))
                         .limitPrice(BigDecimal.valueOf(5.0))
                         .quantity(1)
                         .expiryDate(LocalDate.now().plusDays(7))
                         .build(),
                 TradeLeg.builder()
                         .action(Action.BUY)
                         .optionType(OptionType.PE)
                         .strikePrice(BigDecimal.valueOf(23750.0))
                         .limitPrice(BigDecimal.valueOf(5.0))
                         .quantity(1)
                         .expiryDate(LocalDate.now().plusDays(7))
                         .build()
         );
         trade.setLegs(legs);
 
         // Mock option chain with LTPs that produce totalPnl = -24.0
         OptionChain optionChain = OptionChain.builder()
                 .symbol("NIFTY50")
                 .expiryDate(LocalDate.of(2026, 7, 9))
                 .strikes(List.of(
                         OptionStrike.builder()
                                 .strikePrice(BigDecimal.valueOf(23950))
                                 .callLTP(BigDecimal.valueOf(45.0))  // SELL CE current (entry 30.0)
                                 .build(),
                         OptionStrike.builder()
                                 .strikePrice(BigDecimal.valueOf(23850))
                                 .putLTP(BigDecimal.valueOf(35.0))   // SELL PE current (entry 20.0)
                                 .build(),
                         OptionStrike.builder()
                                 .strikePrice(BigDecimal.valueOf(24050))
                                 .callLTP(BigDecimal.valueOf(8.0))   // BUY CE current (entry 5.0)
                                 .build(),
                         OptionStrike.builder()
                                 .strikePrice(BigDecimal.valueOf(23750))
                                 .putLTP(BigDecimal.valueOf(8.0))    // BUY PE current (entry 5.0)
                                 .build()
                 ))
                 .build();
 
         when(optionChainService.fetchOptionChain("NIFTY50"))
                 .thenReturn(Mono.just(optionChain));
 
         // Mock paperTradeRepository.findById() for closeExpiryDayTrade's internal re-fetch
         PaperTrade freshTrade = new PaperTrade();
         freshTrade.setId(trade.getId());
         freshTrade.setSymbol("NIFTY50");
         freshTrade.setStrategyName("EXPIRY_DAY_STRANGLE");
         freshTrade.setStatus(TradeStatus.OPEN);
         freshTrade.setEntryCredit(BigDecimal.valueOf(50.0));
         freshTrade.setCurrentValue(BigDecimal.valueOf(50.0));
         freshTrade.setUnrealizedPnl(BigDecimal.valueOf(0.0));
         freshTrade.setStopDistancePoints(BigDecimal.valueOf(10.0));
         freshTrade.setTargetThreshold(BigDecimal.valueOf(5.0));
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
                     // Hand-calculated expected values:
                     // legPnl_SELL_CE = (30.0 - 45.0) × 1 = -15.0
                     // legPnl_SELL_PE = (20.0 - 35.0) × 1 = -15.0
                     // legPnl_BUY_CE = (8.0 - 5.0) × 1 = 3.0
                     // legPnl_BUY_PE = (8.0 - 5.0) × 1 = 3.0
                     // totalPnl = -15.0 - 15.0 + 3.0 + 3.0 = -24.0
                     // stopLossThreshold = 10.0 × 1 × -1 = -10.0
                     // pnl (-24.0) < stopLossThreshold (-10.0)? YES → STOP_LOSS
                     // realizedPnl = totalPnl = -24.0
                     return result.getStatus() == TradeStatus.CLOSED &&
                             result.getExitReason().equals("STOP_LOSS") &&
                             result.getRealizedPnl().compareTo(BigDecimal.valueOf(-24.0)) == 0;
                 })
                 .verifyComplete();
 
         // Verify closeExpiryDayTrade path was invoked (findById called)
         verify(paperTradeRepository, times(1)).findById(trade.getId());
         // Verify save was called (from closeExpiryDayTrade)
         verify(paperTradeRepository, times(1)).save(any(PaperTrade.class));
         // Verify alert was sent
         verify(telegramNotificationBot, times(1)).sendAlert(any());
     }

      /**
       * Test B: No trigger (with explicit risk snapshot fields) → save() called directly.
       * <p>
       * Setup:
       * - entryCredit = 50.0 points (net credit after hedge cost)
       * - stopDistancePoints = 10.0 (50.0 × 20% stop-loss)
       * - targetThreshold = 5.0 (50.0 × 10% target-profit)
       * - 4 legs: 2 SELL (short) + 2 BUY (hedge)
       * - lots = 1
       * <p>
       * Hand-calculated P&L via calculateExpiryDayStranglePnl():
       * SELL CE: entry 30.0, current 32.0 → legPnl = (30.0 - 32.0) × 1 = -2.0
       * SELL PE: entry 20.0, current 21.0 → legPnl = (20.0 - 21.0) × 1 = -1.0
       * BUY CE:  entry 5.0, current 6.0 → legPnl = (6.0 - 5.0) × 1 = 1.0
       * BUY PE:  entry 5.0, current 6.0 → legPnl = (6.0 - 5.0) × 1 = 1.0
       * totalPnl = -2.0 - 1.0 + 1.0 + 1.0 = -1.0
       * <p>
       * Exit check (new logic):
       * stopLossThreshold = stopDistancePoints × lots × -1 = 10.0 × 1 × -1 = -10.0
       * pnl (-1.0) < stopLossThreshold (-10.0)? NO
       * profitTarget = targetThreshold × lots = 5.0 × 1 = 5.0
       * pnl (-1.0) >= profitTarget (5.0)? NO
       * → No trigger, save() called directly
       */
      @Test
      @DisplayName("markToMarket: No trigger (with explicit risk snapshot fields) → save() called directly")
      void markToMarket_NoTrigger_WithExplicitRiskFields_SavesDirectly() {
          // Arrange: Create trade with entryCredit = 50.0, stopDistancePoints = 10.0, targetThreshold = 5.0
          PaperTrade trade = new PaperTrade();
          trade.setId(UUID.randomUUID());
          trade.setSymbol("NIFTY50");
          trade.setStrategyName("EXPIRY_DAY_STRANGLE");
          trade.setStatus(TradeStatus.OPEN);
          trade.setEntryCredit(BigDecimal.valueOf(50.0));
          trade.setCurrentValue(BigDecimal.valueOf(50.0));
          trade.setUnrealizedPnl(BigDecimal.valueOf(0.0));
          trade.setStopDistancePoints(BigDecimal.valueOf(10.0));  // 50.0 × 20%
          trade.setTargetThreshold(BigDecimal.valueOf(5.0));      // 50.0 × 10%
          trade.setEntryTime(LocalDateTime.now());
          trade.setTradeDate(LocalDate.now());

          // 4 legs: 2 SELL (short) + 2 BUY (hedge)
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
                          .build(),
                  TradeLeg.builder()
                          .action(Action.BUY)
                          .optionType(OptionType.CE)
                          .strikePrice(BigDecimal.valueOf(24050.0))
                          .limitPrice(BigDecimal.valueOf(5.0))
                          .quantity(1)
                          .expiryDate(LocalDate.now().plusDays(7))
                          .build(),
                  TradeLeg.builder()
                          .action(Action.BUY)
                          .optionType(OptionType.PE)
                          .strikePrice(BigDecimal.valueOf(23750.0))
                          .limitPrice(BigDecimal.valueOf(5.0))
                          .quantity(1)
                          .expiryDate(LocalDate.now().plusDays(7))
                          .build()
          );
          trade.setLegs(legs);

          // Mock option chain with LTPs that produce totalPnl = -1.0 (strictly between -10.0 and 5.0)
          OptionChain optionChain = OptionChain.builder()
                  .symbol("NIFTY50")
                  .expiryDate(LocalDate.of(2026, 7, 9))
                  .strikes(List.of(
                          OptionStrike.builder()
                                  .strikePrice(BigDecimal.valueOf(23950))
                                  .callLTP(BigDecimal.valueOf(32.0))  // SELL CE current (entry 30.0)
                                  .build(),
                          OptionStrike.builder()
                                  .strikePrice(BigDecimal.valueOf(23850))
                                  .putLTP(BigDecimal.valueOf(21.0))   // SELL PE current (entry 20.0)
                                  .build(),
                          OptionStrike.builder()
                                  .strikePrice(BigDecimal.valueOf(24050))
                                  .callLTP(BigDecimal.valueOf(6.0))   // BUY CE current (entry 5.0)
                                  .build(),
                          OptionStrike.builder()
                                  .strikePrice(BigDecimal.valueOf(23750))
                                  .putLTP(BigDecimal.valueOf(6.0))    // BUY PE current (entry 5.0)
                                  .build()
                  ))
                  .build();

          when(optionChainService.fetchOptionChain("NIFTY50"))
                  .thenReturn(Mono.just(optionChain));

          // Mock paperTradeRepository.save() to echo back the mutated trade
          when(paperTradeRepository.save(any(PaperTrade.class)))
                  .thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));

          // NOTE: findById() is NOT stubbed — it should never be called

          // Act & Assert
          StepVerifier.create(engine.markToMarket(trade))
                  .expectNextMatches(result -> {
                      // Hand-calculated expected values:
                      // legPnl_SELL_CE = (30.0 - 32.0) × 1 = -2.0
                      // legPnl_SELL_PE = (20.0 - 21.0) × 1 = -1.0
                      // legPnl_BUY_CE = (6.0 - 5.0) × 1 = 1.0
                      // legPnl_BUY_PE = (6.0 - 5.0) × 1 = 1.0
                      // totalPnl = -2.0 - 1.0 + 1.0 + 1.0 = -1.0
                      // stopLossThreshold = 10.0 × 1 × -1 = -10.0
                      // pnl (-1.0) < stopLossThreshold (-10.0)? NO
                      // profitTarget = 5.0 × 1 = 5.0
                      // pnl (-1.0) >= profitTarget (5.0)? NO
                      // → No trigger
                      return result.getStatus() == TradeStatus.OPEN &&
                              result.getUnrealizedPnl().compareTo(BigDecimal.valueOf(-1.0)) == 0;
                  })
                  .verifyComplete();

          // Verify save() was called directly (not through closeExpiryDayTrade)
          verify(paperTradeRepository, times(1)).save(any(PaperTrade.class));

          // Verify findById() was NOT called (unique to closeExpiryDayTrade path)
          verify(paperTradeRepository, never()).findById(any(UUID.class));

          // Verify alert was NOT sent (no close)
          verify(telegramNotificationBot, never()).sendAlert(any());
      }

      /**
       * Test C: Fallback formula path (null risk snapshot fields) → uses config-based thresholds.
       * <p>
       * Setup:
       * - entryCredit = 40.0 points
       * - stopDistancePoints = null (triggers fallback)
       * - targetThreshold = null (triggers fallback)
       * - stopLossPercent = 20%, targetProfitPercent = 10% (from config)
       * - 2 legs: SELL CE @ 23950 (entry 25.0), SELL PE @ 23850 (entry 15.0)
       * - lots = 1
       * <p>
       * Hand-calculated P&L via calculateExpiryDayStranglePnl():
       * SELL CE: entry 25.0, current 35.0 → legPnl = (25.0 - 35.0) × 1 = -10.0
       * SELL PE: entry 15.0, current 20.0 → legPnl = (15.0 - 20.0) × 1 = -5.0
       * totalPnl = -10.0 - 5.0 = -15.0
       * <p>
       * Fallback exit check:
       * stopLossThreshold = entryCredit × lots × slPct × -1 = 40.0 × 1 × 0.20 × -1 = -8.0
       * pnl (-15.0) < stopLossThreshold (-8.0)? YES → STOP_LOSS triggered ✓
       */
      @Test
      @DisplayName("markToMarket: Fallback formula (null risk fields) → uses config thresholds, STOP_LOSS triggered")
      void markToMarket_FallbackFormula_StopLossTriggered() {
          // Arrange: Create trade with null risk snapshot fields
          PaperTrade trade = new PaperTrade();
          trade.setId(UUID.randomUUID());
          trade.setSymbol("NIFTY50");
          trade.setStrategyName("EXPIRY_DAY_STRANGLE");
          trade.setStatus(TradeStatus.OPEN);
          trade.setEntryCredit(BigDecimal.valueOf(40.0));
          trade.setCurrentValue(BigDecimal.valueOf(40.0));
          trade.setUnrealizedPnl(BigDecimal.valueOf(0.0));
          trade.setStopDistancePoints(null);  // Triggers fallback
          trade.setTargetThreshold(null);     // Triggers fallback
          trade.setEntryTime(LocalDateTime.now());
          trade.setTradeDate(LocalDate.now());

          // 2 legs: SELL CE @ 23950 (entry 25.0), SELL PE @ 23850 (entry 15.0)
          List<TradeLeg> legs = List.of(
                  TradeLeg.builder()
                          .action(Action.SELL)
                          .optionType(OptionType.CE)
                          .strikePrice(BigDecimal.valueOf(23950.0))
                          .limitPrice(BigDecimal.valueOf(25.0))
                          .quantity(1)
                          .expiryDate(LocalDate.now().plusDays(7))
                          .build(),
                  TradeLeg.builder()
                          .action(Action.SELL)
                          .optionType(OptionType.PE)
                          .strikePrice(BigDecimal.valueOf(23850.0))
                          .limitPrice(BigDecimal.valueOf(15.0))
                          .quantity(1)
                          .expiryDate(LocalDate.now().plusDays(7))
                          .build()
          );
          trade.setLegs(legs);

          // Mock option chain with LTPs that produce totalPnl = -15.0
          OptionChain optionChain = OptionChain.builder()
                  .symbol("NIFTY50")
                  .expiryDate(LocalDate.of(2026, 7, 9))
                  .strikes(List.of(
                          OptionStrike.builder()
                                  .strikePrice(BigDecimal.valueOf(23950))
                                  .callLTP(BigDecimal.valueOf(35.0))  // SELL CE current (entry 25.0)
                                  .build(),
                          OptionStrike.builder()
                                  .strikePrice(BigDecimal.valueOf(23850))
                                  .putLTP(BigDecimal.valueOf(20.0))   // SELL PE current (entry 15.0)
                                  .build()
                  ))
                  .build();

          when(optionChainService.fetchOptionChain("NIFTY50"))
                  .thenReturn(Mono.just(optionChain));

           // Mock strategiesConfig.getStrategy() to return config with stopLossPercent=20%, targetProfitPercent=10%
           com.trademesh.strategy.config.StrategyProperties mockConfig = mock(com.trademesh.strategy.config.StrategyProperties.class);
           when(mockConfig.getStopLossPercent()).thenReturn(20.0);
           lenient().when(mockConfig.getTargetProfitPercent()).thenReturn(10.0);
           when(strategiesConfig.getStrategy("EXPIRY_DAY_STRANGLE")).thenReturn(mockConfig);

          // Mock paperTradeRepository.findById() for closeExpiryDayTrade's internal re-fetch
          PaperTrade freshTrade = new PaperTrade();
          freshTrade.setId(trade.getId());
          freshTrade.setSymbol("NIFTY50");
          freshTrade.setStrategyName("EXPIRY_DAY_STRANGLE");
          freshTrade.setStatus(TradeStatus.OPEN);
          freshTrade.setEntryCredit(BigDecimal.valueOf(40.0));
          freshTrade.setCurrentValue(BigDecimal.valueOf(40.0));
          freshTrade.setUnrealizedPnl(BigDecimal.valueOf(0.0));
          freshTrade.setStopDistancePoints(null);
          freshTrade.setTargetThreshold(null);
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
                      // Hand-calculated expected values:
                      // legPnl_SELL_CE = (25.0 - 35.0) × 1 = -10.0
                      // legPnl_SELL_PE = (15.0 - 20.0) × 1 = -5.0
                      // totalPnl = -10.0 - 5.0 = -15.0
                      // stopLossThreshold = 40.0 × 1 × 0.20 × -1 = -8.0
                      // pnl (-15.0) < stopLossThreshold (-8.0)? YES → STOP_LOSS
                      return result.getStatus() == TradeStatus.CLOSED &&
                              result.getExitReason().equals("STOP_LOSS") &&
                              result.getRealizedPnl().compareTo(BigDecimal.valueOf(-15.0)) == 0;
                  })
                  .verifyComplete();

          // Verify closeExpiryDayTrade path was invoked (findById called)
          verify(paperTradeRepository, times(1)).findById(trade.getId());
          // Verify save was called (from closeExpiryDayTrade)
          verify(paperTradeRepository, times(1)).save(any(PaperTrade.class));
          // Verify alert was sent
          verify(telegramNotificationBot, times(1)).sendAlert(any());
          // Verify config was accessed (fallback path)
          verify(strategiesConfig, times(1)).getStrategy("EXPIRY_DAY_STRANGLE");
      }
}
