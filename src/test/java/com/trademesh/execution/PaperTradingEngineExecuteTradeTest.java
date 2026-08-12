package com.trademesh.execution;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.trademesh.alert.TelegramNotificationBot;
import com.trademesh.config.LotSizeService;
import com.trademesh.intelligence.vix.VixMonitorService;
import com.trademesh.strategy.StrategyResult;
import com.trademesh.strategy.TradeLeg;
import com.trademesh.strategy.TradeSignal;
import com.trademesh.strategy.config.StrategiesConfig;
import com.trademesh.strategy.enums.Action;
import com.trademesh.strategy.enums.Confidence;
import com.trademesh.strategy.enums.OptionType;
import com.trademesh.strategy.enums.SignalType;
import com.trademesh.trading.MarketCalendarService;
import com.trademesh.zerodha.options.OptionChainService;
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
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * Unit tests for executeTrade() in PaperTradingEngine.
 * <p>
 * Tests verify that executeTrade(StrategyResult result) correctly:
 * - Executes a valid, executable Iron Condor signal with Phase 1 fields
 * - Copies all Phase 1 fields (stopDistancePoints, targetThreshold, plannedRiskPerLot,
 *   atrPercentile, confidenceScore) from signal to trade
 * - Populates riskSnapshot as non-null JSON
 * - Returns Mono.empty() for non-executable signals
 * - Handles null Phase 1 fields gracefully (no NPE)
 * - Returns Mono.empty() when an OPEN trade already exists for the strategy
 * <p>
 * Uses real builder-constructed StrategyResult/TradeSignal/TradeLeg objects (not mocked).
 * Mocks only external dependencies: paperTradeRepository, telegramNotificationBot, objectMapper.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PaperTradingEngine executeTrade() Tests")
class PaperTradingEngineExecuteTradeTest {

    private PaperTradingEngine engine;

    @Mock
    private PaperTradeRepository paperTradeRepository;

    @Mock
    private ReactiveRedisTemplate<String, String> redisTemplate;

    @Mock
    private TelegramNotificationBot telegramNotificationBot;

    @Mock
    private ObjectMapper objectMapper;

    @Mock
    private StrategiesConfig strategiesConfig;

    @Mock
    private LotSizeService lotSizeService;

    @Mock
    private VixMonitorService vixMonitorService;

    @Mock
    private MarketCalendarService marketCalendarService;

    @Mock
    private OptionChainService optionChainService;

    @Mock
    private com.trademesh.zerodha.options.KiteMarginService kiteMarginService;

    @Mock
    private com.trademesh.zerodha.auth.ZerodhaAuthService authService;

    @BeforeEach
    void setUp() {
        engine = new PaperTradingEngine(
                paperTradeRepository,
                redisTemplate,
                telegramNotificationBot,
                objectMapper,
                strategiesConfig,
                lotSizeService,
                vixMonitorService,
                marketCalendarService,
                optionChainService,
                kiteMarginService,
                authService
        );
        
        // Stub kiteMarginService for margin shadow-logging
        lenient().when(kiteMarginService.fetchBasketMargin(any(), any(), any())).thenReturn(Mono.empty());

        // Stub authService for margin shadow-logging
        lenient().when(authService.getAccessToken()).thenReturn(Mono.just("fake-token-for-test"));

        // Default lenient mocking for alert sending
        lenient().when(telegramNotificationBot.sendAlert(any()))
                .thenReturn(Mono.empty());
    }

