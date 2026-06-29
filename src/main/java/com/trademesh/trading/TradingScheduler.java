package com.trademesh.trading;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Scheduler for automated trading cycles and position monitoring.
 * <p>
 * Manages scheduled tasks for:
 * - Entry signals on specific days and times
 * - Continuous position monitoring during market hours
 * - Forced position exit at market close
 * - Daily P&L reporting
 * <p>
 * All times are in IST (Asia/Kolkata timezone).
 * Market hours: 9:15 AM - 3:30 PM IST, Monday to Friday only.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TradingScheduler {

    private final TradingOrchestrator tradingOrchestrator;
    private final DailyReportService dailyReportService;
    private final MarketCalendarService marketCalendarService;

    /**
     * Runs trading cycle entry on Tuesday at 9:30 AM IST.
     * <p>
     * Triggers strategy evaluation and potential trade entry for both NIFTY and NIFTYBANK.
     * This is the main entry point for the weekly trading cycle.
     * <p>
     * Cron: "0 30 9 * * TUE"
     * - 0: second (0)
     * - 30: minute (30)
     * - 9: hour (9 AM)
     * - *: day of month (any)
     * - *: month (any)
     * - TUE: day of week (Tuesday)
     */
    // Expiry day: TUE — see trading.expiry-day in application.yml
    @Scheduled(cron = "0 30 9 * * TUE", zone = "Asia/Kolkata")
    public void scheduledExpiryDayEntry() {
        executeExpiryDayEntry(false);
    }

    /**
     * Manually triggers expiry day entry, bypassing market open checks.
     * <p>
     * Useful for testing or manual intervention outside of market hours.
     * Logs a warning to indicate this is for testing purposes only.
     */
    public void triggerExpiryDayEntryManually() {
        log.warn("Manual expiry day trigger invoked — bypassing market hours check. FOR TESTING ONLY.");
        executeExpiryDayEntry(true);
    }

    /**
     * Executes the expiry day entry trading cycle.
     * <p>
     * Runs trading cycles for both NIFTY and NIFTYBANK.
     *
     * @param forceExecution if true, bypasses market open check; if false, checks market hours
     */
    private void executeExpiryDayEntry(boolean forceExecution) {
        reactor.core.publisher.Mono<Boolean> marketCheck = forceExecution
                ? reactor.core.publisher.Mono.just(true)
                : marketCalendarService.isMarketOpen();

        marketCheck
                .filter(Boolean::booleanValue)
                .flatMap(ignored -> reactor.core.publisher.Mono.fromRunnable(() -> {
                    log.info("🚀 Starting Tuesday 9:30 AM trading cycle entry");

                    // Run trading cycle for NIFTY
                    tradingOrchestrator.runTradingCycle("NIFTY50")
                            .doOnSuccess(result -> {
                                if (result.getIsExecutable()) {
                                    log.info("✅ NIFTY trading cycle executed successfully");
                                } else {
                                    log.info("⏭️ NIFTY trading cycle skipped: {}", result.getSkipReason());
                                }
                            })
                            .doOnError(e -> log.error("❌ Error in NIFTY trading cycle: {}", e.getMessage(), e))
                            .subscribe(ignored2 -> {
                            }, e -> log.error("Unhandled error in reactive chain: {}", e.getMessage(), e));

                    // Run trading cycle for NIFTYBANK
                    tradingOrchestrator.runTradingCycle("NIFTYBANK")
                            .doOnSuccess(result -> {
                                if (result.getIsExecutable()) {
                                    log.info("✅ NIFTYBANK trading cycle executed successfully");
                                } else {
                                    log.info("⏭️ NIFTYBANK trading cycle skipped: {}", result.getSkipReason());
                                }
                            })
                            .doOnError(e -> log.error("❌ Error in NIFTYBANK trading cycle: {}", e.getMessage(), e))
                            .subscribe(ignored2 -> {
                            }, e -> log.error("Unhandled error in reactive chain: {}", e.getMessage(), e));
                }))
                .subscribe(ignored -> {
                }, e -> log.error("Unhandled error in reactive chain: {}", e.getMessage(), e));
    }


     /**
      * Runs Iron Condor trading cycle on Monday, Wednesday, Tuesday, Friday
      * at 9:30 AM IST. Tuesday is excluded — that is expiry day for NIFTY
      * weekly options and handled by scheduledExpiryDayEntry().
      * <p>
      * Cron: "0 30 9 * * MON,WED,THU,FRI"
      */
     @Scheduled(cron = "0 30 9 * * MON,WED,THU,FRI", zone = "Asia/Kolkata")
    public void scheduledIronCondorEntry() {
        executeIronCondorEntry(false);
    }

    /**
     * Manually triggers Iron Condor entry, bypassing market open checks.
     * <p>
     * Useful for testing or manual intervention outside of market hours.
     * Logs a warning to indicate this is for testing purposes only.
     */
    public void triggerIronCondorEntryManually() {
        log.warn("Manual Iron Condor trigger invoked — FOR TESTING ONLY.");
        executeIronCondorEntry(true);
    }

    /**
     * Executes the Iron Condor entry trading cycle.
     * <p>
     * Runs trading cycle for NIFTY50 only.
     *
     * @param forceExecution if true, bypasses market open check; if false, checks market hours
     */
    private void executeIronCondorEntry(boolean forceExecution) {
        reactor.core.publisher.Mono<Boolean> marketCheck = forceExecution
                ? reactor.core.publisher.Mono.just(true)
                : marketCalendarService.isMarketOpen();

        marketCheck
                .filter(Boolean::booleanValue)
                .flatMap(ignored -> reactor.core.publisher.Mono.fromRunnable(() -> {
                    log.info("🚀 Starting Iron Condor trading cycle entry");

                    tradingOrchestrator.runTradingCycle("NIFTY50")
                            .doOnSuccess(result -> {
                                if (result.getIsExecutable()) {
                                    log.info("✅ Iron Condor NIFTY cycle executed successfully");
                                } else {
                                    log.info("⏭️ Iron Condor NIFTY skipped: {}", result.getSkipReason());
                                }
                            })
                            .doOnError(e -> log.error("❌ Error in Iron Condor NIFTY cycle: {}", e.getMessage(), e))
                            .subscribe(ignored2 -> {
                            }, e -> log.error("Unhandled error in reactive chain: {}", e.getMessage(), e));
                }))
                .subscribe(ignored -> {
                }, e -> log.error("Unhandled error in reactive chain: {}", e.getMessage(), e));
    }

    /**
     * Exits all open positions on Tuesday at 3:15 PM IST (expiry day).
     * <p>
     * This is the safer expiry day exit before NSE close.
     * Closes all open positions regardless of P&L status.
     * <p>
     * Cron: "0 15 15 * * TUE"
     * - 0: second (0)
     * - 15: minute (15)
     * - 15: hour (3 PM, 15:15 = 3:15 PM)
     * - *: day of month (any)
     * - *: month (any)
     * - TUE: day of week (Tuesday)
     */
    // Expiry day: TUE — see trading.expiry-day in application.yml
    @Scheduled(cron = "0 15 15 * * TUE", zone = "Asia/Kolkata")
    public void scheduledExpiryDayExit() {
        marketCalendarService.isMarketOpen()
                .filter(Boolean::booleanValue)
                .flatMap(ignored -> reactor.core.publisher.Mono.fromRunnable(() -> {
                    log.info("🔚 Forcing exit of all open positions at 3:15 PM on expiry day");

                    // Exit all NIFTY positions
                    tradingOrchestrator.exitAllPositions("NIFTY50")
                            .doOnSuccess(count -> {
                                log.info("Closed {} NIFTY positions at expiry day exit", count);
                            })
                            .doOnError(e -> log.error("Error closing NIFTY positions: {}", e.getMessage(), e))
                            .subscribe(ignored2 -> {
                            }, e -> log.error("Unhandled error in reactive chain: {}", e.getMessage(), e));

                    // Exit all NIFTYBANK positions
                    tradingOrchestrator.exitAllPositions("NIFTYBANK")
                            .doOnSuccess(count -> {
                                log.info("Closed {} NIFTYBANK positions at expiry day exit", count);
                            })
                            .doOnError(e -> log.error("Error closing NIFTYBANK positions: {}", e.getMessage(), e))
                            .subscribe(ignored2 -> {
                            }, e -> log.error("Unhandled error in reactive chain: {}", e.getMessage(), e));
                }))
                .subscribe(ignored -> {
                }, e -> log.error("Unhandled error in reactive chain: {}", e.getMessage(), e));
    }

    /**
     * Forces exit of all open Iron Condor positions at 3:15 PM IST
     * on Monday, Wednesday, Thursday, Friday.
     * Tuesday is handled by scheduledExpiryDayExit().
     * <p>
     * Cron: "0 15 15 * * MON,WED,THU,FRI"
     */
    @Scheduled(cron = "0 15 15 * * MON,WED,THU,FRI", zone = "Asia/Kolkata")
    public void scheduledIronCondorExit() {
        marketCalendarService.isMarketOpen()
                .filter(Boolean::booleanValue)
                .flatMap(ignored -> reactor.core.publisher.Mono.fromRunnable(() -> {
                    log.info("🔚 Forcing exit of all open Iron Condor positions at 3:15 PM");

                    tradingOrchestrator.exitAllPositions("NIFTY50")
                            .doOnSuccess(count -> log.info("Closed {} NIFTY Iron Condor positions at EOD", count))
                            .doOnError(e -> log.error("Error closing NIFTY positions: {}", e.getMessage(), e))
                            .subscribe(ignored2 -> {
                            }, e -> log.error("Unhandled error in reactive chain: {}", e.getMessage(), e));
                }))
                .subscribe(ignored -> {
                }, e -> log.error("Unhandled error in reactive chain: {}", e.getMessage(), e));
    }

    /**
     * Generates and sends daily P&L report at 3:45 PM IST on expiry day.
     */
    @Scheduled(cron = "0 45 15 * * TUE", zone = "Asia/Kolkata")
    public void scheduleDailyReport() {
        marketCalendarService.isMarketOpen()
                .filter(Boolean::booleanValue)
                .flatMap(ignored -> reactor.core.publisher.Mono.fromRunnable(() -> {
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
     * Generates daily P&L report at 3:45 PM on non-expiry days.
     * <p>
     * Cron: "0 45 15 * * MON,WED,THU,FRI"
     */
    @Scheduled(cron = "0 45 15 * * MON,WED,THU,FRI", zone = "Asia/Kolkata")
    public void scheduleIronCondorDailyReport() {
        marketCalendarService.isMarketOpen()
                .filter(Boolean::booleanValue)
                .flatMap(ignored -> reactor.core.publisher.Mono.fromRunnable(() -> {
                    dailyReportService.generateDailyReport()
                            .doOnSuccess(report -> log.info("Iron Condor daily report generated and sent"))
                            .doOnError(e -> log.error("Error generating report: {}", e.getMessage(), e))
                            .subscribe(ignored2 -> {
                            }, e -> log.error("Unhandled error in reactive chain: {}", e.getMessage(), e));
                }))
                .subscribe(ignored -> {
                }, e -> log.error("Unhandled error in reactive chain: {}", e.getMessage(), e));
    }
}
