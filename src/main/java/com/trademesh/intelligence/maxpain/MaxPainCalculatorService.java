package com.trademesh.intelligence.maxpain;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.trademesh.config.ApplicationConstants;
import com.trademesh.zerodha.options.OptionChain;
import com.trademesh.zerodha.options.OptionStrike;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import static com.trademesh.config.ApplicationConstants.REDIS_KEY_MAX_PAIN_PREFIX;

/**
 * Service for calculating Max Pain for option chains.
 *
 * <p>Max Pain is the strike price at which the maximum number of options expire worthless,
 * causing maximum loss to option buyers. This service:
 * - Calculates total loss to option buyers at each strike price
 * - Identifies the strike with minimum total loss (Max Pain)
 * - Caches results in Redis with 15-minute TTL
 * - Supports configurable lot sizes per symbol
 *
 * <p>Algorithm:
 * For each strike price X:
 * - Call buyer loss = Sum of (all call strikes below X × their OI × lot size)
 * - Put buyer loss = Sum of (all put strikes above X × their OI × lot size)
 * - Total pain at X = call buyer loss + put buyer loss
 * Strike with MINIMUM total pain = Max Pain strike
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MaxPainCalculatorService {

    private final ReactiveStringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final MaxPainHistoryRepository maxPainHistoryRepository;
    @Value("#{${maxpain.lot-sizes:{NIFTY50: 75, NIFTYBANK: 30}}}")
    private Map<String, Integer> lotSizes;

    /**
     * Calculates and persists the Max Pain for a given option chain.
     * Calls calculate(), sets id to null, ensures calculatedAt is set,
     * then saves to the database via maxPainHistoryRepository.
     *
     * @param chain the option chain to analyze
     * @return {@link Mono} emitting the saved MaxPainResult with database-generated id
     */
    public Mono<MaxPainResult> calculateAndSave(OptionChain chain) {
        return calculate(chain)
                .flatMap(result -> {
                    // Set ID to null to ensure a new record is created
                    result.setId(null);

                    // Ensure calculatedAt is set
                    if (result.getCalculatedAt() == null) {
                        result.setCalculatedAt(Instant.now());
                    }

                    // Save to PostgreSQL and return the saved entity with DB-generated ID
                    return maxPainHistoryRepository.save(result)
                            .doOnSuccess(saved -> log.info("Saved max pain snapshot: symbol={}, id={}, strike={}",
                                    saved.getSymbol(), saved.getId(), saved.getMaxPainStrike()));
                });
    }

    /**
     * Calculates the Max Pain for a given option chain.
     * Attempts to retrieve from Redis cache first; if not found, calculates from scratch.
     * Does NOT persist to database.
     *
     * @param chain the option chain to analyze
     * @return {@link Mono} emitting the MaxPainResult
     */
    public Mono<MaxPainResult> calculate(OptionChain chain) {
        if (chain == null || chain.getStrikes() == null || chain.getStrikes().isEmpty()) {
            log.warn("Cannot calculate max pain for empty option chain");
            return Mono.error(new IllegalArgumentException("Option chain is empty"));
        }

        String symbol = chain.getSymbol();
        String redisKey = REDIS_KEY_MAX_PAIN_PREFIX + symbol;

        // Try to get from cache first
        return redisTemplate.opsForValue().get(redisKey)
                .flatMap(cached -> {
                    log.debug("Max pain for {} found in cache", symbol);
                    try {
                        return Mono.just(objectMapper.readValue(cached, MaxPainResult.class));
                    } catch (Exception e) {
                        log.error("Failed to deserialize cached max pain result: {}", e.getMessage());
                        return performCalculation(chain);
                    }
                })
                .switchIfEmpty(performCalculation(chain))
                .flatMap(result -> {
                    // Cache the result
                    try {
                        String json = objectMapper.writeValueAsString(result);
                        return redisTemplate.opsForValue()
                                .set(redisKey, json, ApplicationConstants.CACHE_MAX_PAIN_TTL)
                                .thenReturn(result)
                                .doOnSuccess(r -> log.info("Cached max pain for {} in Redis (TTL=15m): strike={}",
                                        symbol, r.getMaxPainStrike()));
                    } catch (Exception e) {
                        log.error("Failed to cache max pain result: {}", e.getMessage());
                        return Mono.just(result);
                    }
                })
                .doOnError(e -> log.error("Failed to calculate max pain for {}: {}", symbol, e.getMessage(), e));
    }


    /**
     * Performs the actual max pain calculation on the option chain.
     */
    private Mono<MaxPainResult> performCalculation(OptionChain chain) {
        return Mono.fromCallable(() -> {
            String symbol = chain.getSymbol();
            List<OptionStrike> strikes = chain.getStrikes();
            BigDecimal spotPrice = chain.getSpotPrice();
            int lotSize = getLotSize(symbol);

            log.debug("Calculating max pain for {} with {} strikes, lot size: {}",
                    symbol, strikes.size(), lotSize);

            // Sort strikes by price for easier iteration
            List<OptionStrike> sortedStrikes = strikes.stream()
                    .sorted(Comparator.comparing(OptionStrike::getStrikePrice))
                    .toList();

            // Calculate total pain for each strike
            double minTotalPain = Double.MAX_VALUE;
            Double maxPainStrike = null;

            for (OptionStrike currentStrike : sortedStrikes) {
                BigDecimal strikePrice = currentStrike.getStrikePrice();

                // Calculate call buyer loss: sum of all call strikes below current strike
                double callBuyerLoss = sortedStrikes.stream()
                        .filter(s -> s.getStrikePrice().compareTo(strikePrice) < 0)
                        .mapToDouble(s -> s.getCallOI() * lotSize)
                        .sum();

                // Calculate put buyer loss: sum of all put strikes above current strike
                double putBuyerLoss = sortedStrikes.stream()
                        .filter(s -> s.getStrikePrice().compareTo(strikePrice) > 0)
                        .mapToDouble(s -> s.getPutOI() * lotSize)
                        .sum();

                // Total pain at this strike
                double totalPain = callBuyerLoss + putBuyerLoss;

                log.trace("Strike {}: callLoss={}, putLoss={}, totalPain={}",
                        strikePrice, callBuyerLoss, putBuyerLoss, totalPain);

                // Track minimum pain strike
                if (totalPain < minTotalPain) {
                    minTotalPain = totalPain;
                    maxPainStrike = strikePrice.doubleValue();
                }
            }

            // If no max pain found, use spot price as fallback
            if (maxPainStrike == null) {
                maxPainStrike = spotPrice.doubleValue();
                log.warn("No max pain strike calculated for {}, using spot price: {}", symbol, maxPainStrike);
            }

            // Calculate distance metrics
            double distanceFromSpot = Math.abs(maxPainStrike - spotPrice.doubleValue());
            double distancePercent = spotPrice.doubleValue() > 0
                    ? Math.round((distanceFromSpot / spotPrice.doubleValue()) * 10000.0) / 100.0
                    : 0.0;

            // Build result
            MaxPainResult result = MaxPainResult.builder()
                    .symbol(symbol)
                    .expiryDate(chain.getExpiryDate())
                    .maxPainStrike(maxPainStrike)
                    .currentSpot(spotPrice.doubleValue())
                    .distanceFromSpot(distanceFromSpot)
                    .distancePercent(distancePercent)
                    .calculatedAt(Instant.now())
                    .build();

            log.info("Max Pain calculated for {}: strike={}, spot={}, distance={} ({}%)",
                    symbol, maxPainStrike, spotPrice,
                    String.format("%.2f", distanceFromSpot),
                    String.format("%.2f", distancePercent));

            return result;
        });
    }

    /**
     * Gets the lot size for a given symbol.
     * Falls back to 1 if symbol is not configured.
     *
     * @param symbol trading symbol
     * @return lot size for the symbol
     */
    private int getLotSize(String symbol) {
        return lotSizes.getOrDefault(symbol, 1);
    }
}
