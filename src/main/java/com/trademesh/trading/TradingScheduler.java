package com.trademesh.trading;

import com.trademesh.strategy.config.StrategiesConfig;
import com.trademesh.strategy.config.StrategyProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * Scheduler for automated trading cycles and position management.
 * <p>
 * Manages scheduled tasks for:
 * - Daily entry signals at 9:30 AM (strategy selection handled by StrategySelector)
 * - Forced position exit at 3:15 PM
 * - Daily P&L reporting at 3:45 PM
 * <p>
 * All times are in IST (Asia/Kolkata timezone).
 * Market hours: 9:15 AM - 3:30 PM IST, Monday to Friday only.
 * <p>
 * Strategy selection is automatic via StrategySelector based on market condition:
 * - Tuesday (EXPIRY_DAY) → EXPIRY_DAY_STRANGLE
 * - Other days (SIDEWAYS) → IRON_CONDOR
 * - Other days (TRENDING_BULLISH) → BULL_PUT_SPREAD
 * - Other days (TRENDING_BEARISH) → BEAR_CALL_SPREAD
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TradingScheduler {

    private final TradingOrchestrator tradingOrchestrator;
    private final DailyReportService dailyReportService;
    private final MarketCalendarService marketCalendarService;
    private final StrategiesConfig strategiesConfig;

    /**
     * Runs DTE exit safety net check every weekday at 9:00 AM IST.
     * <p>
     * Provides a daily backstop for any mark-to-market DTE-exit failures.
     * Closes positions where isDteExitDue() returns true for DTE-governed strategies only.
     * <p>
     * Cron: "0 0 9 * * MON-FRI"
     * - 0: second (0)
     * - 0: minute (0)
     * - 9: hour (9 AM)
     * - *: day of month (any)
     * - *: month (any)
     * - MON-FRI: day of week (Monday to Friday)
     */
    @Scheduled(cron = "0 0 9 * * MON-FRI", zone = "Asia/Kolkata")
    public void dailySafetyNetCheck() {
        tradingOrchestrator.runDteExitSafetyNetCheck()
                .doOnSuccess(count -> log.info("DTE safety net check completed, closed {} positions", count))
                .doOnError(e -> log.error("Error in DTE safety net check: {}", e.getMessage(), e))
                .subscribe(ignored -> {
                }, e -> log.error("Unhandled error in reactive chain: {}", e.getMessage(), e));
    }

    /**
     * Runs trading cycle entry every weekday at 9:30 AM IST.
     * <p>
     * Triggers strategy evaluation and potential trade entry for NIFTY50.
     * StrategySelector automatically picks the right strategy based on market condition:
     * - Tuesday → EXPIRY_DAY_STRANGLE
     * - Other days → IRON_CONDOR, BULL_PUT_SPREAD, or BEAR_CALL_SPREAD
     * <p>
     * Cron: "0 30 9 * * MON-FRI"
     * - 0: second (0)
     * - 30: minute (30)
     * - 9: hour (9 AM)
     * - *: day of month (any)
     * - *: month (any)
     * - MON-FRI: day of week (Monday to Friday)
     */
    @Scheduled(cron = "0 30 9 * * MON-FRI", zone = "Asia/Kolkata")
    public void scheduledDailyEntry() {
        executeDailyEntry(false);
    }

    /**
     * Manually triggers daily entry, bypassing market open checks.
     * <p>
     * Useful for testing or manual intervention outside of market hours.
     * Logs a warning to indicate this is for testing purposes only.
     */
    public void triggerDailyEntryManually() {
        log.warn("Manual daily entry trigger — FOR TESTING ONLY");
        executeDailyEntry(true);
    }

    /**
     * Executes the daily entry trading cycle.
     * <p>
     * Runs trading cycle for NIFTY50. StrategySelector automatically determines
     * which strategy to use based on current market condition.
     *
     * @param forceExecution if true, bypasses market open check; if false, checks market hours
     */
    private void executeDailyEntry(boolean forceExecution) {
        Mono<Boolean> marketCheck = forceExecution
                ? Mono.just(true)
                : marketCalendarService.isMarketOpen();

        marketCheck
                .filter(Boolean::booleanValue)
                .flatMap(ignored -> Mono.fromRunnable(() -> {
                    log.info("Starting daily trading cycle entry");

                    tradingOrchestrator.runTradingCycle("NIFTY50")
                            .doOnSuccess(result -> {
                                if (result.getIsExecutable()) {
                                    log.info("NIFTY trading cycle executed successfully");
                                } else {
                                    log.info("NIFTY trading cycle skipped: {}", result.getSkipReason());
                                }
                            })
                            .doOnError(e -> log.error("Error in NIFTY trading cycle: {}", e.getMessage(), e))
                            .subscribe(ignored2 -> {
                            }, e -> log.error("Unhandled error in reactive chain: {}", e.getMessage(), e));
                }))
                .subscribe(ignored -> {
                }, e -> log.error("Unhandled error in reactive chain: {}", e.getMessage(), e));
    }

     /**
      * Forces exit of EXPIRY_DAY_STRANGLE positions every weekday at 3:15 PM IST.
      * <p>
      * Closes all open EXPIRY_DAY_STRANGLE positions for NIFTY50 regardless of P&L status.
      * Other strategies (IRON_CONDOR, BULL_PUT_SPREAD, BEAR_CALL_SPREAD) are left untouched
      * and managed by their own DTE-based exit logic.
      * <p>
      * Cron: "0 15 15 * * MON-FRI"
      * - 0: second (0)
      * - 15: minute (15)
      * - 15: hour (3 PM, 15:15 = 3:15 PM)
      * - *: day of month (any)
      * - *: month (any)
      * - MON-FRI: day of week (Monday to Friday)
      */
     @Scheduled(cron = "0 15 15 * * MON-FRI", zone = "Asia/Kolkata")
     public void scheduledDailyExit() {
         marketCalendarService.isMarketOpen()
                 .filter(Boolean::booleanValue)
                 .flatMap(ignored -> Mono.fromRunnable(() -> {
                     log.info("Forcing exit of EXPIRY_DAY_STRANGLE positions at 3:15 PM");

                     tradingOrchestrator.exitPositionsForStrategy("NIFTY50", "EXPIRY_DAY_STRANGLE")
                             .doOnSuccess(count -> {
                                 log.info("Closed {} EXPIRY_DAY_STRANGLE positions at daily exit", count);
                             })
                             .doOnError(e -> log.error("Error closing EXPIRY_DAY_STRANGLE positions: {}", e.getMessage(), e))
                             .subscribe(ignored2 -> {
                             }, e -> log.error("Unhandled error in reactive chain: {}", e.getMessage(), e));
                 }))
                 .subscribe(ignored -> {
                 }, e -> log.error("Unhandled error in reactive chain: {}", e.getMessage(), e));
     }

    /**
     * Generates and sends daily P&L report every weekday at 3:45 PM IST.
     * <p>
     * Aggregates daily trading metrics and sends comprehensive summary via Telegram.
     * <p>
     * Cron: "0 45 15 * * MON-FRI"
     * - 0: second (0)
     * - 45: minute (45)
     * - 15: hour (3 PM, 15:45 = 3:45 PM)
     * - *: day of month (any)
     * - *: month (any)
     * - MON-FRI: day of week (Monday to Friday)
     */
    @Scheduled(cron = "0 45 15 * * MON-FRI", zone = "Asia/Kolkata")
    public void scheduledDailyReport() {
        marketCalendarService.isMarketOpen()
                .filter(Boolean::booleanValue)
                .flatMap(ignored -> Mono.fromRunnable(() -> {
                    log.info("Generating daily P&L report");

                    dailyReportService.generateDailyReport()
                            .doOnSuccess(report -> log.info("Daily report generated and sent"))
                            .doOnError(e -> log.error("Error generating daily report: {}", e.getMessage(), e))
                            .subscribe(ignored2 -> {
                            }, e -> log.error("Unhandled error in reactive chain: {}", e.getMessage(), e));
                }))
                .subscribe(ignored -> {
                }, e -> log.error("Unhandled error in reactive chain: {}", e.getMessage(), e));
    }

    /**
     * Runs periodic entry retry every 15 minutes during market hours (9:00 AM - 1:00 PM IST).
     * <p>
     * Attempts to enter trades for strategies that:
     * - Have no open position for today
     * - Have not yet passed their latest-entry-time
     * <p>
     * Stops retrying once:
     * - A position opens for the strategy, OR
     * - The strategy's latest-entry-time passes
     * <p>
     * Cron: "0 *\/15 9-13 * * MON-FRI"
     * - 0: second (0)
     * - *\/15: every 15 minutes
     * - 9-13: hours 9 AM to 1 PM (covers all strategies' latest-entry-times)
     * - *: day of month (any)
     * - *: month (any)
     * - MON-FRI: day of week (Monday to Friday)
     */
    @Scheduled(cron = "0 45,0,15,30 9-13 * * MON-FRI", zone = "Asia/Kolkata")
    public void scheduledEntryRetry() {
        executeEntryRetry();
    }

    /**
     * Executes periodic entry retry for all four strategies.
     * <p>
     * For each strategy:
     * 1. Check if no open position exists for today
     * 2. Check if current time is before strategy's latest-entry-time
     * 3. If both true: run trading cycle
     * 4. Log outcome: entered / skipped with reason / gate closed
     * <p>
     * RiskManager's concurrent position limit (3 max) gates every retry attempt
     * through runTradingCycle(), preventing runaway position creation.
     */
    private void executeEntryRetry() {
        String[] strategies = {"IRON_CONDOR", "BULL_PUT_SPREAD", "BEAR_CALL_SPREAD", "EXPIRY_DAY_STRANGLE"};
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Kolkata"));

        for (String strategyName : strategies) {
            // Check if no open position exists for this strategy
            tradingOrchestrator.hasOpenPositionForStrategy(strategyName)
                    .flatMap(hasOpenPosition -> {
                        if (hasOpenPosition) {
                            log.info("Retry gate closed for {}: position already open", strategyName);
                            return Mono.empty();
                        }

                        // Check if current time is before latest-entry-time
                        StrategyProperties cfg = strategiesConfig.getStrategy(strategyName);
                        LocalTime currentTime = LocalTime.now(ZoneId.of("Asia/Kolkata"));
                        LocalTime latestTime = LocalTime.parse(cfg.getLatestEntryTime(),
                                DateTimeFormatter.ofPattern("HH:mm"));

                        if (currentTime.isAfter(latestTime)) {
                            log.info("Retry gate closed for {}: latest-entry-time ({}) has passed (current: {})",
                                    strategyName, cfg.getLatestEntryTime(),
                                    currentTime.format(DateTimeFormatter.ofPattern("HH:mm")));
                            return Mono.empty();
                        }

                        // Both conditions met: attempt entry
                        log.info("Retry attempt for {} (current: {}, limit: {})",
                                strategyName,
                                currentTime.format(DateTimeFormatter.ofPattern("HH:mm")),
                                cfg.getLatestEntryTime());

                        return tradingOrchestrator.runTradingCycle("NIFTY50")
                                .doOnSuccess(result -> {
                                    if (result.getIsExecutable()) {
                                        log.info("Retry ENTERED for {}", strategyName);
                                    } else {
                                        log.info("Retry skipped for {}: {}", strategyName, result.getSkipReason());
                                    }
                                })
                                .doOnError(e -> log.error("Error in retry for {}: {}", strategyName, e.getMessage(), e));
                    })
                    .subscribe(
                            ignored -> {},
                            e -> log.error("Error checking retry conditions for {}: {}", strategyName, e.toString(), e)
                    );
        }
    }
}
