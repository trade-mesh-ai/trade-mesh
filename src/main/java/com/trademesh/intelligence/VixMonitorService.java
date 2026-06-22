package com.trademesh.intelligence;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.trademesh.zerodha.auth.ZerodhaAuthService;
import com.trademesh.zerodha.auth.ZerodhaConfig;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;

/**
 * Service for fetching and caching VIX (Volatility Index) data from Zerodha Kite API.
 * <p>
 * Responsibilities:
 * - Fetches INDIA VIX data from Zerodha Kite REST API (instrument token: 264969)
 * - Caches result in Redis with 5-minute TTL
 * - Calculates VIX level and percentage change
 * - Provides real-time VIX data for trading decisions
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VixMonitorService {

    // ─── Constants ─────────────────────────────────────────────────────────────────
    private static final String REDIS_KEY = "market:vix";
    private static final long INDIA_VIX_TOKEN = 264969L;
    private static final Duration CACHE_TTL = Duration.ofMinutes(5);
    // ─── Dependencies ─────────────────────────────────────────────────────────────
    private final ZerodhaConfig config;
    private final ZerodhaAuthService authService;
    private final ReactiveStringRedisTemplate redisTemplate;
    private final WebClient.Builder webClientBuilder;
    // ─── Configuration ────────────────────────────────────────────────────────────
    @Value("${vix.alert-threshold:20.0}")
    private Double alertThreshold;

    // ─── Public API ────────────────────────────────────────────────────────────────

    /**
     * Fetches the current VIX data from Zerodha Kite API.
     * Attempts to retrieve from Redis cache first; if not found, fetches from API.
     *
     * @return {@link Mono} emitting the current VixData
     */
    public Mono<VixData> fetchCurrentVix() {
        // Try to get from cache first
        return redisTemplate.opsForValue().get(REDIS_KEY)
                .flatMap(cached -> {
                    log.debug("VIX data found in cache");
                    return Mono.just(deserializeVixData(cached));
                })
                .switchIfEmpty(
                        // Cache miss: fetch from Kite API
                        fetchFromKiteAPI()
                                .flatMap(vixData -> {
                                    // Cache the result
                                    String json = serializeVixData(vixData);
                                    return redisTemplate.opsForValue()
                                            .set(REDIS_KEY, json, CACHE_TTL)
                                            .thenReturn(vixData)
                                            .doOnSuccess(vd -> log.info("Cached VIX data in Redis (TTL=5m): {}", vd.getCurrentVix()));
                                })
                )
                .doOnError(e -> log.error("Failed to fetch VIX data: {}", e.getMessage(), e));
    }

    /**
     * Determines the VIX level for a given VIX value.
     *
     * @param vixValue the current VIX value
     * @return the corresponding VixLevel
     */
    public VixLevel getVixLevel(Double vixValue) {
        return VixLevel.fromValue(vixValue);
    }

    /**
     * Checks if the current VIX has crossed the alert threshold.
     *
     * @param vixData the current VIX data
     * @return true if VIX is at or above the alert threshold, false otherwise
     */
    public boolean hasVixCrossedThreshold(VixData vixData) {
        return vixData.getCurrentVix() >= alertThreshold;
    }

    // ─── Kite API Integration ─────────────────────────────────────────────────────

    /**
     * Fetches VIX data from Zerodha Kite REST API.
     * Uses the Quote API to fetch INDIA VIX (token: 264969).
     */
    private Mono<VixData> fetchFromKiteAPI() {
        return authService.getAccessToken()
                .switchIfEmpty(Mono.error(new IllegalStateException(
                        "No Zerodha access token found in Redis. Please authenticate first.")))
                .flatMap(accessToken -> {
                    log.info("Fetching VIX data from Kite API");

                    WebClient client = webClientBuilder
                            .baseUrl(config.getBaseUrl())
                            .defaultHeader("Authorization", "Bearer " + accessToken)
                            .build();

                    // Fetch quote data for INDIA VIX
                    return client.get()
                            .uri("/quote/?i=NSE:INDIAVIX&mode=full")
                            .retrieve()
                            .bodyToMono(KiteQuoteResponse.class)
                            .flatMap(this::parseVixData)
                            .doOnError(e -> log.error("Kite API error: {}", e.getMessage()));
                });
    }

    /**
     * Parses the Kite API response and builds a VixData object.
     */
    private Mono<VixData> parseVixData(KiteQuoteResponse response) {
        if (response == null || response.getData() == null) {
            return Mono.error(new IllegalStateException("Invalid Kite API response for VIX"));
        }

        KiteQuoteResponse.QuoteData data = response.getData();

        Double currentVix = data.getLastPrice();
        Double previousClose = data.getPrevDayClose();
        Double changePercent = previousClose != null && previousClose > 0
                ? ((currentVix - previousClose) / previousClose) * 100
                : 0.0;

        VixLevel vixLevel = VixLevel.fromValue(currentVix);

        VixData vixData = VixData.builder()
                .currentVix(currentVix)
                .vixLevel(vixLevel)
                .previousClose(previousClose)
                .changePercent(changePercent)
                .fetchedAt(Instant.now())
                .build();

        log.info("Parsed VIX data: value={}, level={}, change={}%",
                currentVix, vixLevel, String.format("%.2f", changePercent));

        return Mono.just(vixData);
    }

    // ─── Serialization / Deserialization ──────────────────────────────────────────

    /**
     * Serializes VixData to JSON string for Redis caching.
     */
    private String serializeVixData(VixData vixData) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper()
                    .writeValueAsString(vixData);
        } catch (Exception e) {
            log.error("Failed to serialize VixData: {}", e.getMessage());
            throw new RuntimeException(e);
        }
    }

    /**
     * Deserializes a JSON string from Redis back to a VixData object.
     */
    private VixData deserializeVixData(String json) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper()
                    .readValue(json, VixData.class);
        } catch (Exception e) {
            log.error("Failed to deserialize VixData: {}", e.getMessage());
            throw new RuntimeException(e);
        }
    }

    // ─── Inner DTOs for Kite API Response ──────────────────────────────────────────

    /**
     * Minimal representation of Kite Connect Quote API response for VIX.
     */
    @Data
    static class KiteQuoteResponse {
        private String status;
        private QuoteData data;

        @Data
        static class QuoteData {
            @JsonProperty("instrument_token")
            private long instrumentToken;

            @JsonProperty("trading_symbol")
            private String tradingSymbol;

            @JsonProperty("last_price")
            private Double lastPrice;

            @JsonProperty("prev_day_close")
            private Double prevDayClose;

            @JsonProperty("open_interest")
            private long openInterest;

            @JsonProperty("volume")
            private long volume;

            @JsonProperty("timestamp")
            private String timestamp;
        }
    }
}
