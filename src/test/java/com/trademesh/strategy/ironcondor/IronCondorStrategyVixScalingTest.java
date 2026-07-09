package com.trademesh.strategy.ironcondor;

import com.trademesh.config.LotSizeService;
import com.trademesh.intelligence.MarketCondition;
import com.trademesh.intelligence.MarketIntelligence;
import com.trademesh.intelligence.MarketMetrics;
import com.trademesh.intelligence.MarketMetricsService;
import com.trademesh.intelligence.vix.VixData;
import com.trademesh.intelligence.vix.VixMonitorService;
import com.trademesh.strategy.StrategyResult;
import com.trademesh.strategy.TradeLeg;
import com.trademesh.strategy.config.StrategiesConfig;
import com.trademesh.strategy.config.StrategyProperties;
import com.trademesh.zerodha.options.OptionChain;
import com.trademesh.zerodha.options.OptionStrike;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Integration tests for Iron Condor Strategy with VIX-based position sizing.
 * Verifies that VIX-scaled lots are correctly applied to trade signals.
 */
class IronCondorStrategyVixScalingTest {

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

    @Test
    void testIronCondorSignal_UsesVixScaledLots_HighVix() {
        // Setup: VIX = 20 (high), should scale to 0.5x
        // Expected: signal legs should have half the lots compared to low VIX scenario

        StrategyProperties cfg = createMockStrategyProperties();
        when(strategiesConfig.getStrategy("IRON_CONDOR")).thenReturn(cfg);

        // VIX = 20 (high)
        VixData vixData = VixData.builder().currentVix(20.0).build();
        when(vixMonitorService.fetchCurrentVix()).thenReturn(Mono.just(vixData));

        // Market metrics
        MarketMetrics metrics = createMockMarketMetrics();
        when(marketMetricsService.calculateMetrics("NIFTY50")).thenReturn(Mono.just(metrics));

        // Option chain
        OptionChain chain = createMockOptionChain();
        when(optionChainService.fetchOptionChain("NIFTY50")).thenReturn(Mono.just(chain));

        // Market calendar
        when(marketCalendarService.countTradingDays(LocalDate.now(), chain.getExpiryDate()))
                .thenReturn(2);

        // LotSizeService: baseSize = 10, VIX = 20 → scaled = 5
        // With realistic LTPs: shortCall=60, shortPut=60, longCall=40, longPut=40
        // netCredit = 60 + 60 - 40 - 40 = 40
        // maxLossPerLot = (200 - 40) × 65 = 160 × 65 = 10920
        when(lotSizeService.getLotSize("NIFTY50")).thenReturn(65);
        when(lotSizeService.getPositionSize(
                new BigDecimal("10920.0"), // maxLossPerLot = (wingWidth - netCredit) × lotSize
                20.0,
                80,
                60
        )).thenReturn(5); // scaled size

        // Create market intelligence (no marketMetrics field)
        MarketIntelligence intelligence = MarketIntelligence.builder()
                .spotPrice(50000.0)
                .vixData(vixData)
                .optionChain(chain)
                .marketCondition(MarketCondition.SIDEWAYS)
                .build();

        // Execute
        StepVerifier.create(strategy.evaluate(intelligence))
                .assertNext(result -> {
                    assertTrue(result.getIsExecutable());
                    assertNotNull(result.getSignal());
                    
                    // Verify that legs have the VIX-scaled quantity (5)
                    List<TradeLeg> legs = result.getSignal().getLegs();
                    assertEquals(4, legs.size(), "Iron Condor should have 4 legs");
                    
                    for (TradeLeg leg : legs) {
                        assertEquals(5, leg.getQuantity(),
                                "All legs should use VIX-scaled quantity of 5");
                    }
                })
                .verifyComplete();
    }

    @Test
    void testIronCondorSignal_UsesVixScaledLots_LowVix() {
        // Setup: VIX = 15 (low), should use full size (1.0x)
        // Expected: signal legs should have full lots

        StrategyProperties cfg = createMockStrategyProperties();
        when(strategiesConfig.getStrategy("IRON_CONDOR")).thenReturn(cfg);

        // VIX = 15 (low)
        VixData vixData = VixData.builder().currentVix(15.0).build();
        when(vixMonitorService.fetchCurrentVix()).thenReturn(Mono.just(vixData));

        // Market metrics
        MarketMetrics metrics = createMockMarketMetrics();
        when(marketMetricsService.calculateMetrics("NIFTY50")).thenReturn(Mono.just(metrics));

        // Option chain
        OptionChain chain = createMockOptionChain();
        when(optionChainService.fetchOptionChain("NIFTY50")).thenReturn(Mono.just(chain));

        // Market calendar
        when(marketCalendarService.countTradingDays(LocalDate.now(), chain.getExpiryDate()))
                .thenReturn(2);

        // LotSizeService: baseSize = 10, VIX = 15 → scaled = 10 (full size)
        // With realistic LTPs: shortCall=60, shortPut=60, longCall=40, longPut=40
        // netCredit = 60 + 60 - 40 - 40 = 40
        // maxLossPerLot = (200 - 40) × 65 = 160 × 65 = 10920
        when(lotSizeService.getLotSize("NIFTY50")).thenReturn(65);
        when(lotSizeService.getPositionSize(
                new BigDecimal("10920.0"), // maxLossPerLot = (wingWidth - netCredit) × lotSize
                15.0,
                90,
                60
        )).thenReturn(10); // full size

        // Create market intelligence (no marketMetrics field)
        MarketIntelligence intelligence = MarketIntelligence.builder()
                .spotPrice(50000.0)
                .vixData(vixData)
                .optionChain(chain)
                .marketCondition(MarketCondition.SIDEWAYS)
                .build();

        // Execute
        StepVerifier.create(strategy.evaluate(intelligence))
                .assertNext(result -> {
                    assertTrue(result.getIsExecutable());
                    assertNotNull(result.getSignal());
                    
                    // Verify that legs have the full quantity (10)
                    List<TradeLeg> legs = result.getSignal().getLegs();
                    assertEquals(4, legs.size(), "Iron Condor should have 4 legs");
                    
                    for (TradeLeg leg : legs) {
                        assertEquals(10, leg.getQuantity(),
                                "All legs should use full quantity of 10");
                    }
                })
                .verifyComplete();
    }

