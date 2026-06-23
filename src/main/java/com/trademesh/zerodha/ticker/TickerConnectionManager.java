package com.trademesh.zerodha.ticker;

import com.trademesh.zerodha.auth.TokenAvailabilityTracker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Manages the lifecycle of the {@link ZerodhaTickerService} WebSocket connection.
 * <p>
 * Responsibilities:
 * - Auto-start: attempts to connect once the Spring application is ready.
 * - Health check: polls the connection state every 30 seconds and triggers a reconnect if closed.
 * - Alerting: logs WARN / ERROR messages on disconnect so alerting pipelines can notify operators.
 * - Reconnect back-off: tracks consecutive reconnect attempts and escalates log severity after failures.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TickerConnectionManager {

    private static final String SCHEDULER_NAME = "TickerConnectionManager";
    private static final Object TOKEN_LOCK = new Object();
    private final ZerodhaTickerService tickerService;
    private final TokenAvailabilityTracker tokenTracker;
    /**
     * Counts consecutive failed reconnect attempts since last successful connection.
     */
    private final AtomicInteger reconnectAttempts = new AtomicInteger(0);
    @Value("${zerodha.ticker.max-reconnect-alerts:10}")
    private int maxReconnectAlerts;

    // ─── Application startup ──────────────────────────────────────────────────────

    /**
     * Triggered once the Spring context is fully started.
     * <p>
     * Attempts to connect to the KiteTicker WebSocket. If the access token is not
     * yet available in Redis (user hasn't authenticated), the error is logged as WARN
     * and the health-check scheduler will retry automatically.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        log.info("=== [TickerConnectionManager] Application ready – initiating KiteTicker connection ===");
        attemptConnect();
    }

    // ─── Periodic health check ────────────────────────────────────────────────────

    /**
     * Runs every 30 seconds to verify the WebSocket is still alive.
     * <p>
     * - If connected → resets the reconnect counter and logs INFO.
     * - If disconnected → logs WARN/ERROR and triggers a reconnect.
     * <p>
     * Cron: {@code "0/30 * * * * *"} (every 30 seconds)
     */
    @Scheduled(fixedDelay = 30_000)
    public void healthCheck() {
        if (tickerService.isConnected()) {
            if (reconnectAttempts.get() > 0) {
                log.info("[TickerConnectionManager] ✅ KiteTicker reconnected successfully after {} attempt(s).",
                        reconnectAttempts.get());
                reconnectAttempts.set(0);
            } else {
                log.debug("[TickerConnectionManager] ✅ KiteTicker WebSocket is healthy.");
            }
        } else {
            int attempts = reconnectAttempts.incrementAndGet();
            logDisconnectAlert(attempts);
            attemptConnect();
        }
    }

    // ─── Connect helper ───────────────────────────────────────────────────────────

    /**
     * Calls {@link ZerodhaTickerService#connect()} and handles errors gracefully.
     */
    private void attemptConnect() {
        tickerService.connect()
                .onErrorResume(ex -> {
                    // Check if this is a token missing error
                    if (ex instanceof IllegalStateException && ex.getMessage().contains("No Zerodha access token")) {
                        synchronized (TOKEN_LOCK) {
                            tokenTracker.setTokenUnavailable();
                            tokenTracker.trackTokenMissing(SCHEDULER_NAME);
                        }
                    } else {
                        log.warn("[TickerConnectionManager] ⚠️  KiteTicker connect attempt failed: {}",
                                ex.getMessage());
                    }
                    return Mono.empty();
                })
                .doOnSuccess(v -> {
                    synchronized (TOKEN_LOCK) {
                        tokenTracker.resetTracking(SCHEDULER_NAME);
                    }
                })
                .subscribe();
    }

    // ─── Alert logging ────────────────────────────────────────────────────────────

    /**
     * Escalates log severity based on the number of consecutive reconnect attempts.
     * <p>
     * - Attempts 1–maxReconnectAlerts: WARN
     * - Attempts &gt; maxReconnectAlerts: ERROR (triggers alerting pipelines)
     * <p>
     * The threshold is configurable via zerodha.ticker.max-reconnect-alerts (default: 10 cycles = 5 minutes).
     */
    private void logDisconnectAlert(int attempts) {
        String msg = "[TickerConnectionManager] ⚠️  KiteTicker WebSocket is DISCONNECTED "
                + "(attempt #{} to reconnect). "
                + "Live market data is UNAVAILABLE. "
                + "Strategies relying on real-time prices may produce stale signals.";

        if (attempts <= maxReconnectAlerts) {
            log.warn(msg, attempts);
        } else {
            log.error("[TickerConnectionManager] 🚨 CRITICAL: KiteTicker WebSocket has been "
                    + "DISCONNECTED for {} consecutive health-check cycles! "
                    + "All live market data is LOST. "
                    + "Manual intervention may be required – check Redis access token validity "
                    + "and network connectivity to wss://ws.kite.trade.", attempts);
        }
    }
}
