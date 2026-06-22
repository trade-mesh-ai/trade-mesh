package com.trademesh.intelligence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.trademesh.intelligence.maxpain.MaxPainCalculatorService;
import com.trademesh.intelligence.oi.MarketBias;
import com.trademesh.intelligence.oi.OIAnalyserService;
import com.trademesh.zerodha.options.OptionChainService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.Duration;

/**
 * Service for generating comprehensive market intelligence.
 *
 * <p>Aggregates VIX data, OI analysis, and max pain calculations into a unified
 * intelligence object with market condition classification and trading safety assessment.
 *
 * <p>Responsibilities:
 * - Fetches all intelligence components in parallel using Mono.zip()
 * - Classifies market condition based on VIX, OI bias, and calendar
 * - Assesses trading safety
 * - Caches results in Redis with 5-minute TTL
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MarketIntelligenceService {

    // ─── Constants ─────────────────────────────────────────────────────────────────
    private static final String REDIS_KEY_PREFIX = "market:intelligence:";
    private static final Duration CACHE_TTL = Duration.ofMinutes(5);
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    // ─── Dependencies ─────────────────────────────────────────────────────────────
    private final VixMonitorService vixMonitorService;
    private final OptionChainService optionChainService;
    private final OIAnalyserService oiAnalyserService;
    private final MaxPainCalculatorService maxPainCalculatorService;
    private final ReactiveStringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    // ─── Public API ────────────────────────────────────────────────────────────────

    /**
     * Generates comprehensive market intelligence for a given symbol.
     * Fetches all components in parallel and classifies market condition.
     * Attempts to retrieve from Redis cache first; if not found, generates from scratch.
     *
     * @param symbol trading symbol (e.g., "NIFTY50", "NIFTYBANK")
     * @return {@link Mono} emitting the MarketIntelligence
     */
    public Mono<MarketIntelligence> generateIntelligence(String symbol) {
        String redisKey = REDIS_KEY_PREFIX + symbol;

        // Try to get from cache first
        return redisTemplate.opsForValue().get(redisKey)
                .flatMap(cached -> {
                    log.debug("Market intelligence for {} found in cache", symbol);
                    try {
                        return Mono.just(objectMapper.readValue(cached, MarketIntelligence.class));
                    } catch (Exception e) {
                        log.error("Failed to deserialize cached intelligence: {}", e.getMessage());
                        return performGeneration(symbol);
                    }
                })
                .switchIfEmpty(performGeneration(symbol))
                .flatMap(intelligence -> {
                    // Cache the result
                    try {
                        String json = objectMapper.writeValueAsString(intelligence);
                        return redisTemplate.opsForValue()
                                .set(redisKey, json, CACHE_TTL)
                                .thenReturn(intelligence)
                                .doOnSuccess(i -> log.info("Cached market intelligence for {} in Redis (TTL=5m): condition={}", 
                                        symbol, i.getMarketCondition()));
                    } catch (Exception e) {
                        log.error("Failed to cache intelligence: {}", e.getMessage());
                        return Mono.just(intelligence);
                    }
                })
                .doOnError(e -> log.error("Failed to generate intelligence for {}: {}", symbol, e.getMessage(), e));
    }

    // ─── Generation Methods ────────────────────────────────────────────────────────

    /**
     * Performs the actual intelligence generation by fetching all components in parallel.
     */
    private Mono<MarketIntelligence> performGeneration(String symbol) {
        // Fetch VIX and option chain in parallel
        return Mono.zip(
                vixMonitorService.fetchCurrentVix(),
                optionChainService.fetchOptionChain(symbol)
        )
        .flatMap(tuple -> {
            var vixData = tuple.getT1();
            var optionChain = tuple.getT2();

            // Fetch OI analysis and max pain in parallel
            return Mono.zip(
                    oiAnalyserService.analyseOI(optionChain),
                    maxPainCalculatorService.calculate(optionChain)
            )
            .map(tuple2 -> {
                var oiAnalysis = tuple2.getT1();
                var maxPainResult = tuple2.getT2();

                // Classify market condition
                MarketCondition condition = classifyMarketCondition(symbol, vixData, oiAnalysis);

                // Assess trading safety
                Boolean isSafeToTrade = assessTradingSafety(vixData, condition);

                // Build intelligence
                MarketIntelligence intelligence = MarketIntelligence.builder()
                        .symbol(symbol)
                        .spotPrice(optionChain.getSpotPrice().doubleValue())
                        .vixData(vixData)
                        .oiAnalysis(oiAnalysis)
                        .maxPainResult(maxPainResult)
                        .optionChain(optionChain)
                        .marketCondition(condition)
                        .isSafeToTrade(isSafeToTrade)
                        .generatedAt(Instant.now())
                        .build();

                log.info("Generated market intelligence for {}: condition={}, safe={}, summary={}",
                        symbol, condition, isSafeToTrade, intelligence.summary());

                return intelligence;
            });
        });
    }

    /**
     * Classifies the market condition based on VIX, OI bias, and calendar.
     *
     * <p>Classification logic:
     * 1. If today is expiry day (Tuesday for NIFTY50, last Tuesday for BANKNIFTY) → EXPIRY_DAY
     * 2. If VIX > 25 → NO_TRADE (extreme volatility)
     * 3. If VIX > 20 → HIGH_VOLATILITY
     * 4. If OI bias is NEUTRAL and VIX < 17 → SIDEWAYS
     * 5. If OI bias is BULLISH → TRENDING_BULLISH
     * 6. If OI bias is BEARISH → TRENDING_BEARISH
     *
     * @param symbol trading symbol
     * @param vixData current VIX data
     * @param oiAnalysis OI analysis result
     * @return classified market condition
     */
    private MarketCondition classifyMarketCondition(String symbol, VixData vixData, com.trademesh.intelligence.oi.OIAnalysis oiAnalysis) {
        // Check if today is expiry day
        if (isExpiryDay(symbol)) {
            return MarketCondition.EXPIRY_DAY;
        }

        // Check VIX levels
        if (vixData.getCurrentVix() > 25) {
            return MarketCondition.NO_TRADE;
        }

        if (vixData.getCurrentVix() > 20) {
            return MarketCondition.HIGH_VOLATILITY;
        }

        // Check OI bias
        String bias = oiAnalysis.getMarketBias();
        
        if ("NEUTRAL".equals(bias) && vixData.getCurrentVix() < 17) {
            return MarketCondition.SIDEWAYS;
        }

        if ("BULLISH".equals(bias)) {
            return MarketCondition.TRENDING_BULLISH;
        }

        if ("BEARISH".equals(bias)) {
            return MarketCondition.TRENDING_BEARISH;
        }

        // Default to sideways if no clear condition
        return MarketCondition.SIDEWAYS;
    }

    /**
     * Assesses whether it is safe to trade based on VIX level and market condition.
     *
     * <p>Safe to trade if:
     * - VIX is in LOW or NORMAL range (< 17)
     * - Market condition is not NO_TRADE
     *
     * @param vixData current VIX data
     * @param condition market condition
     * @return true if safe to trade, false otherwise
     */
    private Boolean assessTradingSafety(VixData vixData, MarketCondition condition) {
        boolean vixSafe = vixData.isSafeToTrade(); // VIX < 17
        boolean conditionSafe = condition != MarketCondition.NO_TRADE;
        return vixSafe && conditionSafe;
    }

    /**
     * Determines if today is an expiry day for the given symbol.
     *
     * <p>NIFTY50: Every Tuesday
     * BANKNIFTY: Last Tuesday of the month
     *
     * @param symbol trading symbol
     * @return true if today is expiry day for the symbol
     */
    private boolean isExpiryDay(String symbol) {
        LocalDate today = LocalDate.now(IST);
        DayOfWeek dayOfWeek = today.getDayOfWeek();

        if ("NIFTY50".equals(symbol)) {
            // NIFTY50 expires every Tuesday
            return dayOfWeek == DayOfWeek.TUESDAY;
        } else if ("NIFTYBANK".equals(symbol)) {
            // BANKNIFTY expires on last Tuesday of the month
            return dayOfWeek == DayOfWeek.TUESDAY 
                    && today.plusDays(7).getMonthValue() != today.getMonthValue();
        }

        return false;
    }
}
