package com.trademesh.zerodha.auth;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Scheduled task that monitors the Zerodha / Kite Connect access token health.
 *
 * <p>Zerodha access tokens are valid for <strong>one trading day only</strong> and expire
 * at midnight IST.  This scheduler runs every day at <strong>08:00 AM IST</strong> to
 * verify that a valid token is present in Redis before the market opens (NSE/BSE open at
 * 09:15 AM IST).
 *
 * <p>If the token is missing or expired, a prominent {@code WARN} log is emitted so that
 * alerting systems (e.g. Grafana Loki, PagerDuty log-based alerts) can trigger a
 * notification to the operator to re-authenticate via {@code GET /zerodha/login}.
 *
 * <p>Cron expression {@code "0 0 8 * * *"} → every day at 08:00:00 (server local time,
 * which should be set to Asia/Kolkata / IST).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TokenRefreshScheduler {

    private final ZerodhaAuthService authService;

    // ─── Daily token health check ─────────────────────────────────────────────────

    /**
     * Runs every day at 08:00 AM IST.
     *
     * <ul>
     *   <li>If a valid token is found in Redis → logs an INFO confirmation.</li>
     *   <li>If no token is found (expired or never set) → logs a WARN alert so that
     *       operators know they must re-authenticate before trading begins.</li>
     * </ul>
     *
     * <p>Cron: {@code "0 0 8 * * *"} (second=0, minute=0, hour=8, every day)
     */
    @Scheduled(cron = "0 0 8 * * *")
    public void checkTokenHealth() {
        log.info("=== [TokenRefreshScheduler] Daily Zerodha token health check starting ===");

        authService.isTokenValid()
                .subscribe(
                        valid -> {
                            if (Boolean.TRUE.equals(valid)) {
                                log.info("[TokenRefreshScheduler] ✅ Zerodha access token is VALID "
                                        + "and present in Redis. Trading can proceed normally.");
                            } else {
                                log.warn("[TokenRefreshScheduler] ⚠️  ALERT: Zerodha access token is "
                                        + "MISSING or EXPIRED in Redis! "
                                        + "Please re-authenticate immediately via GET /zerodha/login "
                                        + "before market open (09:15 AM IST). "
                                        + "All automated trading will FAIL until a valid token is obtained.");
                            }
                        },
                        error -> log.error("[TokenRefreshScheduler] ❌ ERROR checking token validity "
                                + "in Redis: {}", error.getMessage(), error)
                );
    }

    // ─── Pre-market reminder ──────────────────────────────────────────────────────

    /**
     * Secondary reminder at 09:00 AM IST (15 minutes before NSE/BSE open).
     *
     * <p>If the token is still missing at this point, the WARN log is escalated to ERROR
     * to maximise visibility in log-based alerting pipelines.
     *
     * <p>Cron: {@code "0 0 9 * * MON-FRI"} (weekdays only – no trading on weekends)
     */
    @Scheduled(cron = "0 0 9 * * MON-FRI")
    public void preMarketTokenCheck() {
        log.info("=== [TokenRefreshScheduler] Pre-market token check (T-15 min before NSE open) ===");

        authService.isTokenValid()
                .subscribe(
                        valid -> {
                            if (Boolean.TRUE.equals(valid)) {
                                log.info("[TokenRefreshScheduler] ✅ Pre-market check PASSED – "
                                        + "Zerodha access token is ready.");
                            } else {
                                log.error("[TokenRefreshScheduler] 🚨 CRITICAL: Zerodha access token "
                                        + "is STILL MISSING 15 minutes before market open! "
                                        + "Automated trading is DISABLED. "
                                        + "Authenticate NOW via GET /zerodha/login.");
                            }
                        },
                        error -> log.error("[TokenRefreshScheduler] ❌ Redis error during pre-market "
                                + "token check: {}", error.getMessage(), error)
                );
    }
}
