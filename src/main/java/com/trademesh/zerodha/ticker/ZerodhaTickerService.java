package com.trademesh.zerodha.ticker;

import com.trademesh.config.ApplicationConstants;
import com.trademesh.zerodha.auth.TokenAvailabilityTracker;
import com.trademesh.zerodha.auth.ZerodhaAuthService;
import com.trademesh.zerodha.auth.ZerodhaConfig;
import com.zerodhatech.kiteconnect.kitehttp.exceptions.KiteException;
import com.zerodhatech.models.Tick;
import com.zerodhatech.ticker.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static com.trademesh.config.ApplicationConstants.CACHE_PRICE_TTL;
import static com.trademesh.config.ApplicationConstants.PRICE_KEY_PREFIX;

/**
 * Zerodha KiteTicker WebSocket service.
 * <p>
 * Responsibilities:
 * - Fetches the current access token from Redis.
 * - Connects to the KiteTicker WebSocket feed.
 * - Subscribes to NIFTY 50 (token 256265) and NIFTY BANK (token 260105) in FULL mode.
 * - On each tick: logs the data, stores the latest price in Redis under
 * {@code price:{symbol}} with a 60-second TTL, and emits to a reactive Flux.
 * <p>
 * Connection lifecycle (connect / disconnect / reconnect) is delegated to
 * {@link TickerConnectionManager}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ZerodhaTickerService {

    private static final String SCHEDULER_NAME = "ZerodhaTickerService";

    private final ZerodhaConfig config;
    private final ZerodhaAuthService authService;
    private final ReactiveStringRedisTemplate redisTemplate;
    private final TokenAvailabilityTracker tokenTracker;
    /**
     * Hot Flux sink – all subscribers receive every tick.
     */
    private final Sinks.Many<TickData> tickSink =
            Sinks.many().multicast().onBackpressureBuffer();
    // ─── Internal state ───────────────────────────────────────────────────────────
    private volatile KiteTicker kiteTicker;

    // ─── Public API ───────────────────────────────────────────────────────────────

    /**
     * Returns a hot {@link Flux} of live {@link TickData} for downstream consumers
     * (strategy engine, signal service, etc.).
     */
    public Flux<TickData> tickStream() {
        return tickSink.asFlux();
    }

    /**
     * Connects to the KiteTicker WebSocket using the access token stored in Redis.
     *
     * @return {@link Mono} that completes once the connection attempt is initiated
     */
    public Mono<Void> connect() {
        return authService.getAccessToken()
                .switchIfEmpty(Mono.error(new IllegalStateException(
                        "No Zerodha access token found in Redis. "
                                + "Please authenticate via GET /zerodha/login first.")))
                .flatMap(accessToken -> {
                    log.info("Initialising KiteTicker WebSocket connection …");
                    tokenTracker.resetTracking(SCHEDULER_NAME);
                    initKiteTicker(accessToken);
                    return Mono.<Void>empty();
                })
                .doOnError(e -> {
                    if (e instanceof IllegalStateException && e.getMessage().contains("No Zerodha access token")) {
                        tokenTracker.trackTokenMissing(SCHEDULER_NAME);
                    } else {
                        log.error("Failed to connect KiteTicker: {}", e.getMessage(), e);
                    }
                });
    }

    /**
     * Gracefully disconnects the KiteTicker WebSocket.
     */
    public void disconnect() {
        if (kiteTicker != null) {
            log.info("Disconnecting KiteTicker WebSocket …");
            kiteTicker.disconnect();
        }
    }

    /**
     * Returns {@code true} if the WebSocket is currently connected.
     */
    public boolean isConnected() {
        return kiteTicker != null && kiteTicker.isConnectionOpen();
    }

    // ─── KiteTicker initialisation ────────────────────────────────────────────────

    private void initKiteTicker(String accessToken) {
        kiteTicker = new KiteTicker(accessToken, config.getApiKey());

        // ── Callbacks ──────────────────────────────────────────────────────────────

        kiteTicker.setOnConnectedListener(new OnConnect() {
            @Override
            public void onConnected() {
                log.info("✅ KiteTicker WebSocket CONNECTED");
                subscribeToInstruments();
            }
        });

        kiteTicker.setOnDisconnectedListener(new OnDisconnect() {
            @Override
            public void onDisconnected() {
                log.warn("⚠️  KiteTicker WebSocket DISCONNECTED");
            }
        });

        kiteTicker.setOnErrorListener(new OnError() {
            @Override
            public void onError(Exception exception) {
                log.error("❌ KiteTicker error: {}", exception.getMessage(), exception);
            }

            @Override
            public void onError(KiteException kiteException) {
                log.error("❌ KiteTicker KiteException: {}", kiteException.getMessage(), kiteException);
            }
        });

        kiteTicker.setOnTickerArrivalListener(new OnTicks() {
            @Override
            public void onTicks(ArrayList<Tick> ticks) {
                ticks.forEach(ZerodhaTickerService.this::processTick);
            }
        });

        // Enable auto-reconnect (max 5 retries, 5-second interval)
        try {
            kiteTicker.setTryReconnection(true);
            kiteTicker.setMaximumRetries(5);
            kiteTicker.setMaximumRetryInterval(5);
        } catch (KiteException e) {
            log.warn("Could not configure reconnection settings: {}", e.getMessage());
        }

        kiteTicker.connect();
    }

    // ─── Subscription ─────────────────────────────────────────────────────────────

    private void subscribeToInstruments() {
        ArrayList<Long> tokens = new ArrayList<>(List.of(ApplicationConstants.TOKEN_NIFTY_50, ApplicationConstants.TOKEN_NIFTY_BANK));
        log.info("Subscribing to instrument tokens: {}", tokens);
        kiteTicker.subscribe(tokens);
        kiteTicker.setMode(tokens, KiteTicker.modeFull);
        log.info("Subscription mode set to FULL for tokens: {}", tokens);
    }

    // ─── Tick processing ──────────────────────────────────────────────────────────

    private void processTick(Tick tick) {
        String symbol = ApplicationConstants.TOKEN_SYMBOL_MAP.getOrDefault(tick.getInstrumentToken(), "UNKNOWN");

        // Tick.getVolumeTradedToday() returns double in this SDK version
        long volume = (long) tick.getVolumeTradedToday();

        TickData tickData = TickData.builder()
                .instrumentToken(tick.getInstrumentToken())
                .tradingSymbol(symbol)
                .lastPrice(BigDecimal.valueOf(tick.getLastTradedPrice()))
                .openPrice(BigDecimal.valueOf(tick.getOpenPrice()))
                .highPrice(BigDecimal.valueOf(tick.getHighPrice()))
                .lowPrice(BigDecimal.valueOf(tick.getLowPrice()))
                .closePrice(BigDecimal.valueOf(tick.getClosePrice()))
                .volume(volume)
                .buyQuantity(BigDecimal.valueOf(tick.getTotalBuyQuantity()))
                .sellQuantity(BigDecimal.valueOf(tick.getTotalSellQuantity()))
                .timestamp(Instant.now())
                .build();

        log.debug("Tick [{}] LTP={} H={} L={} V={}",
                symbol,
                tickData.getLastPrice(),
                tickData.getHighPrice(),
                tickData.getLowPrice(),
                tickData.getVolume());

        // Emit to reactive stream
        tickSink.tryEmitNext(tickData);

        // Store latest price in Redis (non-blocking)
        String redisKey = PRICE_KEY_PREFIX + symbol;
        redisTemplate.opsForValue()
                .set(redisKey, tickData.getLastPrice().toPlainString(), CACHE_PRICE_TTL)
                .subscribe(
                        ok -> log.trace("Stored price in Redis: {}={}", redisKey, tickData.getLastPrice()),
                        err -> log.error("Failed to store price in Redis for {}: {}", symbol, err.getMessage())
                );
    }
}
