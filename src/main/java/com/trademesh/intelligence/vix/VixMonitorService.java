package com.trademesh.intelligence.vix;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.trademesh.zerodha.auth.TokenAvailabilityTracker;
import com.trademesh.zerodha.auth.ZerodhaAuthService;
import com.trademesh.zerodha.auth.ZerodhaConfig;
import com.trademesh.zerodha.options.KiteQuotesResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.ZonedDateTime;

import static com.trademesh.config.ApplicationConstants.IST;

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
    private static final String SCHEDULER_NAME = "VixMonitorService";
    // ─── Dependencies ─────────────────────────────────────────────────────────────
    private final ZerodhaConfig config;
    private final ZerodhaAuthService authService;
    private final ReactiveStringRedisTemplate redisTemplate;
    private final WebClient.Builder webClientBuilder;
    private final TokenAvailabilityTracker tokenTracker;
    private final ObjectMapper objectMapper;
    private final VixHistoryRepository vixHistoryRepository;
    // ─── Configuration ────────────────────────────────────────────────────────
    @Value("${vix.alert-threshold:20.0}")
    private Double alertThreshold;

    // ─── Public API ────────────────────────────────────────────────────────────────

    /**
     * Fetches the current VIX data from Zerodha Kite API.
     * Attempts to retrieve from Redis cache first; if not found, fetches from API.
     * Does NOT persist to database.
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
                .doOnError(e -> log.error("Failed to fetch VIX data: {}", e.getMessage(), e))
                .onErrorResume(e -> {
                    // Suppress error propagation to prevent ErrorCallbackNotImplemented
                    log.debug("VIX fetch failed, returning empty to allow scheduler to continue");
                    return Mono.empty();
                });
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

    /**
     * Fetches the current VIX and persists it to the vix_history table.
     * This method is called by the scheduler to capture historical snapshots.
     *
     * @return {@link Mono} emitting the saved VixHistory
     */
    public Mono<VixHistory> fetchAndSave() {
        return fetchCurrentVix()
                .flatMap(vixData -> {
                    VixHistory vixHistory = VixHistory.fromVixData(vixData);
                    return vixHistoryRepository.save(vixHistory)
                            .doOnSuccess(saved -> log.info("VIX data persisted to database: id={}, value={}, level={}",
                                    saved.getId(),
                                    saved.getVixValue(),
                                    saved.getVixLevel()))
                            .then(Mono.just(vixHistory));
                })
                .doOnError(e -> log.error("Failed to fetch and save VIX data: {}", e.getMessage(), e));
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
                    tokenTracker.resetTracking(SCHEDULER_NAME);

                    WebClient client = webClientBuilder
                            .baseUrl(config.getBaseUrl())
                            .defaultHeader(
                                    HttpHeaders.AUTHORIZATION,
                                    "token " + config.getApiKey() + ":" + accessToken
                            )
                            .build();

                    // Fetch quote data for INDIA VIX
                    return client.get()
                            .uri("/quote?i=NSE:INDIA+VIX")
                            .retrieve()

                            .bodyToMono(KiteQuotesResponse.class)
                            .doOnNext(response -> log.info("Successfully fetched VIX quote from Kite API: {}", response))
                            .timeout(Duration.ofSeconds(10))
                            .flatMap(this::parseVixData)
                            .doOnError(e -> log.error("Kite API error fetching VIX: {}", e.getMessage(), e));
                })
                .doOnError(e -> {
                    if (e instanceof IllegalStateException && e.getMessage().contains("No Zerodha access token")) {
                        tokenTracker.setTokenUnavailable();
                        tokenTracker.trackTokenMissing(SCHEDULER_NAME);
                    }
                })
                .onErrorResume(e -> {
                    if (e instanceof IllegalStateException && e.getMessage().contains("No Zerodha access token")) {
                        log.debug("VIX fetch failed due to missing token, returning empty to allow scheduler to continue");
                        return Mono.empty();
                    }
                    log.warn("VIX fetch failed with error: {}, returning empty", e.getMessage());
                    return Mono.empty();
                });
    }

    /**
     * Parses the Kite API response and builds a VixData object.
     */
    private Mono<VixData> parseVixData(KiteQuotesResponse response) {
        if (response == null || response.getData() == null) {
            return Mono.error(new IllegalStateException("Invalid Kite API response for VIX"));
        }

        KiteQuotesResponse.QuoteData quoteData = response.getData().get("NSE:INDIA VIX");
        if (quoteData == null) {
            log.error("No VIX data found in Kite response. data map was empty or key missing.");
            return Mono.error(new RuntimeException("VIX data not available from Kite API"));
        }

        Double currentVix = quoteData.getLastPrice();
        if (currentVix == null || currentVix <= 0) {
            return Mono.error(new IllegalStateException("Invalid VIX value: " + currentVix));
        }

        Double prevClose = (quoteData.getOhlc() != null)
                ? quoteData.getOhlc().getClose()
                : quoteData.getLastPrice();

        Double changePercent = 0.0;

        if (prevClose != null && prevClose > 0) {
            changePercent = ((currentVix - prevClose) / prevClose) * 100;
        } else {
            log.debug("Previous close not available for VIX, using 0% change");
        }

        VixLevel vixLevel = VixLevel.fromValue(currentVix);

        VixData vixData = VixData.builder()
                .currentVix(currentVix)
                .vixLevel(vixLevel)
                .previousClose(prevClose)
                .changePercent(Math.round(changePercent * 100.0) / 100.0)
                .fetchedAt(ZonedDateTime.now(IST).toInstant())
                .build();

        log.info("VIX fetched successfully: last={}, prevClose={}, netChange={}",
                quoteData.getLastPrice(), prevClose, quoteData.getNetChange() != null ? quoteData.getNetChange() : 0.0);

        return Mono.just(vixData);
    }

    // ─── Serialization / Deserialization ──────────────────────────────────────────

    /**
     * Serializes VixData to JSON string for Redis caching.
     */
    private String serializeVixData(VixData vixData) {
        try {
            return objectMapper.writeValueAsString(vixData);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            log.error("Failed to serialize VixData: {}", e.getMessage());
            throw new RuntimeException("VixData serialization failed", e);
        }
    }

    /**
     * Deserializes a JSON string from Redis back to a VixData object.
     */
    private VixData deserializeVixData(String json) {
        try {
            return objectMapper.readValue(json, VixData.class);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            log.error("Failed to deserialize VixData: {}", e.getMessage());
            throw new RuntimeException("VixData deserialization failed", e);
        }
    }
}
