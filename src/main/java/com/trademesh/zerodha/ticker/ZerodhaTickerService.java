package com.trademesh.zerodha.ticker;

import com.trademesh.zerodha.auth.ZerodhaAuthService;
import com.trademesh.zerodha.auth.ZerodhaConfig;
import com.zerodhatech.kiteconnect.kitehttp.exceptions.KiteException;
import com.zerodhatech.models.Tick;
import com.zerodhatech.ticker.KiteTicker;
import com.zerodhatech.ticker.OnConnect;
import com.zerodhatech.ticker.OnDisconnect;
import com.zerodhatech.ticker.OnError;
import com.zerodhatech.ticker.OnTicks;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Zerodha KiteTicker WebSocket service.
 *
 * <p>Responsibilities:
 * <ul>
 *   <li>Fetches the current access token from Redis.</li>
 *   <li>Connects to the KiteTicker WebSocket feed.</li>
 *   <li>Subscribes to NIFTY 50 (token 256265) and NIFTY BANK (token 260105) in FULL mode.</li>
 *   <li>On each tick: logs the data, stores the latest price in Redis under
 *       {@code price:{symbol}} with a 60-second TTL, and emits to a reactive Flux.</li>
 * </ul>
 *
 * <p>Connection lifecycle (connect / disconnect / reconnect) is delegated to
 * {@link TickerConnectionManager}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ZerodhaTickerService {

    // ─── Instrument tokens ────────────────────────────────────────────────────────
    public static final long TOKEN_NIFTY_50   = 256265L;
    public static final long TOKEN_NIFTY_BANK = 260105L;

    /** Human-readable symbol map keyed by instrument token. */
    private static final Map<Long, String> TOKEN_SYMBOL_MAP = Map.of(
            TOKEN_NIFTY_50,   "NIFTY50",
            TOKEN_NIFTY_BANK, "NIFTYBANK"
    );

    /** Redis key prefix for latest price cache. */
    private static final String PRICE_KEY_PREFIX = "price:";

    /** Price TTL in Redis – 60 seconds. */
    private static final Duration PRICE_TTL = Duration.ofSeconds(60);

    // ─── Dependencies ─────────────────────────────────────────────────────────────
    private final ZerodhaConfig               config;
    private final ZerodhaAuthService          authService;
    private final ReactiveStringRedisTemplate redisTemplate;

    // ─── Internal state ───────────────────────────────────────────────────────────
    private volatile KiteTicker kiteTicker;

    /** Hot Flux sink – all subscribers receive every tick. */
    private final Sinks.Many<TickData> tickSink =
            Sinks.many().multicast().onBackpressureBuffer();

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
                    initKiteTicker(accessToken);
                    return Mono.<Void>empty();
                })
                .doOnError(e -> log.error("Failed to connect KiteTicker: {}", e.getMessage(), e));
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
        ArrayList<Long> tokens = new ArrayList<>(List.of(TOKEN_NIFTY_50, TOKEN_NIFTY_BANK));
        log.info("Subscribing to instrument tokens: {}", tokens);
        kiteTicker.subscribe(tokens);
        kiteTicker.setMode(tokens, KiteTicker.modeFull);
        log.info("Subscription mode set to FULL for tokens: {}", tokens);
    }

    // ─── Tick processing ──────────────────────────────────────────────────────────

    private void processTick(Tick tick) {
        String symbol = TOKEN_SYMBOL_MAP.getOrDefault(tick.getInstrumentToken(), "UNKNOWN");

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
                .set(redisKey, tickData.getLastPrice().toPlainString(), PRICE_TTL)
                .subscribe(
                        ok  -> log.trace("Stored price in Redis: {}={}", redisKey, tickData.getLastPrice()),
                        err -> log.error("Failed to store price in Redis for {}: {}", symbol, err.getMessage())
                );
    }
}
