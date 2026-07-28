package com.trademesh.strategy.ironcondor;

import com.trademesh.config.LotSizeService;
import com.trademesh.intelligence.MarketCondition;
import com.trademesh.intelligence.MarketIntelligence;
import com.trademesh.intelligence.MarketMetrics;
import com.trademesh.intelligence.MarketMetricsService;
import com.trademesh.intelligence.vix.VixData;
import com.trademesh.intelligence.vix.VixMonitorService;
import com.trademesh.strategy.StrategyResult;
import com.trademesh.strategy.config.StrategiesConfig;
import com.trademesh.strategy.config.StrategyProperties;
import com.trademesh.trading.MarketCalendarService;
import com.trademesh.zerodha.options.OptionChain;
import com.trademesh.zerodha.options.OptionChainService;
import com.trademesh.zerodha.options.OptionStrike;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

/**
 * Integration tests for IronCondorStrategy's ATR percentile veto gate.
 * Instantiates the real strategy with mocked dependencies and calls evaluate()
 * to verify the veto fires/doesn't fire at boundary values and with different metrics.
 */
class IronCondorAtrPercentileVetoTest {

    @Mock
    private StrategiesConfig strategiesConfig;

    @Mock
    private MarketMetricsService marketMetricsService;

    @Mock
    private VixMonitorService vixMonitorService;

    @Mock
    private LotSizeService lotSizeService;

    @Mock
    private com.trademesh.trading.MarketCalendarService marketCalendarService;

    @Mock
    private com.trademesh.zerodha.options.OptionChainService optionChainService;

