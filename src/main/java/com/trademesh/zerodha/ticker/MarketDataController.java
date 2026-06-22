package com.trademesh.zerodha.ticker;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.util.Map;

/**
 * REST controller exposing live market data sourced from the KiteTicker WebSocket feed.
 *
 * Endpoints:
 * - {@code GET /market/price/{symbol}}   – latest price from Redis cache.
 * - {@code GET /market/stream/{symbol}}  – Server-Sent Events (SSE) live price stream.
 * - {@code GET /market/status}           – WebSocket connection health.
 *
 * Supported symbols: {@code NIFTY50}, {@code NIFTYBANK} (case-insensitive).
 */
@Slf4j
@RestController
@RequestMapping("/market")
@RequiredArgsConstructor
@Tag(name = "Market Data", description = "Live market price endpoints powered by Zerodha KiteTicker")
public class MarketDataController {

    private static final String PRICE_KEY_PREFIX = "price:";

    private final ReactiveStringRedisTemplate redisTemplate;
    private final ZerodhaTickerService        tickerService;

    // ─── Latest price (Redis cache) ───────────────────────────────────────────────

    /**
     * Returns the latest cached price for the given symbol from Redis.
     *
     * <p>Prices are written by {@link ZerodhaTickerService} on every tick with a
     * 60-second TTL.  If the key has expired (no tick in the last 60 s) or the symbol
     * is unknown, a 404 is returned.
     *
     * <p>Example: {@code GET /market/price/NIFTY50}
     *
     * @param symbol instrument symbol, e.g. {@code NIFTY50} or {@code NIFTYBANK}
     */
    @Operation(summary = "Get latest cached price for a symbol from Redis")
    @GetMapping("/price/{symbol}")
    public Mono<ResponseEntity<Map<String, Object>>> getPrice(
            @Parameter(description = "Instrument symbol (NIFTY50 or NIFTYBANK)")
            @PathVariable String symbol) {

        String normalised = symbol.toUpperCase();
        String redisKey   = PRICE_KEY_PREFIX + normalised;

        log.debug("Fetching price from Redis for key: {}", redisKey);

        return redisTemplate.opsForValue()
                .get(redisKey)
                .map(price -> {
                    log.debug("Price found in Redis: {}={}", redisKey, price);
                    Map<String, Object> body = Map.of(
                            "symbol",    normalised,
                            "lastPrice", new BigDecimal(price),
                            "source",    "redis-cache",
                            "ttlSeconds", 60
                    );
                    return ResponseEntity.ok(body);
                })
                .switchIfEmpty(Mono.defer(() -> {
                    log.warn("No price found in Redis for symbol: {}", normalised);
                    Map<String, Object> body = Map.of(
                            "symbol",  normalised,
                            "error",   "Price not available. Either the symbol is unknown or "
                                     + "no tick has been received in the last 60 seconds.",
                            "hint",    "Supported symbols: NIFTY50, NIFTYBANK"
                    );
                    return Mono.just(ResponseEntity.status(HttpStatus.NOT_FOUND)
                            .<Map<String, Object>>body(body));
                }))
                .onErrorResume(ex -> {
                    log.error("Redis error fetching price for {}: {}", normalised, ex.getMessage(), ex);
                    Map<String, Object> body = Map.of(
                            "symbol", normalised,
                            "error",  "Internal error fetching price: " + ex.getMessage()
                    );
                    return Mono.just(ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                            .<Map<String, Object>>body(body));
                });
    }

    // ─── SSE live price stream ────────────────────────────────────────────────────

    /**
     * Server-Sent Events endpoint that streams live tick prices for a given symbol.
     *
     * <p>Clients can subscribe with {@code EventSource} in the browser or
     * {@code curl -N http://localhost:8080/market/stream/NIFTY50}.
     *
     * <p>Example: {@code GET /market/stream/NIFTY50}
     *
     * @param symbol instrument symbol, e.g. {@code NIFTY50} or {@code NIFTYBANK}
     */
    @Operation(summary = "Server-Sent Events stream of live tick prices for a symbol")
    @GetMapping(value = "/stream/{symbol}", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<Map<String, Object>> streamPrice(
            @Parameter(description = "Instrument symbol (NIFTY50 or NIFTYBANK)")
            @PathVariable String symbol) {

        String normalised = symbol.toUpperCase();
        log.info("SSE client subscribed to live price stream for: {}", normalised);

        return tickerService.tickStream()
                .filter(tick -> normalised.equalsIgnoreCase(tick.getTradingSymbol()))
                .map(tick -> Map.<String, Object>of(
                        "symbol",    tick.getTradingSymbol(),
                        "lastPrice", tick.getLastPrice(),
                        "high",      tick.getHighPrice(),
                        "low",       tick.getLowPrice(),
                        "volume",    tick.getVolume(),
                        "timestamp", tick.getTimestamp().toString()
                ))
                .doOnCancel(() -> log.info("SSE client disconnected from stream: {}", normalised));
    }

    // ─── Connection status ────────────────────────────────────────────────────────

    /**
     * Returns the current KiteTicker WebSocket connection status.
     *
     * <p>Example: {@code GET /market/status}
     */
    @Operation(summary = "KiteTicker WebSocket connection health status")
    @GetMapping("/status")
    public Mono<ResponseEntity<Map<String, Object>>> connectionStatus() {
        boolean connected = tickerService.isConnected();
        Map<String, Object> body = Map.of(
                "websocketConnected", connected,
                "status", connected ? "CONNECTED" : "DISCONNECTED",
                "instruments", Map.of(
                        "NIFTY50",   ZerodhaTickerService.TOKEN_NIFTY_50,
                        "NIFTYBANK", ZerodhaTickerService.TOKEN_NIFTY_BANK
                )
        );
        HttpStatus status = connected ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE;
        return Mono.just(ResponseEntity.status(status).body(body));
    }
}
