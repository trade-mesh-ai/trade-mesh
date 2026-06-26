package com.trademesh.intelligence.oi;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.trademesh.zerodha.options.OptionChain;
import com.trademesh.zerodha.options.OptionStrike;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;

/**
 * Service for analyzing Open Interest (OI) patterns in option chains.
 * <p>
 * Responsibilities:
 * - Analyzes option chain data to identify OI walls (resistance/support)
 * - Calculates market bias based on call vs put OI distribution
 * - Detects OI wall strength (WEAK, MODERATE, STRONG)
 * - Caches analysis results in Redis with 5-minute TTL
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OIAnalyserService {

    // ─── Constants ─────────────────────────────────────────────────────────────────
    private static final String REDIS_KEY_PREFIX = "market:oi:analysis:";
    private static final Duration CACHE_TTL = Duration.ofMinutes(5);

    // OI Wall strength thresholds (in number of contracts)
    private static final long STRONG_THRESHOLD = 5_000_000L;      // 50 lakh
    private static final long MODERATE_THRESHOLD = 2_000_000L;    // 20 lakh

    // Market bias threshold (10% difference)
    private static final double BIAS_THRESHOLD = 0.10;

    // ─── Dependencies ─────────────────────────────────────────────────────────────
    private final ReactiveStringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final OIAnalysisRepository analysisRepository;

    // ─── Public API ────────────────────────────────────────────────────────────────

    /**
     * Analyzes the option chain and returns OI analysis.
     * Does NOT persist to database.
     * <p>
     * This method:
     * - Finds maximum call OI (resistance wall)
     * - Finds maximum put OI (support wall)
     * - Identifies top 3 call and put OI walls
     * - Calculates market bias
     * - Caches result in Redis with 5-minute TTL
     *
     * @param chain the option chain to analyze
     * @return Mono emitting the OIAnalysis
     */
    public Mono<OIAnalysis> analyseOI(OptionChain chain) {
        if (chain == null || chain.getStrikes() == null || chain.getStrikes().isEmpty()) {
            log.warn("Cannot analyze empty option chain for {}", chain != null ? chain.getSymbol() : "unknown");
            return Mono.error(new IllegalArgumentException("Option chain is empty"));
        }

        String symbol = chain.getSymbol();
        String redisKey = REDIS_KEY_PREFIX + symbol;

        // Try to get from cache first
        return redisTemplate.opsForValue().get(redisKey)
                .flatMap(cached -> {
                    log.debug("OI analysis for {} found in cache", symbol);
                    try {
                        return Mono.just(objectMapper.readValue(cached, OIAnalysis.class));
                    } catch (Exception e) {
                        log.error("Failed to deserialize cached OI analysis: {}", e.getMessage());
                        return performAnalysis(chain);
                    }
                })
                .switchIfEmpty(performAnalysis(chain))
                .flatMap(analysis -> {
                    // Cache the result
                    try {
                        String json = objectMapper.writeValueAsString(analysis);
                        return redisTemplate.opsForValue()
                                .set(redisKey, json, CACHE_TTL)
                                .thenReturn(analysis)
                                .doOnSuccess(a -> log.info("Cached OI analysis for {} in Redis (TTL=5m)", symbol));
                    } catch (Exception e) {
                        log.error("Failed to cache OI analysis: {}", e.getMessage());
                        return Mono.just(analysis);
                    }
                })
                .doOnError(e -> log.error("Failed to analyze OI for {}: {}", symbol, e.getMessage(), e));
    }

    // ─── Analysis Methods ─────────────────────────────────────────────────────────

    /**
     * Performs the actual OI analysis on the option chain.
     */
    private Mono<OIAnalysis> performAnalysis(OptionChain chain) {
        return Mono.fromCallable(() -> {
            List<OptionStrike> strikes = chain.getStrikes();

            // Find max call OI (resistance wall)
            OptionStrike maxCallStrike = findMaxCallOI(strikes);
            BigDecimal maxCallOIStrike = maxCallStrike != null ? maxCallStrike.getStrikePrice() : null;
            long maxCallOI = maxCallStrike != null ? maxCallStrike.getCallOI() : 0;

            // Find max put OI (support wall)
            OptionStrike maxPutStrike = findMaxPutOI(strikes);
            BigDecimal maxPutOIStrike = maxPutStrike != null ? maxPutStrike.getStrikePrice() : null;
            long maxPutOI = maxPutStrike != null ? maxPutStrike.getPutOI() : 0;

            // Get top 3 call and put walls
            List<OIWall> topCallWalls = getTopCallWalls(strikes, 3);
            List<OIWall> topPutWalls = getTopPutWalls(strikes, 3);

            // Calculate market bias
            MarketBias bias = calculateMarketBias(maxCallOI, maxPutOI);

            // Build OIAnalysis
            OIAnalysis analysis = OIAnalysis.builder()
                    .symbol(chain.getSymbol())
                    .maxCallOIStrike(maxCallOIStrike)
                    .maxPutOIStrike(maxPutOIStrike)
                    .topCallWalls(topCallWalls)
                    .topPutWalls(topPutWalls)
                    .marketBias(bias.name())
                    .analyzedAt(Instant.now())
                    .build();

            // Serialize full analysis to JSON
            try {
                analysis.setData(objectMapper.writeValueAsString(analysis));
                analysis.setTopCallWallsJson(objectMapper.writeValueAsString(topCallWalls));
                analysis.setTopPutWallsJson(objectMapper.writeValueAsString(topPutWalls));
            } catch (Exception e) {
                log.error("Failed to serialize OI analysis: {}", e.getMessage());
            }

            log.info("OI Analysis for {}: maxCallStrike={}, maxPutStrike={}, bias={}",
                    chain.getSymbol(), maxCallOIStrike, maxPutOIStrike, bias);

            return analysis;
        });
    }

    /**
     * Finds the strike with maximum call OI (resistance wall).
     *
     * @param strikes list of option strikes
     * @return the strike with maximum call OI, or null if list is empty
     */
    public OptionStrike findMaxCallOI(List<OptionStrike> strikes) {
        return strikes.stream()
                .max(Comparator.comparingLong(OptionStrike::getCallOI))
                .orElse(null);
    }

    /**
     * Finds the strike with maximum put OI (support wall).
     *
     * @param strikes list of option strikes
     * @return the strike with maximum put OI, or null if list is empty
     */
    public OptionStrike findMaxPutOI(List<OptionStrike> strikes) {
        return strikes.stream()
                .max(Comparator.comparingLong(OptionStrike::getPutOI))
                .orElse(null);
    }

    /**
     * Gets the top N call OI walls (highest call OI concentrations).
     *
     * @param strikes list of option strikes
     * @param limit   number of top walls to return
     * @return list of top call OI walls
     */
    public List<OIWall> getTopCallWalls(List<OptionStrike> strikes, int limit) {
        return strikes.stream()
                .filter(strike -> strike.getCallOI() > 0)
                .map(strike -> OIWall.builder()
                        .strikePrice(strike.getStrikePrice())
                        .wallType(OIWallType.CALL_WALL)
                        .totalOI(strike.getCallOI())
                        .oiChangePercent(calculateOIChangePercent(strike.getCallOI(), strike.getCallOIChange()))
                        .strength(detectOIWallStrength(strike.getCallOI()))
                        .build())
                .sorted(Comparator.comparingLong(OIWall::getTotalOI).reversed())
                .limit(limit)
                .toList();
    }

    /**
     * Gets the top N put OI walls (highest put OI concentrations).
     *
     * @param strikes list of option strikes
     * @param limit   number of top walls to return
     * @return list of top put OI walls
     */
    public List<OIWall> getTopPutWalls(List<OptionStrike> strikes, int limit) {
        return strikes.stream()
                .filter(strike -> strike.getPutOI() > 0)
                .map(strike -> OIWall.builder()
                        .strikePrice(strike.getStrikePrice())
                        .wallType(OIWallType.PUT_WALL)
                        .totalOI(strike.getPutOI())
                        .oiChangePercent(calculateOIChangePercent(strike.getPutOI(), strike.getPutOIChange()))
                        .strength(detectOIWallStrength(strike.getPutOI()))
                        .build())
                .sorted(Comparator.comparingLong(OIWall::getTotalOI).reversed())
                .limit(limit)
                .toList();
    }

    /**
     * Calculates market bias based on call vs put OI distribution.
     * <p>
     * Logic:
     * - If maxPutOI > maxCallOI by >10% → BULLISH (support dominant)
     * - If maxCallOI > maxPutOI by >10% → BEARISH (resistance dominant)
     * - Within 10% difference → NEUTRAL (balanced)
     *
     * @param maxCallOI maximum call OI
     * @param maxPutOI  maximum put OI
     * @return the market bias
     */
    public MarketBias calculateMarketBias(long maxCallOI, long maxPutOI) {
        if (maxCallOI == 0 && maxPutOI == 0) {
            return MarketBias.NEUTRAL;
        }

        long maxOI = Math.max(maxCallOI, maxPutOI);
        if (maxOI == 0) {
            return MarketBias.NEUTRAL;
        }

        double difference = Math.abs(maxCallOI - maxPutOI) / (double) maxOI;

        if (difference <= BIAS_THRESHOLD) {
            return MarketBias.NEUTRAL;
        }

        if (maxPutOI > maxCallOI) {
            return MarketBias.BULLISH;
        } else {
            return MarketBias.BEARISH;
        }
    }

    /**
     * Detects OI wall strength based on total open interest.
     * <p>
     * Strength classification:
     * - OI > 50 lakh (5,000,000) → STRONG
     * - OI 20-50 lakh (2,000,000-5,000,000) → MODERATE
     * - OI < 20 lakh (2,000,000) → WEAK
     *
     * @param oi the open interest value
     * @return the strength classification
     */
    public OIWallStrength detectOIWallStrength(long oi) {
        if (oi >= STRONG_THRESHOLD) {
            return OIWallStrength.STRONG;
        } else if (oi >= MODERATE_THRESHOLD) {
            return OIWallStrength.MODERATE;
        } else {
            return OIWallStrength.WEAK;
        }
    }

    /**
     * Calculates OI change percentage.
     *
     * @param currentOI current open interest
     * @param oiChange  change in open interest
     * @return percentage change, or 0 if current OI is 0
     */
    private Double calculateOIChangePercent(long currentOI, long oiChange) {
        if (currentOI == 0) {
            return 0.0;
        }
        return (oiChange / (double) currentOI) * 100.0;
    }

    /**
     * Analyzes the option chain and persists the OI analysis to the oi_snapshots table.
     * This method is called by the scheduler to capture historical snapshots.
     *
     * @param chain the option chain to analyze
     * @return {@link Mono} emitting the saved OIAnalysis
     */
    public Mono<OIAnalysis> captureAndSave(OptionChain chain) {
        return analyseOI(chain)
                .flatMap(analysis -> {
                    // Set ID to null to ensure a new record is created
                    analysis.setId(null);

                    // Ensure analyzedAt is set
                    if (analysis.getAnalyzedAt() == null) {
                        analysis.setAnalyzedAt(Instant.now());
                    }

                    // Save to PostgreSQL and return the saved entity with DB-generated ID
                    return analysisRepository.save(analysis)
                            .doOnSuccess(saved -> log.info("Saved OI analysis snapshot: symbol={}, id={}, bias={}",
                                    saved.getSymbol(), saved.getId(), saved.getMarketBias()));
                })
                .doOnError(e -> log.error("Failed to capture and save OI analysis: {}", e.getMessage(), e));
    }
}
