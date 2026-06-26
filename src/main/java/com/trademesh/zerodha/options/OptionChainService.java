package com.trademesh.zerodha.options;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.trademesh.config.WebClientConfig;
import com.trademesh.intelligence.iv.ImpliedVolatilityCalculator;
import com.trademesh.zerodha.auth.TokenAvailabilityTracker;
import com.trademesh.zerodha.auth.ZerodhaAuthService;
import com.trademesh.zerodha.auth.ZerodhaConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static com.trademesh.config.ApplicationConstants.*;

/**
 * Service for fetching and caching option chain data from Zerodha Kite REST API.
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
    private static final Duration INSTRUMENTS_CACHE_TTL = Duration.ofHours(24);
    private static final String INSTRUMENTS_CACHE_KEY = "instruments:nfo:ALL";

    // ─── Dependencies ─────────────────────────────────────────────────────────
    private final ZerodhaConfig config;
    private final ZerodhaAuthService authService;
    private final ReactiveStringRedisTemplate redisTemplate;
    private final WebClient.Builder webClientBuilder;
    private final TokenAvailabilityTracker tokenTracker;
    private final ObjectMapper objectMapper;
    private final WebClientConfig webClientConfig;

    // ─── Public API ────────────────────────────────────────────────────────────────

    /**
     * Fetches the option chain for a given symbol (NIFTY50 or NIFTYBANK).
     * Attempts to retrieve from Redis cache first; if not found, fetches from Kite API.
     *
     * @param symbol trading symbol (e.g., "NIFTY50", "NIFTYBANK", "NIFTY 50", "BANK NIFTY")
     * @return {@link Mono} emitting the OptionChain
     */
    public Mono<OptionChain> fetchOptionChain(String symbol) {
        String normalizedSymbol = normalizeSymbol(symbol);
        String redisKey = REDIS_KEY_OPTION_CHAIN_PREFIX + normalizedSymbol;

        // Try to get from cache first
        return redisTemplate.opsForValue().get(redisKey)
                .flatMap(cached -> {
                    log.debug("Option chain for {} found in cache", normalizedSymbol);
                    OptionChain optionChain = deserializeOptionChain(cached);
                    log.info("Successfully retrieved option chain for {} from cache", normalizedSymbol);
                    return Mono.just(optionChain);
                })
                .switchIfEmpty(
                        // Cache miss: fetch from Kite API
                        authService.getAccessToken()  // ← fetch ONCE here
                                .switchIfEmpty(Mono.error(new IllegalStateException(
                                        "No Zerodha access token found in Redis. Please authenticate first.")))
                                .flatMap(accessToken ->
                        fetchFromKiteAPI(normalizedSymbol, accessToken)
                                .flatMap(optionChain -> {
                                    // Cache the result
                                    String json = serializeOptionChain(optionChain);
                                    return redisTemplate.opsForValue()
                                            .set(redisKey, json, CACHE_OPTION_CHAIN_TTL)
                                            .thenReturn(optionChain)
                                            .doOnSuccess(oc -> log.info("Cached option chain for {} in Redis (TTL=3m)", normalizedSymbol));
                                })
                )
                .doOnNext(optionChain -> {
                    if (optionChain != null) {
                        log.info("Successfully fetched option chain for {}: {} strikes, expiry: {}",
                                normalizedSymbol, optionChain.getStrikes().size(), optionChain.getExpiryDate());
                    } else {
                        log.warn("Option chain for {} is null after fetch operation", normalizedSymbol);
                    }
                })
                .doOnError(e -> log.error("Failed to fetch option chain for {}: {}", normalizedSymbol, e.getMessage(), e)));
    }

    // ─── Kite API Integration ─────────────────────────────────────────────────────

    /**
     * Fetches option chain data from Zerodha Kite REST API.
     * Uses the Quote API to fetch data for all strikes of the nearest weekly expiry.
     *
     * @param symbol the trading symbol (e.g., "NIFTY50", "NIFTYBANK")
     * @param accessToken the Zerodha access token to use for API calls
     * @return Mono emitting the OptionChain
     */
    private Mono<OptionChain> fetchFromKiteAPI(String symbol, String accessToken) {
        log.info("Fetching option chain for {} from Kite API", symbol);
        tokenTracker.resetTracking(SCHEDULER_NAME);

        Long instrumentToken = SYMBOL_TOKEN_MAP.get(symbol);
        if (instrumentToken == null) {
            return Mono.error(new IllegalArgumentException(
                    "Unknown symbol: " + symbol + ". Supported: NIFTY50, NIFTYBANK"));
        }

        WebClient client = webClientBuilder
                .baseUrl(config.getBaseUrl())
                .defaultHeader(HttpHeaders.AUTHORIZATION, "token " + config.getApiKey() + ":" + accessToken)
                .build();

        // Fetch quote data for the underlying using API-friendly format
        String apiFriendlySymbol = toApiFriendlySymbol(symbol);
        String encodedSymbol = URLEncoder.encode(apiFriendlySymbol, StandardCharsets.UTF_8);
        return client.get()
                .uri("/quote?i=NSE:" + encodedSymbol)
                .retrieve()
                .bodyToMono(KiteQuotesResponse.class)
                .timeout(Duration.ofSeconds(10))
                .doOnSuccess(quoteResp -> log.info("Successfully fetched quote data for {} from Kite API", symbol))
                .flatMap(response -> parseOptionChain(symbol, apiFriendlySymbol, response))
                .doOnError(e -> log.error("Kite API error for {}: {}", symbol, e.getMessage()));
    }

    /**
     * Parses the Kite API response and builds an OptionChain object.
     * Fetches all option instruments for the symbol, filters by nearest weekly expiry,
     * and fetches quote data for each strike.
     */
    private Mono<OptionChain> parseOptionChain(String symbol, String apiFriendlySymbol, KiteQuotesResponse response) {
        if (response == null || response.getData() == null) {
            return Mono.error(new IllegalStateException("Invalid Kite API response"));
        }
        String tradingSymbol = "NSE:" + apiFriendlySymbol;
        KiteQuotesResponse.QuoteData spotData = response.getData().get(tradingSymbol);
        if (spotData == null) {
            log.error("No spot price data found in Kite response for symbol: {}", apiFriendlySymbol);
            return Mono.error(new IllegalStateException(
                    "Spot price data not available for: " + apiFriendlySymbol));
        }

        BigDecimal spotPrice = BigDecimal.valueOf(spotData.getLastPrice());

        // Fetch all option instruments for this symbol
        return authService.getAccessToken()
                .switchIfEmpty(Mono.error(new IllegalStateException(
                        "No Zerodha access token found in Redis. Please authenticate first.")))
                .flatMap(accessToken -> {
                    tokenTracker.resetTracking(SCHEDULER_NAME);

                    WebClient client = webClientConfig.instrumentsClient(accessToken);

                    // Check Redis cache for instruments first
                    String instrumentsCacheKey = INSTRUMENTS_CACHE_KEY;
                    return redisTemplate.opsForValue().get(instrumentsCacheKey)
                            .flatMap(cached -> {
                                log.debug("Instruments for {} found in cache", symbol);
                                try {
                                    List<KiteInstrumentsResponse.Instrument> instruments =
                                            objectMapper.readValue(cached,
                                                    objectMapper.getTypeFactory().constructCollectionType(List.class, KiteInstrumentsResponse.Instrument.class));
                                    return buildOptionChain(symbol, spotPrice, instruments, accessToken);
                                } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                                    log.warn("Failed to deserialize cached instruments for {}: {}", symbol, e.getMessage());
                                    return Mono.empty();
                                }
                            })
                            .switchIfEmpty(
                                    // Cache miss: fetch from Kite API using dedicated instruments client
                                    webClientConfig.instrumentsClient(accessToken).get()
                                            .uri("/instruments")
                                            .retrieve()
                                            .bodyToMono(String.class)
                                            .timeout(Duration.ofSeconds(15))
                                            .doOnSuccess(csv -> log.info("Successfully fetched instruments CSV for {}", symbol))
                                            .flatMap(csv -> {
                                                List<KiteInstrumentsResponse.Instrument> instruments = parseInstrumentsCsv(csv);

                                                // Cache the parsed instruments
                                                try {
                                                    String json = objectMapper.writeValueAsString(instruments);
                                                    return redisTemplate.opsForValue()
                                                            .set(instrumentsCacheKey, json, INSTRUMENTS_CACHE_TTL)
                                                            .thenReturn(instruments);
                                                } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                                                    log.warn("Failed to cache instruments for {}: {}", symbol, e.getMessage());
                                                    return Mono.just(instruments);
                                                }
                                            })
                                            .flatMap(instruments -> buildOptionChain(symbol, spotPrice, instruments, accessToken))
                            )
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
     * Parses CSV response from Kite /instruments endpoint.
     * Kite API returns CSV format with the following columns:
     * 0: instrument_token, 1: exchange_token, 2: tradingsymbol, 3: name, 4: last_price,
     * 5: expiry, 6: strike, 7: tick_size, 8: lot_size, 9: instrument_type, 10: segment, 11: exchange
     */
    private List<KiteInstrumentsResponse.Instrument> parseInstrumentsCsv(String csv) {
        List<KiteInstrumentsResponse.Instrument> instruments = new ArrayList<>();
        if (csv == null || csv.isBlank()) {
            return instruments;
        }

        String[] lines = csv.split("\n");
        // Skip header line (line 0)
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.isBlank()) {
                continue;
            }

            String[] fields = line.split(",", 12);
            if (fields.length < 12) {
                continue;  // skip malformed lines
            }

            if (!(Arrays.asList(fields).contains("NFO-OPT") && (Arrays.asList(fields).contains("\"NIFTY\"") || Arrays.asList(fields).contains("\"BANKNIFTY\"")))) {
                continue;
            }

            try {
                KiteInstrumentsResponse.Instrument inst = new KiteInstrumentsResponse.Instrument();
                inst.setInstrumentToken(Long.parseLong(fields[0].trim()));
                inst.setExchangeToken(Long.parseLong(fields[1].trim()));
                inst.setTradingSymbol(fields[2].trim());
                inst.setName(fields[3].trim());
                inst.setLastPrice(fields[4].isBlank() ? 0.0 : Double.parseDouble(fields[4].trim()));
                inst.setExpiryDate(fields[5].isBlank() ? null : LocalDate.parse(fields[5].trim()));
                inst.setStrikePrice(fields[6].isBlank() ? 0.0 : Double.parseDouble(fields[6].trim()));
                inst.setLotSize(fields[8].isBlank() ? 0 : Integer.parseInt(fields[8].trim()));
                inst.setInstrumentType(fields[9].trim());
                inst.setSegment(fields[10].trim());
                inst.setExchange(fields[11].trim());
                instruments.add(inst);
            } catch (NumberFormatException e) {
                log.warn("Skipping malformed instrument line {}: {}", i, line);
            }
        }
        log.info("Parsed {} instruments from Kite CSV response", instruments.size());
        return instruments;
    }

    /**
     * Builds the complete OptionChain by filtering instruments and fetching strike data.
     */
    private Mono<OptionChain> buildOptionChain(String symbol, BigDecimal spotPrice,
                                               List<KiteInstrumentsResponse.Instrument> instruments,
                                               String accessToken) {
        if (instruments == null || instruments.isEmpty()) {
            return Mono.error(new IllegalStateException("Invalid instruments list"));
        }

        log.info("Building option chain for {} with {} instruments, spot price: {}", symbol, instruments.size(), spotPrice);
        // Find the nearest weekly expiry date
        LocalDate nearestExpiry = findNearestWeeklyExpiry(instruments, symbol);
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
        // Extract base symbol name for matching (e.g., "NIFTY50" -> "NIFTY", "NIFTYBANK" -> "BANKNIFTY")
        BigDecimal atmStrike = spotPrice.divide(BigDecimal.valueOf(100), 0, java.math.RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100));

        String baseSymbol = getBaseSymbolForMatching(symbol);
        List<KiteInstrumentsResponse.Instrument> optionInstruments = instruments
                .stream()
                .filter(inst -> inst.getSegment() != null && inst.getSegment().equals("NFO-OPT"))
                .filter(inst -> inst.getInstrumentType() != null &&
                        (inst.getInstrumentType().equals("CE") || inst.getInstrumentType().equals("PE")))
                .filter(inst -> inst.getTradingSymbol() != null && inst.getTradingSymbol().startsWith(baseSymbol))
                .filter(inst -> inst.getExpiryDate() != null && inst.getExpiryDate().equals(nearestExpiry))
                .filter(inst -> {
                    BigDecimal strike = BigDecimal.valueOf(inst.getStrikePrice());
                    return strike.compareTo(atmStrike.subtract(BigDecimal.valueOf(1000))) >= 0
                            && strike.compareTo(atmStrike.add(BigDecimal.valueOf(1000))) <= 0;
                })
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

        // Log the nearest expiry and strike count
        log.info("Nearest expiry for {}: {}, {} strikes found", symbol, nearestExpiry, optionInstruments.size());

        // Group by strike price
        Map<BigDecimal, List<KiteInstrumentsResponse.Instrument>> strikeGroups = optionInstruments
                .stream()
                .collect(java.util.stream.Collectors.groupingBy(inst ->
                        new BigDecimal(inst.getStrikePrice())));

        // Build a fresh quotes client, not the instruments client
        WebClient quotesClient = webClientBuilder
                .baseUrl(config.getBaseUrl())
                .defaultHeader(HttpHeaders.AUTHORIZATION, "token " + config.getApiKey() + ":" + accessToken)
                .build();

        // Fetch quote data for all strikes
        List<String> instrumentTokens = optionInstruments.stream()
                .map(inst -> "NFO:" + inst.getTradingSymbol())
                .distinct()
                .toList();

        return fetchQuotesForStrikes(quotesClient, instrumentTokens)
                .map(quotesResponse -> buildStrikesFromQuotes(symbol, spotPrice, nearestExpiry,
                        strikeGroups, quotesResponse))
                .map(strikes -> OptionChain.builder()
                        .symbol(symbol)
                        .spotPrice(spotPrice)
                        .expiryDate(nearestExpiry)
                        .strikes(strikes)
                        .fetchedAt(Instant.now())
                        .build())
                .doOnSuccess(optionChain -> {
                    log.info("Fetched and parsed {} NFO instruments for {}", optionInstruments.size(), symbol);
                    log.info("Successfully built option chain for {} with {} strikes, expiry: {}, spot price: {}",
                            symbol, optionChain.getStrikes().size(), nearestExpiry, spotPrice);
                });
    }

    /**
     * Fetches quote data for all option strikes.
     */
    private Mono<com.trademesh.zerodha.options.KiteQuotesResponse> fetchQuotesForStrikes(WebClient client, List<String> instrumentTokens) {
        if (instrumentTokens.isEmpty()) {
            return Mono.just(new com.trademesh.zerodha.options.KiteQuotesResponse());
        }

        // Build comma-separated list of instrument tokens
        //String tokens = String.join(",", instrumentTokens);
        log.info("Fetching quotes for {} strikes: {}", instrumentTokens.size(), instrumentTokens);

        return client.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/quote")
                        .queryParam("i", instrumentTokens)
                        .build())
                .retrieve()
                .bodyToMono(com.trademesh.zerodha.options.KiteQuotesResponse.class)
                .timeout(Duration.ofSeconds(10))
                .doOnSuccess(response -> log.info("Successfully fetched quotes for {} strikes", instrumentTokens.size()))
                .onErrorResume(e -> {
                    log.error("Failed to fetch quotes for strikes: {}", e.getMessage());
                    return Mono.just(new com.trademesh.zerodha.options.KiteQuotesResponse());
                });
    }

    /**
     * Builds OptionStrike objects from quote data.
     */
    private List<OptionStrike> buildStrikesFromQuotes(String symbol, BigDecimal spotPrice,
                                                      LocalDate expiryDate,
                                                      Map<BigDecimal, List<KiteInstrumentsResponse.Instrument>> strikeGroups,
                                                      com.trademesh.zerodha.options.KiteQuotesResponse quotesResponse) {
        List<OptionStrike> strikes = new ArrayList<>();

        if (quotesResponse == null || quotesResponse.getData() == null) {
            return strikes;
        }

        Map<String, com.trademesh.zerodha.options.KiteQuotesResponse.QuoteData> quoteMap = quotesResponse.getData();

        for (Map.Entry<BigDecimal, List<KiteInstrumentsResponse.Instrument>> entry : strikeGroups.entrySet()) {
            BigDecimal strikePrice = entry.getKey();
            List<KiteInstrumentsResponse.Instrument> instruments = entry.getValue();

            com.trademesh.zerodha.options.KiteQuotesResponse.QuoteData callQuote = null;
            com.trademesh.zerodha.options.KiteQuotesResponse.QuoteData putQuote = null;

            // Find call and put quotes for this strike
            for (KiteInstrumentsResponse.Instrument inst : instruments) {
                String tradingSymbol = "NFO:" + inst.getTradingSymbol();
                com.trademesh.zerodha.options.KiteQuotesResponse.QuoteData quote = quoteMap.get(tradingSymbol);
                if (quote == null) {
                    log.warn("No quote data found for strike: {} — skipping", tradingSymbol);
                    continue;
                }

                if ("CE".equals(inst.getInstrumentType())) {
                    callQuote = quote;
                } else if ("PE".equals(inst.getInstrumentType())) {
                    putQuote = quote;
                }
            }

            // Build OptionStrike with available data
            OptionStrike.OptionStrikeBuilder builder = OptionStrike.builder()
                    .strikePrice(strikePrice);

            // Calculate time to expiry in years (using IST timezone)
            LocalDate today = LocalDate.now(ZoneId.of("Asia/Kolkata"));
            long daysToExpiry = Math.max(today.until(expiryDate, ChronoUnit.DAYS), 1);
            double timeToExpiryYears = daysToExpiry / 365.0;

            if (callQuote != null) {
                // OI day change not provided by Kite REST API. Using oiDayHigh - oiDayLow as intraday OI range proxy.
                long callOIChange = callQuote.getOiDayHigh() - callQuote.getOiDayLow();

                // Calculate IV locally using Black-Scholes since Kite REST API does not provide it
                double callIV = ImpliedVolatilityCalculator.calculate(
                        spotPrice.doubleValue(),
                        strikePrice.doubleValue(),
                        timeToExpiryYears,
                        callQuote.getLastPrice(),
                        "CE");

                builder.callOI(callQuote.getOpenInterest())
                        .callOIChange(callOIChange)
                        .callLTP(BigDecimal.valueOf(callQuote.getLastPrice()))
                        .callIV(BigDecimal.valueOf(callIV));
            } else {
                builder.callOI(0).callOIChange(0).callLTP(BigDecimal.ZERO).callIV(BigDecimal.ZERO);
            }

            if (putQuote != null) {
                // OI day change not provided by Kite REST API. Using oiDayHigh - oiDayLow as intraday OI range proxy.
                long putOIChange = putQuote.getOiDayHigh() - putQuote.getOiDayLow();

                // Calculate IV locally using Black-Scholes since Kite REST API does not provide it
                double putIV = ImpliedVolatilityCalculator.calculate(
                        spotPrice.doubleValue(),
                        strikePrice.doubleValue(),
                        timeToExpiryYears,
                        putQuote.getLastPrice(),
                        "PE");

                builder.putOI(putQuote.getOpenInterest())
                        .putOIChange(putOIChange)
                        .putLTP(BigDecimal.valueOf(putQuote.getLastPrice()))
                        .putIV(BigDecimal.valueOf(putIV));
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
        String baseSymbol = getBaseSymbolForMatching(symbol);
        return instruments.stream()
                .filter(inst -> inst.getExpiryDate() != null)
                .filter(inst -> inst.getSegment() != null && inst.getSegment().equals("NFO-OPT"))
                .filter(inst -> inst.getTradingSymbol() != null && inst.getTradingSymbol().startsWith(baseSymbol))
                .map(KiteInstrumentsResponse.Instrument::getExpiryDate)
                .distinct()
                .filter(date -> date.isAfter(LocalDate.now()))
                .min(LocalDate::compareTo)
                .orElse(null);
    }

    /**
     * Extracts the base symbol name for matching against Kite CSV tradingSymbols.
     * Kite CSV uses date-based naming: NIFTY27DEC26000PE, BANKNIFTY26DEC67500PE
     * This method converts the input symbol to the base prefix used in tradingSymbols.
     *
     * @param symbol the normalized symbol (e.g., "NIFTY50", "NIFTYBANK")
     * @return the base symbol prefix for matching (e.g., "NIFTY", "BANKNIFTY")
     */
    private String getBaseSymbolForMatching(String symbol) {
        if (symbol == null) {
            return null;
        }
        return switch (symbol) {
            case "NIFTY50" -> "NIFTY";
            case "NIFTYBANK" -> "BANKNIFTY";
            default -> symbol;
        };
    }

    // ─── Symbol Normalization ─────────────────────────────────────────────────────

    /**
     * Normalizes symbol format for internal use (removes spaces).
     * Converts "NIFTY 50" to "NIFTY50" and "BANK NIFTY" to "NIFTYBANK".
     *
     * @param symbol the input symbol (may contain spaces)
     * @return normalized symbol without spaces
     */
    private String normalizeSymbol(String symbol) {
        if (symbol == null) {
            return null;
        }
        return symbol.replaceAll("\\s+", "");
    }

    /**
     * Converts symbol to API-friendly format (with spaces where needed).
     * Converts "NIFTY50" to "NIFTY 50" and "NIFTYBANK" to "BANK NIFTY".
     *
     * @param symbol the normalized symbol (without spaces)
     * @return API-friendly symbol format
     */
    private String toApiFriendlySymbol(String symbol) {
        if (symbol == null) {
            return null;
        }
        return switch (symbol) {
            case "NIFTY50" -> "NIFTY 50";
            case "NIFTYBANK" -> "NIFTY BANK";
            default -> symbol;
        };
    }

    // ─── Serialization / Deserialization ──────────────────────────────────────────

    /**
     * Serializes an OptionChain to JSON string for Redis caching.
     */
    private String serializeOptionChain(OptionChain optionChain) {
        try {
            return objectMapper.writeValueAsString(optionChain);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            log.error("Failed to serialize OptionChain: {}", e.getMessage());
            throw new RuntimeException("OptionChain serialization failed", e);
        }
    }

    /**
     * Deserializes a JSON string from Redis back to an OptionChain object.
     */
    private OptionChain deserializeOptionChain(String json) {
        try {
            return objectMapper.readValue(json, OptionChain.class);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            log.error("Failed to deserialize OptionChain: {}", e.getMessage());
            throw new RuntimeException("OptionChain deserialization failed", e);
        }
    }

}
