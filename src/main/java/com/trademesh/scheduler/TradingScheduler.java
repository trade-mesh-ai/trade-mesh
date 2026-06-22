package com.trademesh.scheduler;

import com.trademesh.strategy.StrategyEngine;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Scheduled tasks for the TradeMesh platform.
 * TODO: Add market-hours guard so tasks only run during NSE/BSE trading sessions.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TradingScheduler {

    private final StrategyEngine strategyEngine;

    /**
     * Evaluates all active strategies every minute during market hours.
     * Cron: every minute, Mon–Fri.
     */
    @Scheduled(cron = "0 * * * * MON-FRI")
    public void runStrategyEvaluation() {
        log.info("Scheduled strategy evaluation triggered");
        strategyEngine.evaluate()
                .doOnError(e -> log.error("Strategy evaluation error", e))
                .subscribe();
    }

    /**
     * Daily housekeeping task – runs at 4:00 PM IST (10:30 UTC) on weekdays.
     * Syncs portfolio positions, archives completed orders, etc.
     */
    @Scheduled(cron = "0 30 10 * * MON-FRI")
    public void dailyHousekeeping() {
        log.info("Daily housekeeping started");
        // TODO: sync positions, archive orders, generate daily report
    }
}