    @Test
    void testIronCondorSignal_VixBoundary18() {
        // Setup: VIX = 18.0 (boundary), should use full size (1.0x)
        // Expected: signal legs should have full lots

        StrategyProperties cfg = createMockStrategyProperties();
        when(strategiesConfig.getStrategy("IRON_CONDOR")).thenReturn(cfg);

        // VIX = 18.0 (boundary)
        VixData vixData = VixData.builder().currentVix(18.0).build();
        when(vixMonitorService.fetchCurrentVix()).thenReturn(Mono.just(vixData));

        // Market metrics
        MarketMetrics metrics = createMockMarketMetrics();
        when(marketMetricsService.calculateMetrics("NIFTY50")).thenReturn(Mono.just(metrics));

        // Option chain
        OptionChain chain = createMockOptionChain();
        when(optionChainService.fetchOptionChain("NIFTY50")).thenReturn(Mono.just(chain));

        // Market calendar
        when(marketCalendarService.countTradingDays(LocalDate.now(), chain.getExpiryDate()))
                .thenReturn(2);

        // LotSizeService: baseSize = 10, VIX = 18.0 → scaled = 10 (full size, boundary)
        // With realistic LTPs: shortCall=60, shortPut=60, longCall=40, longPut=40
        // netCredit = 60 + 60 - 40 - 40 = 40
        // maxLossPerLot = (200 - 40) × 65 = 160 × 65 = 10920
        when(lotSizeService.getLotSize("NIFTY50")).thenReturn(65);
        when(lotSizeService.getPositionSize(
                new BigDecimal("10920.0"), // maxLossPerLot = (wingWidth - netCredit) × lotSize
                18.0,
                80,
                60
        )).thenReturn(10); // full size

        // Create market intelligence (no marketMetrics field)
        MarketIntelligence intelligence = MarketIntelligence.builder()
                .spotPrice(50000.0)
                .vixData(vixData)
                .optionChain(chain)
                .marketCondition(MarketCondition.SIDEWAYS)
                .build();

        // Execute
        StepVerifier.create(strategy.evaluate(intelligence))
                .assertNext(result -> {
                    assertTrue(result.getIsExecutable());
                    assertNotNull(result.getSignal());
                    
                    // Verify that legs have the full quantity (10) at boundary
                    List<TradeLeg> legs = result.getSignal().getLegs();
                    assertEquals(4, legs.size(), "Iron Condor should have 4 legs");
                    
                    for (TradeLeg leg : legs) {
                        assertEquals(10, leg.getQuantity(),
                                "At VIX=18.0 boundary, should use full quantity of 10");
                    }
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
        return props;
    }

    private MarketMetrics createMockMarketMetrics() {
        return MarketMetrics.builder()
                .ivr(BigDecimal.valueOf(50.0))
                .ivHvRatio(BigDecimal.valueOf(1.2))
                .atrPct(BigDecimal.valueOf(0.7))
                .currentAtmIv(BigDecimal.valueOf(15.0))
                .pcr(BigDecimal.valueOf(1.0))
                .build();
    }

    private OptionChain createMockOptionChain() {
        LocalDate expiryDate = LocalDate.now().plusDays(2);
        List<OptionStrike> strikes = new ArrayList<>();
        
        // Create mock strikes around 50000 with realistic LTPs
        // Spot = 50000, so strikes are: 49500, 49600, ..., 50000, ..., 50400, 50500
        // Near-the-money strikes have higher LTP, far OTM have lower LTP
        for (int i = 48000; i <= 52000; i += 100) {
            OptionStrike strike = new OptionStrike();
            strike.setStrikePrice(BigDecimal.valueOf(i));
            
            // Distance from spot (50000)
            int distance = Math.abs(i - 50000);
            
            // LTP decreases with distance from spot
            // At spot (distance=0): LTP=80
            // At distance 100: LTP=60
            // At distance 200: LTP=40
            // At distance 300: LTP=20
            // At distance 400: LTP=10
            // At distance 500: LTP=5
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