    private IronCondorStrategy strategy;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        strategy = new IronCondorStrategy(
                strategiesConfig,
                marketMetricsService,
                optionChainService,
                vixMonitorService,
                lotSizeService,
                marketCalendarService
        );
    }

    /**
     * Test 1: atrPercentile = 91 (exceeds default max of 90) → veto fires.
     * Expected: isExecutable=false, skipReason="ATR percentile too high"
     */
    @Test
    void testAtrPercentileVeto_At91_VetoFires() {
        StrategyProperties cfg = createMockStrategyProperties();
        when(strategiesConfig.getStrategy("IRON_CONDOR")).thenReturn(cfg);

        // VIX = 15.0 (passes VIX gate)
        VixData vixData = VixData.builder().currentVix(15.0).build();
        when(vixMonitorService.fetchCurrentVix()).thenReturn(Mono.just(vixData));

        // Market metrics with atrPercentile = 91 (exceeds max of 90)
        MarketMetrics metrics = MarketMetrics.builder()
                .atrPercentile(BigDecimal.valueOf(91.0))
                .ivr(BigDecimal.valueOf(55.0))
                .ivHvRatio(BigDecimal.valueOf(1.5))
                .pcr(BigDecimal.valueOf(1.0))
                .atrPct(BigDecimal.valueOf(0.7))
                .currentAtmIv(BigDecimal.valueOf(15.0))
                .build();
        when(marketMetricsService.calculateMetrics("NIFTY50")).thenReturn(Mono.just(metrics));

        // Option chain
        OptionChain chain = createMockOptionChain();
        when(optionChainService.fetchOptionChain("NIFTY50")).thenReturn(Mono.just(chain));

        // Market calendar
        when(marketCalendarService.countTradingDays(LocalDate.now(), chain.getExpiryDate()))
                .thenReturn(2);

        // Create market intelligence
        MarketIntelligence intelligence = MarketIntelligence.builder()
                .spotPrice(50000.0)
                .vixData(vixData)
                .optionChain(chain)
                .marketCondition(MarketCondition.SIDEWAYS)
                .build();

        // Execute and verify veto fires
        StepVerifier.create(strategy.evaluate(intelligence))
                .assertNext(result -> {
                    assertFalse(result.getIsExecutable(), "atrPercentile=91 should trigger veto");
                    assertEquals("ATR percentile too high", result.getSkipReason(),
                            "skipReason should be 'ATR percentile too high'");
                })
                .verifyComplete();
    }

    /**
     * Test 2: atrPercentile = 90.0 (exactly at max) → veto does NOT fire.
     * Expected: isExecutable=true (or if false, skipReason is NOT "ATR percentile too high")
     */
    @Test
    void testAtrPercentileVeto_At90_VetoDoesNotFire() {
        StrategyProperties cfg = createMockStrategyProperties();
        when(strategiesConfig.getStrategy("IRON_CONDOR")).thenReturn(cfg);

        // VIX = 15.0 (passes VIX gate)
        VixData vixData = VixData.builder().currentVix(15.0).build();
        when(vixMonitorService.fetchCurrentVix()).thenReturn(Mono.just(vixData));

        // Market metrics with atrPercentile = 90.0 (exactly at max, should NOT trigger veto)
        MarketMetrics metrics = MarketMetrics.builder()
                .atrPercentile(BigDecimal.valueOf(90.0))
                .ivr(BigDecimal.valueOf(55.0))
                .ivHvRatio(BigDecimal.valueOf(1.5))
                .pcr(BigDecimal.valueOf(1.0))
                .atrPct(BigDecimal.valueOf(0.7))
                .currentAtmIv(BigDecimal.valueOf(15.0))
                .build();
        when(marketMetricsService.calculateMetrics("NIFTY50")).thenReturn(Mono.just(metrics));

        // Option chain
        OptionChain chain = createMockOptionChain();
        when(optionChainService.fetchOptionChain("NIFTY50")).thenReturn(Mono.just(chain));

        // Market calendar
        when(marketCalendarService.countTradingDays(LocalDate.now(), chain.getExpiryDate()))
                .thenReturn(2);

        // LotSizeService mocking for executable result
        when(lotSizeService.getLotSize("NIFTY50")).thenReturn(65);
        when(lotSizeService.getPositionSize(
                ArgumentMatchers.argThat(bd -> bd != null && bd.compareTo(new BigDecimal("2080")) == 0),
                ArgumentMatchers.eq(15.0),
                ArgumentMatchers.eq(75),
                ArgumentMatchers.eq(51)
        )).thenReturn(10);

        // Create market intelligence
        MarketIntelligence intelligence = MarketIntelligence.builder()
                .spotPrice(50000.0)
                .vixData(vixData)
                .optionChain(chain)
                .marketCondition(MarketCondition.SIDEWAYS)
                .build();

        // Execute and verify veto does NOT fire
        StepVerifier.create(strategy.evaluate(intelligence))
                .assertNext(result -> {
                    // Either isExecutable is true, or if false, skipReason is NOT the atrPercentile veto
                    if (!result.getIsExecutable()) {
                        assertNotEquals("ATR percentile too high", result.getSkipReason(),
                                "atrPercentile=90.0 should NOT trigger the percentile veto");
                    }
                })
                .verifyComplete();
    }

    /**
     * Test 3: atrPercentile = 89 (below max) → veto does NOT fire.
     * Expected: isExecutable=true (or if false, skipReason is NOT "ATR percentile too high")
     */
    @Test
    void testAtrPercentileVeto_At89_VetoDoesNotFire() {
        StrategyProperties cfg = createMockStrategyProperties();
        when(strategiesConfig.getStrategy("IRON_CONDOR")).thenReturn(cfg);

        // VIX = 15.0 (passes VIX gate)
        VixData vixData = VixData.builder().currentVix(15.0).build();
        when(vixMonitorService.fetchCurrentVix()).thenReturn(Mono.just(vixData));

        // Market metrics with atrPercentile = 89 (below max)
        MarketMetrics metrics = MarketMetrics.builder()
                .atrPercentile(BigDecimal.valueOf(89.0))
                .ivr(BigDecimal.valueOf(55.0))
                .ivHvRatio(BigDecimal.valueOf(1.5))
                .pcr(BigDecimal.valueOf(1.0))
                .atrPct(BigDecimal.valueOf(0.7))
                .currentAtmIv(BigDecimal.valueOf(15.0))
                .build();
        when(marketMetricsService.calculateMetrics("NIFTY50")).thenReturn(Mono.just(metrics));

        // Option chain
        OptionChain chain = createMockOptionChain();
        when(optionChainService.fetchOptionChain("NIFTY50")).thenReturn(Mono.just(chain));

        // Market calendar
        when(marketCalendarService.countTradingDays(LocalDate.now(), chain.getExpiryDate()))
                .thenReturn(2);

        // LotSizeService mocking for executable result
        when(lotSizeService.getLotSize("NIFTY50")).thenReturn(65);
        when(lotSizeService.getPositionSize(
                ArgumentMatchers.argThat(bd -> bd != null && bd.compareTo(new BigDecimal("2080")) == 0),
                ArgumentMatchers.eq(15.0),
                ArgumentMatchers.eq(75),
                ArgumentMatchers.eq(51)
        )).thenReturn(10);

        // Create market intelligence
        MarketIntelligence intelligence = MarketIntelligence.builder()
                .spotPrice(50000.0)
                .vixData(vixData)
                .optionChain(chain)
                .marketCondition(MarketCondition.SIDEWAYS)
                .build();

        // Execute and verify veto does NOT fire
        StepVerifier.create(strategy.evaluate(intelligence))
                .assertNext(result -> {
                    // Either isExecutable is true, or if false, skipReason is NOT the atrPercentile veto
                    if (!result.getIsExecutable()) {
                        assertNotEquals("ATR percentile too high", result.getSkipReason(),
                                "atrPercentile=89 should NOT trigger the percentile veto");
                    }
                })
                .verifyComplete();
    }

    /**
     * Test 4: atrPercentile = null → veto does NOT fire.
     * Expected: isExecutable=true (or if false, skipReason is NOT "ATR percentile too high")
     */
    @Test
    void testAtrPercentileVeto_Null_VetoDoesNotFire() {
        StrategyProperties cfg = createMockStrategyProperties();
        when(strategiesConfig.getStrategy("IRON_CONDOR")).thenReturn(cfg);

        // VIX = 15.0 (passes VIX gate)
        VixData vixData = VixData.builder().currentVix(15.0).build();
        when(vixMonitorService.fetchCurrentVix()).thenReturn(Mono.just(vixData));

        // Market metrics with atrPercentile = null
        MarketMetrics metrics = MarketMetrics.builder()
                .atrPercentile(null)
                .ivr(BigDecimal.valueOf(55.0))
                .ivHvRatio(BigDecimal.valueOf(1.5))
                .pcr(BigDecimal.valueOf(1.0))
                .atrPct(BigDecimal.valueOf(0.7))
                .currentAtmIv(BigDecimal.valueOf(15.0))
                .build();
        when(marketMetricsService.calculateMetrics("NIFTY50")).thenReturn(Mono.just(metrics));

        // Option chain
        OptionChain chain = createMockOptionChain();
        when(optionChainService.fetchOptionChain("NIFTY50")).thenReturn(Mono.just(chain));

        // Market calendar
        when(marketCalendarService.countTradingDays(LocalDate.now(), chain.getExpiryDate()))
                .thenReturn(2);

        // LotSizeService mocking for executable result
        when(lotSizeService.getLotSize("NIFTY50")).thenReturn(65);
        when(lotSizeService.getPositionSize(
                ArgumentMatchers.argThat(bd -> bd != null && bd.compareTo(new BigDecimal("2080")) == 0),
                ArgumentMatchers.eq(15.0),
                ArgumentMatchers.eq(75),
                ArgumentMatchers.eq(51)
        )).thenReturn(10);

        // Create market intelligence
        MarketIntelligence intelligence = MarketIntelligence.builder()
                .spotPrice(50000.0)
                .vixData(vixData)
                .optionChain(chain)
                .marketCondition(MarketCondition.SIDEWAYS)
                .build();

        // Execute and verify veto does NOT fire
        StepVerifier.create(strategy.evaluate(intelligence))
                .assertNext(result -> {
                    // Either isExecutable is true, or if false, skipReason is NOT the atrPercentile veto
                    if (!result.getIsExecutable()) {
                        assertNotEquals("ATR percentile too high", result.getSkipReason(),
                                "atrPercentile=null should NOT trigger the percentile veto");
                    }
                })
                .verifyComplete();
    }

    /**
     * Test 5: High atrPct (>1.0), normal atrPercentile (50) → only atrPct gate fires.
     * Expected: isExecutable=false, skipReason="ATR% too high"
     * Verifies the two gates are independent.
     */
    @Test
    void testTwoGatesIndependent_HighAtrPctLowPercentile_AtrPctGateFires() {
        StrategyProperties cfg = createMockStrategyProperties();
        when(strategiesConfig.getStrategy("IRON_CONDOR")).thenReturn(cfg);

        // VIX = 15.0 (passes VIX gate)
        VixData vixData = VixData.builder().currentVix(15.0).build();
        when(vixMonitorService.fetchCurrentVix()).thenReturn(Mono.just(vixData));

        // Market metrics with high atrPct but normal atrPercentile
        MarketMetrics metrics = MarketMetrics.builder()
                .atrPct(BigDecimal.valueOf(1.2))           // exceeds atr-pct-max (1.0)
                .atrPercentile(BigDecimal.valueOf(50.0))   // below atr-percentile-max (90.0)
                .ivr(BigDecimal.valueOf(55.0))
                .ivHvRatio(BigDecimal.valueOf(1.5))
                .pcr(BigDecimal.valueOf(1.0))
                .currentAtmIv(BigDecimal.valueOf(15.0))
                .build();
        when(marketMetricsService.calculateMetrics("NIFTY50")).thenReturn(Mono.just(metrics));

        // Option chain
        OptionChain chain = createMockOptionChain();
        when(optionChainService.fetchOptionChain("NIFTY50")).thenReturn(Mono.just(chain));

        // Market calendar
        when(marketCalendarService.countTradingDays(LocalDate.now(), chain.getExpiryDate()))
                .thenReturn(2);

        // Create market intelligence
        MarketIntelligence intelligence = MarketIntelligence.builder()
                .spotPrice(50000.0)
                .vixData(vixData)
                .optionChain(chain)
                .marketCondition(MarketCondition.SIDEWAYS)
                .build();

        // Execute and verify atrPct gate fires, not atrPercentile gate
        StepVerifier.create(strategy.evaluate(intelligence))
                .assertNext(result -> {
                    assertFalse(result.getIsExecutable(), "High atrPct should trigger a veto");
                    assertEquals("ATR% too high", result.getSkipReason(),
                            "skipReason should be 'ATR% too high', not the percentile veto");
                })
                .verifyComplete();
    }

    /**
     * Test 6: Normal atrPct (<1.0), high atrPercentile (95) → only atrPercentile gate fires.
     * Expected: isExecutable=false, skipReason="ATR percentile too high"
     * Verifies the two gates are independent.
     */
    @Test
    void testTwoGatesIndependent_LowAtrPctHighPercentile_PercentileGateFires() {
        StrategyProperties cfg = createMockStrategyProperties();
        when(strategiesConfig.getStrategy("IRON_CONDOR")).thenReturn(cfg);

        // VIX = 15.0 (passes VIX gate)
        VixData vixData = VixData.builder().currentVix(15.0).build();
        when(vixMonitorService.fetchCurrentVix()).thenReturn(Mono.just(vixData));

        // Market metrics with normal atrPct but high atrPercentile
        MarketMetrics metrics = MarketMetrics.builder()
                .atrPct(BigDecimal.valueOf(0.7))           // below atr-pct-max (1.0)
                .atrPercentile(BigDecimal.valueOf(95.0))   // exceeds atr-percentile-max (90.0)
                .ivr(BigDecimal.valueOf(55.0))
                .ivHvRatio(BigDecimal.valueOf(1.5))
                .pcr(BigDecimal.valueOf(1.0))
                .currentAtmIv(BigDecimal.valueOf(15.0))
                .build();
        when(marketMetricsService.calculateMetrics("NIFTY50")).thenReturn(Mono.just(metrics));

        // Option chain
        OptionChain chain = createMockOptionChain();
        when(optionChainService.fetchOptionChain("NIFTY50")).thenReturn(Mono.just(chain));

        // Market calendar
        when(marketCalendarService.countTradingDays(LocalDate.now(), chain.getExpiryDate()))
                .thenReturn(2);

        // Create market intelligence
        MarketIntelligence intelligence = MarketIntelligence.builder()
                .spotPrice(50000.0)
                .vixData(vixData)
                .optionChain(chain)
                .marketCondition(MarketCondition.SIDEWAYS)
                .build();

        // Execute and verify atrPercentile gate fires, not atrPct gate
        StepVerifier.create(strategy.evaluate(intelligence))
                .assertNext(result -> {
                    assertFalse(result.getIsExecutable(), "High atrPercentile should trigger a veto");
                    assertEquals("ATR percentile too high", result.getSkipReason(),
                            "skipReason should be 'ATR percentile too high', not the atrPct veto");
                })
                .verifyComplete();
    }

    // ===== Helper methods =====

    private StrategyProperties createMockStrategyProperties() {
        StrategyProperties props = new StrategyProperties();
        props.setEnabled(true);
        props.setMaxLots(20);
        props.setMaxVix(30.0);
        props.setMinVix(10.0);
        props.setLatestEntryTime("23:59");
        props.setTargetProfitPercent(50.0);
        props.setStopLossPercent(100.0);
        props.setAllowedDays(java.util.List.of("MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY"));
        props.setExtra(java.util.Map.of("min-score", 51));
        return props;
    }

    private OptionChain createMockOptionChain() {
        LocalDate expiryDate = LocalDate.now().plusDays(2);
        List<OptionStrike> strikes = new ArrayList<>();

        // Create mock strikes around 50000 with realistic LTPs
        for (int i = 48000; i <= 52000; i += 100) {
            OptionStrike strike = new OptionStrike();
            strike.setStrikePrice(BigDecimal.valueOf(i));

            // Distance from spot (50000)
            int distance = Math.abs(i - 50000);

            // LTP decreases with distance from spot
            double ltp = Math.max(1.0, 150.0 - (distance / 100.0) * 8.0);

            strike.setCallLTP(BigDecimal.valueOf(ltp));
            strike.setPutLTP(BigDecimal.valueOf(ltp));
            strike.setCallIV(BigDecimal.valueOf(15.0));
            strike.setPutIV(BigDecimal.valueOf(15.0));
            strikes.add(strike);
        }

        OptionChain chain = new OptionChain();
        chain.setSpotPrice(BigDecimal.valueOf(50000.0));
        chain.setExpiryDate(expiryDate);
        chain.setStrikes(strikes);
        return chain;
    }
}