    @Test
    @DisplayName("Test 1: Valid executable Iron Condor signal with Phase 1 fields")
    void testExecuteTradeValidIronCondorWithPhase1Fields() throws JsonProcessingException {
        // Arrange: Build a real Iron Condor signal with Phase 1 fields
        LocalDateTime now = LocalDateTime.now(ZoneId.of("Asia/Kolkata"));
        LocalDate expiryDate = LocalDate.now(ZoneId.of("Asia/Kolkata")).plusDays(7);

        TradeLeg shortCallLeg = TradeLeg.builder()
                .instrument("NIFTY50 19400 CE")
                .action(Action.SELL)
                .optionType(OptionType.CE)
                .strikePrice(BigDecimal.valueOf(19400))
                .expiryDate(expiryDate)
                .quantity(1)
                .limitPrice(BigDecimal.valueOf(50))
                .build();

        TradeLeg shortPutLeg = TradeLeg.builder()
                .instrument("NIFTY50 19200 PE")
                .action(Action.SELL)
                .optionType(OptionType.PE)
                .strikePrice(BigDecimal.valueOf(19200))
                .expiryDate(expiryDate)
                .quantity(1)
                .limitPrice(BigDecimal.valueOf(45))
                .build();

        TradeLeg longCallLeg = TradeLeg.builder()
                .instrument("NIFTY50 19500 CE")
                .action(Action.BUY)
                .optionType(OptionType.CE)
                .strikePrice(BigDecimal.valueOf(19500))
                .expiryDate(expiryDate)
                .quantity(1)
                .limitPrice(BigDecimal.valueOf(20))
                .build();

        TradeLeg longPutLeg = TradeLeg.builder()
                .instrument("NIFTY50 19100 PE")
                .action(Action.BUY)
                .optionType(OptionType.PE)
                .strikePrice(BigDecimal.valueOf(19100))
                .expiryDate(expiryDate)
                .quantity(1)
                .limitPrice(BigDecimal.valueOf(15))
                .build();

        List<TradeLeg> legs = List.of(shortCallLeg, shortPutLeg, longCallLeg, longPutLeg);

        // Phase 1 fields
        BigDecimal stopDistancePoints = BigDecimal.valueOf(25.5);
        BigDecimal targetThreshold = BigDecimal.valueOf(50.0);
        BigDecimal plannedRiskPerLot = BigDecimal.valueOf(1650);
        Integer atrPercentile = 65;
        Integer confidenceScore = 78;

        TradeSignal signal = TradeSignal.builder()
                .signalId(UUID.randomUUID())
                .symbol("NIFTY50")
                .strategyName("IRON_CONDOR")
                .signalType(SignalType.ENTER)
                .legs(legs)
                .expectedCredit(75.0)
                .maxLoss(1650.0)
                .confidence(Confidence.HIGH)
                .reason("Test Iron Condor signal")
                .stopDistancePoints(stopDistancePoints)
                .targetThreshold(targetThreshold)
                .plannedRiskPerLot(plannedRiskPerLot)
                .atrPercentile(atrPercentile)
                .confidenceScore(confidenceScore)
                .build();

        StrategyResult result = StrategyResult.builder()
                .signal(signal)
                .isExecutable(true)
                .build();

        // Mock repository: no existing OPEN trade
        when(paperTradeRepository.existsByStrategyNameAndStatus("IRON_CONDOR", TradeStatus.OPEN))
                .thenReturn(Mono.just(false));

        // Mock repository: save returns the trade with ID
        when(paperTradeRepository.save(any(PaperTrade.class)))
                .thenAnswer(invocation -> {
                    PaperTrade trade = invocation.getArgument(0);
                    trade.setId(UUID.randomUUID());
                    return Mono.just(trade);
                });

        // Mock objectMapper: buildRiskSnapshot() uses it
        when(objectMapper.writeValueAsString(any()))
                .thenReturn("{\"strategyName\":\"IRON_CONDOR\",\"entryCredit\":75.0}");

        // Act
        Mono<PaperTrade> result_mono = engine.executeTrade(result);

        // Assert
        StepVerifier.create(result_mono)
                .assertNext(trade -> {
                    // Pre-existing fields
                    assertEquals("NIFTY50", trade.getSymbol());
                    assertEquals("IRON_CONDOR", trade.getStrategyName());
                    assertEquals(TradeStatus.OPEN, trade.getStatus());
                    assertEquals(4, trade.getLegs().size());
                    assertEquals(BigDecimal.valueOf(75.0), trade.getEntryCredit());
                    assertEquals(BigDecimal.valueOf(75.0), trade.getCurrentValue());
                    assertEquals(BigDecimal.ZERO, trade.getUnrealizedPnl());
                    assertEquals(BigDecimal.valueOf(1650.0), trade.getMaxLoss());
                    assertNotNull(trade.getEntryTime());
                    assertNotNull(trade.getCreatedAt());
                    assertNotNull(trade.getUpdatedAt());

                    // Phase 1 fields
                    assertEquals(stopDistancePoints, trade.getStopDistancePoints());
                    assertEquals(targetThreshold, trade.getTargetThreshold());
                    assertEquals(plannedRiskPerLot, trade.getPlannedRiskPerLot());
                    assertEquals(atrPercentile, trade.getAtrPercentile());
                    assertEquals(confidenceScore, trade.getConfidenceScore());

                    // Risk snapshot
                    assertNotNull(trade.getRiskSnapshot());
                    assertTrue(trade.getRiskSnapshot().contains("IRON_CONDOR"));
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("Test 2: Non-executable signal returns Mono.empty()")
    void testExecuteTradeNonExecutableSignal() {
        // Arrange
        TradeSignal signal = TradeSignal.builder()
                .signalId(UUID.randomUUID())
                .symbol("NIFTY50")
                .strategyName("IRON_CONDOR")
                .signalType(SignalType.ENTER)
                .legs(List.of())
                .expectedCredit(75.0)
                .maxLoss(1650.0)
                .confidence(Confidence.HIGH)
                .reason("Test signal")
                .build();

        StrategyResult result = StrategyResult.builder()
                .signal(signal)
                .isExecutable(false)
                .skipReason("Confidence too low")
                .build();

        // Act
        Mono<PaperTrade> result_mono = engine.executeTrade(result);

        // Assert
        StepVerifier.create(result_mono)
                .verifyComplete();
    }

    @Test
    @DisplayName("Test 3: Null Phase 1 fields handled gracefully (no NPE)")
    void testExecuteTradeNullPhase1Fields() throws JsonProcessingException {
        // Arrange: Signal with null Phase 1 fields (e.g., Expiry Day strategy)
        LocalDateTime now = LocalDateTime.now(ZoneId.of("Asia/Kolkata"));
        LocalDate expiryDate = LocalDate.now(ZoneId.of("Asia/Kolkata")).plusDays(1);

        TradeLeg callLeg = TradeLeg.builder()
                .instrument("NIFTY50 19400 CE")
                .action(Action.SELL)
                .optionType(OptionType.CE)
                .strikePrice(BigDecimal.valueOf(19400))
                .expiryDate(expiryDate)
                .quantity(1)
                .limitPrice(BigDecimal.valueOf(50))
                .build();

        TradeLeg putLeg = TradeLeg.builder()
                .instrument("NIFTY50 19200 PE")
                .action(Action.SELL)
                .optionType(OptionType.PE)
                .strikePrice(BigDecimal.valueOf(19200))
                .expiryDate(expiryDate)
                .quantity(1)
                .limitPrice(BigDecimal.valueOf(45))
                .build();

        TradeSignal signal = TradeSignal.builder()
                .signalId(UUID.randomUUID())
                .symbol("NIFTY50")
                .strategyName("EXPIRY_DAY_STRANGLE")
                .signalType(SignalType.ENTER)
                .legs(List.of(callLeg, putLeg))
                .expectedCredit(95.0)
                .maxLoss(1905.0)
                .confidence(Confidence.MEDIUM)
                .reason("Test strangle signal")
                // Phase 1 fields are null
                .stopDistancePoints(null)
                .targetThreshold(null)
                .plannedRiskPerLot(null)
                .atrPercentile(null)
                .confidenceScore(null)
                .build();

        StrategyResult result = StrategyResult.builder()
                .signal(signal)
                .isExecutable(true)
                .build();

        // Mock repository
        when(paperTradeRepository.existsByStrategyNameAndStatus("EXPIRY_DAY_STRANGLE", TradeStatus.OPEN))
                .thenReturn(Mono.just(false));

        when(paperTradeRepository.save(any(PaperTrade.class)))
                .thenAnswer(invocation -> {
                    PaperTrade trade = invocation.getArgument(0);
                    trade.setId(UUID.randomUUID());
                    return Mono.just(trade);
                });

        when(objectMapper.writeValueAsString(any()))
                .thenReturn("{\"strategyName\":\"EXPIRY_DAY_STRANGLE\"}");

        // Act
        Mono<PaperTrade> result_mono = engine.executeTrade(result);

        // Assert
        StepVerifier.create(result_mono)
                .assertNext(trade -> {
                    assertEquals("EXPIRY_DAY_STRANGLE", trade.getStrategyName());
                    // Phase 1 fields should be null (no NPE)
                    assertNull(trade.getStopDistancePoints());
                    assertNull(trade.getTargetThreshold());
                    assertNull(trade.getPlannedRiskPerLot());
                    assertNull(trade.getAtrPercentile());
                    assertNull(trade.getConfidenceScore());
                    assertNotNull(trade.getRiskSnapshot());
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("Test 4: Existing OPEN trade returns Mono.empty()")
    void testExecuteTradeExistingOpenTrade() {
        // Arrange
        TradeSignal signal = TradeSignal.builder()
                .signalId(UUID.randomUUID())
                .symbol("NIFTY50")
                .strategyName("IRON_CONDOR")
                .signalType(SignalType.ENTER)
                .legs(List.of())
                .expectedCredit(75.0)
                .maxLoss(1650.0)
                .confidence(Confidence.HIGH)
                .reason("Test signal")
                .build();

        StrategyResult result = StrategyResult.builder()
                .signal(signal)
                .isExecutable(true)
                .build();

        // Mock repository: OPEN trade already exists
        when(paperTradeRepository.existsByStrategyNameAndStatus("IRON_CONDOR", TradeStatus.OPEN))
                .thenReturn(Mono.just(true));

        // Act
        Mono<PaperTrade> result_mono = engine.executeTrade(result);

        // Assert
        StepVerifier.create(result_mono)
                .verifyComplete();
    }
}
