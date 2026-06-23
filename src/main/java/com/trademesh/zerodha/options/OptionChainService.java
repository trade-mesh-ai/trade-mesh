package com.trademesh.zerodha.options;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.trademesh.zerodha.auth.TokenAvailabilityTracker;
import com.trademesh.zerodha.auth.ZerodhaAuthService;
import com.trademesh.zerodha.auth.ZerodhaConfig;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.trademesh.config.ApplicationConstants.*;

/**
 * Service for fetching and caching option chain data from Zerodha Kite REST API.
 * <p>
 * Responsibilities:
 * - Fetches access token from Redis.
 * - Calls Zerodha Kite REST API to fetch option chain data.
 * - Parses response and builds OptionChain object.
 * - Caches result in Redis with 3-minute TTL.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OptionChainService {

    private static final String SCHEDULER_NAME = "OptionChainService";

    // ─── Dependencies ─────────────────────────────────────────────────────────
    private final ZerodhaConfig config;
    private final ZerodhaAuthService authService;
    private final ReactiveStringRedisTemplate redisTemplate;
    private final WebClient.Builder webClientBuilder;
    private final TokenAvailabilityTracker tokenTracker;

    // ─── Public API ────────────────────────────────────────────────────────────────

    /**
     * Fetches the option chain for a given symbol (NIFTY50 or NIFTYBANK).
     * Attempts to retrieve from Redis cache first; if not found, fetches from Kite API.
     *
     * @param symbol trading symbol (e.g., "NIFTY50", "NIFTYBANK")
     * @return {@link Mono} emitting the OptionChain
     */
    public Mono<OptionChain> fetchOptionChain(String symbol) {
        String redisKey = REDIS_KEY_OPTION_CHAIN_PREFIX + symbol;

        // Try to get from cache first
        return redisTemplate.opsForValue().get(redisKey)
                .flatMap(cached -> {
                    log.debug("Option chain for {} found in cache", symbol);
                    return Mono.just(deserializeOptionChain(cached));
                })
                .switchIfEmpty(
                        // Cache miss: fetch from Kite API
                        fetchFromKiteAPI(symbol)
                                .flatMap(optionChain -> {
                                    // Cache the result
                                    String json = serializeOptionChain(optionChain);
                                    return redisTemplate.opsForValue()
                                            .set(redisKey, json, CACHE_OPTION_CHAIN_TTL)
                                            .thenReturn(optionChain)
                                            .doOnSuccess(oc -> log.info("Cached option chain for {} in Redis (TTL=3m)", symbol));
                                })
                )
                .doOnError(e -> log.error("Failed to fetch option chain for {}: {}", symbol, e.getMessage(), e));
    }

    // ─── Kite API Integration ─────────────────────────────────────────────────────

    /**
     * Fetches option chain data from Zerodha Kite REST API.
     * Uses the Quote API to fetch data for all strikes of the nearest weekly expiry.
     */
    private Mono<OptionChain> fetchFromKiteAPI(String symbol) {
        return authService.getAccessToken()
                .switchIfEmpty(Mono.error(new IllegalStateException(
                        "No Zerodha access token found in Redis. Please authenticate first.")))
                .flatMap(accessToken -> {
                    log.info("Fetching option chain for {} from Kite API", symbol);
                    tokenTracker.resetTracking(SCHEDULER_NAME);

                    Long instrumentToken = SYMBOL_TOKEN_MAP.get(symbol);
                    if (instrumentToken == null) {
                        return Mono.error(new IllegalArgumentException(
                                "Unknown symbol: " + symbol + ". Supported: NIFTY50, NIFTYBANK"));
                    }

                    WebClient client = webClientBuilder
                            .baseUrl(config.getBaseUrl())
                            .defaultHeader("Authorization", "Bearer " + accessToken)
                            .build();

                    // Fetch quote data for the underlying
                    return client.get()
                            .uri("/quote/?i=NSE:" + symbol + "&mode=full")
                            .retrieve()
                            .bodyToMono(KiteQuoteResponse.class)
                            .flatMap(response -> parseOptionChain(symbol, response))
                            .doOnError(e -> log.error("Kite API error for {}: {}", symbol, e.getMessage()));
                })
                .doOnError(e -> {
                    if (e instanceof IllegalStateException && e.getMessage().contains("No Zerodha access token")) {
                        tokenTracker.setTokenUnavailable();
                        tokenTracker.trackTokenMissing(SCHEDULER_NAME);
                    }
                })
                .onErrorResume(e -> {
                    if (e instanceof IllegalStateException && e.getMessage().contains("No Zerodha access token")) {
                        log.debug("Option chain fetch failed due to missing token, returning empty to allow scheduler to continue");
                        return Mono.empty();
                    }
                    return Mono.error(e);
                });
    }

    /**
     * Parses the Kite API response and builds an OptionChain object.
     * Fetches all option instruments for the symbol, filters by nearest weekly expiry,
     * and fetches quote data for each strike.
     */
    private Mono<OptionChain> parseOptionChain(String symbol, KiteQuoteResponse response) {
        if (response == null || response.getData() == null) {
            return Mono.error(new IllegalStateException("Invalid Kite API response"));
        }

        KiteQuoteResponse.QuoteData data = response.getData();
        BigDecimal spotPrice = BigDecimal.valueOf(data.getLastPrice());

        // Fetch all option instruments for this symbol
        return authService.getAccessToken()
                .switchIfEmpty(Mono.error(new IllegalStateException(
                        "No Zerodha access token found in Redis. Please authenticate first.")))
                .flatMap(accessToken -> {
                    tokenTracker.resetTracking(SCHEDULER_NAME);

                    WebClient client = webClientBuilder
                            .baseUrl(config.getBaseUrl())
                            .defaultHeader("Authorization", "Bearer " + accessToken)
                            .build();

                    // Fetch all instruments to find option strikes
                    return client.get()
                            .uri("/instruments")
                            .retrieve()
                            .bodyToMono(KiteInstrumentsResponse.class)
                            .flatMap(instrumentsResponse -> buildOptionChain(symbol, spotPrice, instrumentsResponse, accessToken, client))
                            .doOnError(e -> log.error("Failed to fetch instruments for {}: {}", symbol, e.getMessage()));
                })
                .doOnError(e -> {
                    if (e instanceof IllegalStateException && e.getMessage().contains("No Zerodha access token")) {
                        tokenTracker.setTokenUnavailable();
                        tokenTracker.trackTokenMissing(SCHEDULER_NAME);
                    }
                });
    }

    /**
     * Builds the complete OptionChain by filtering instruments and fetching strike data.
     */
    private Mono<OptionChain> buildOptionChain(String symbol, BigDecimal spotPrice,
                                               KiteInstrumentsResponse instrumentsResponse,
                                               String accessToken, WebClient client) {
        if (instrumentsResponse == null || instrumentsResponse.getData() == null) {
            return Mono.error(new IllegalStateException("Invalid instruments response"));
        }

        // Find the nearest weekly expiry date
        LocalDate nearestExpiry = findNearestWeeklyExpiry(instrumentsResponse.getData(), symbol);
        if (nearestExpiry == null) {
            log.warn("No option instruments found for {} with weekly expiry", symbol);
            return Mono.just(OptionChain.builder()
                    .symbol(symbol)
                    .spotPrice(spotPrice)
                    .expiryDate(LocalDate.now().plusDays(7))
                    .strikes(new ArrayList<>())
                    .fetchedAt(Instant.now())
                    .build());
        }

        // Filter instruments for this symbol and expiry
        List<KiteInstrumentsResponse.Instrument> optionInstruments = instrumentsResponse.getData()
                .stream()
                .filter(inst -> inst.getSegment() != null && inst.getSegment().equals("NFO"))
                .filter(inst -> inst.getExpiryDate() != null && inst.getExpiryDate().equals(nearestExpiry))
                .filter(inst -> inst.getInstrumentType() != null &&
                        (inst.getInstrumentType().equals("CE") || inst.getInstrumentType().equals("PE")))
                .filter(inst -> inst.getTradingSymbol() != null && inst.getTradingSymbol().contains(symbol))
                .toList();

        if (optionInstruments.isEmpty()) {
            log.warn("No option instruments found for {} with expiry {}", symbol, nearestExpiry);
            return Mono.just(OptionChain.builder()
                    .symbol(symbol)
                    .spotPrice(spotPrice)
                    .expiryDate(nearestExpiry)
                    .strikes(new ArrayList<>())
                    .fetchedAt(Instant.now())
                    .build());
        }

        // Group by strike price
        Map<BigDecimal, List<KiteInstrumentsResponse.Instrument>> strikeGroups = optionInstruments
                .stream()
                .collect(java.util.stream.Collectors.groupingBy(inst ->
                        new BigDecimal(inst.getStrikePrice())));

        // Fetch quote data for all strikes
        List<String> instrumentTokens = optionInstruments.stream()
                .map(inst -> "NFO:" + inst.getTradingSymbol())
                .distinct()
                .toList();

        return fetchQuotesForStrikes(client, instrumentTokens)
                .map(quotesResponse -> buildStrikesFromQuotes(symbol, spotPrice, nearestExpiry,
                        strikeGroups, quotesResponse))
                .map(strikes -> OptionChain.builder()
                        .symbol(symbol)
                        .spotPrice(spotPrice)
                        .expiryDate(nearestExpiry)
                        .strikes(strikes)
                        .fetchedAt(Instant.now())
                        .build());
    }

    /**
     * Fetches quote data for all option strikes.
     */
    private Mono<KiteQuotesResponse> fetchQuotesForStrikes(WebClient client, List<String> instrumentTokens) {
        if (instrumentTokens.isEmpty()) {
            return Mono.just(new KiteQuotesResponse());
        }

        // Build comma-separated list of instrument tokens
        String tokens = String.join(",", instrumentTokens);

        return client.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/quote")
                        .queryParam("i", tokens)
                        .queryParam("mode", "full")
                        .build())
                .retrieve()
                .bodyToMono(KiteQuotesResponse.class)
                .onErrorResume(e -> {
                    log.error("Failed to fetch quotes for strikes: {}", e.getMessage());
                    return Mono.just(new KiteQuotesResponse());
                });
    }

    /**
     * Builds OptionStrike objects from quote data.
     */
    private List<OptionStrike> buildStrikesFromQuotes(String symbol, BigDecimal spotPrice,
                                                      LocalDate expiryDate,
                                                      Map<BigDecimal, List<KiteInstrumentsResponse.Instrument>> strikeGroups,
                                                      KiteQuotesResponse quotesResponse) {
        List<OptionStrike> strikes = new ArrayList<>();

        if (quotesResponse == null || quotesResponse.getData() == null) {
            return strikes;
        }

        Map<String, KiteQuotesResponse.QuoteData> quoteMap = quotesResponse.getData();

        for (Map.Entry<BigDecimal, List<KiteInstrumentsResponse.Instrument>> entry : strikeGroups.entrySet()) {
            BigDecimal strikePrice = entry.getKey();
            List<KiteInstrumentsResponse.Instrument> instruments = entry.getValue();

            KiteQuotesResponse.QuoteData callQuote = null;
            KiteQuotesResponse.QuoteData putQuote = null;

            // Find call and put quotes for this strike
            for (KiteInstrumentsResponse.Instrument inst : instruments) {
                String tradingSymbol = "NFO:" + inst.getTradingSymbol();
                KiteQuotesResponse.QuoteData quote = quoteMap.get(tradingSymbol);

                if (quote != null) {
                    if ("CE".equals(inst.getInstrumentType())) {
                        callQuote = quote;
                    } else if ("PE".equals(inst.getInstrumentType())) {
                        putQuote = quote;
                    }
                }
            }

            // Build OptionStrike with available data
            OptionStrike.OptionStrikeBuilder builder = OptionStrike.builder()
                    .strikePrice(strikePrice);

            if (callQuote != null) {
                builder.callOI(callQuote.getOpenInterest())
                        .callOIChange(callQuote.getOiDayChange())
                        .callLTP(BigDecimal.valueOf(callQuote.getLastPrice()))
                        .callIV(BigDecimal.valueOf(callQuote.getImpliedVolatility()));
            } else {
                builder.callOI(0).callOIChange(0).callLTP(BigDecimal.ZERO).callIV(BigDecimal.ZERO);
            }

            if (putQuote != null) {
                builder.putOI(putQuote.getOpenInterest())
                        .putOIChange(putQuote.getOiDayChange())
                        .putLTP(BigDecimal.valueOf(putQuote.getLastPrice()))
                        .putIV(BigDecimal.valueOf(putQuote.getImpliedVolatility()));
            } else {
                builder.putOI(0).putOIChange(0).putLTP(BigDecimal.ZERO).putIV(BigDecimal.ZERO);
            }

            long callOI = callQuote != null ? callQuote.getOpenInterest() : 0;
            long putOI = putQuote != null ? putQuote.getOpenInterest() : 0;
            builder.totalOI(callOI + putOI);

            strikes.add(builder.build());
        }

        log.info("Built {} strikes for {} with expiry {}", strikes.size(), symbol, expiryDate);
        return strikes;
    }

    /**
     * Finds the nearest weekly expiry date (typically Thursday).
     */
    private LocalDate findNearestWeeklyExpiry(List<KiteInstrumentsResponse.Instrument> instruments, String symbol) {
        return instruments.stream()
                .filter(inst -> inst.getExpiryDate() != null)
                .filter(inst -> inst.getSegment() != null && inst.getSegment().equals("NFO"))
                .filter(inst -> inst.getTradingSymbol() != null && inst.getTradingSymbol().contains(symbol))
                .map(KiteInstrumentsResponse.Instrument::getExpiryDate)
                .distinct()
                .filter(date -> date.isAfter(LocalDate.now()))
                .min(LocalDate::compareTo)
                .orElse(null);
    }

    // ─── Serialization / Deserialization ──────────────────────────────────────────

    /**
     * Serializes an OptionChain to JSON string for Redis caching.
     */
    private String serializeOptionChain(OptionChain optionChain) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper()
                    .writeValueAsString(optionChain);
        } catch (Exception e) {
            log.error("Failed to serialize OptionChain: {}", e.getMessage());
            throw new RuntimeException(e);
        }
    }

    /**
     * Deserializes a JSON string from Redis back to an OptionChain object.
     */
    private OptionChain deserializeOptionChain(String json) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper()
                    .readValue(json, OptionChain.class);
        } catch (Exception e) {
            log.error("Failed to deserialize OptionChain: {}", e.getMessage());
            throw new RuntimeException(e);
        }
    }

    // ─── Inner DTOs for Kite API Response ──────────────────────────────────────────

    /**
     * Minimal representation of Kite Connect Quote API response.
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
            private double lastPrice;

            @JsonProperty("open_interest")
            private long openInterest;

            @JsonProperty("volume")
            private long volume;

            @JsonProperty("timestamp")
            private String timestamp;
        }
    }
}
