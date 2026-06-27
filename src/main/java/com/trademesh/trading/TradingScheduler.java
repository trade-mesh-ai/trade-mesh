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
     * Runs trading cycle entry on Thursday at 9:30 AM IST.
     * <p>
     * Triggers strategy evaluation and potential trade entry for both NIFTY and NIFTYBANK.
     * This is the main entry point for the weekly trading cycle.
     * <p>
     * Cron: "0 30 9 * * THU"
     * - 0: second (0)
     * - 30: minute (30)
     * - 9: hour (9 AM)
     * - *: day of month (any)
     * - *: month (any)
     * - THU: day of week (Tuesday)
     */
    // Expiry day: THU — see trading.expiry-day in application.yml
    @Scheduled(cron = "0 30 9 * * THU", zone = "Asia/Kolkata")
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
     * Monitors open positions every 5 minutes during market hours.
     * <p>
     * Runs from 9:15 AM to 2:55 PM IST, Monday to Friday.
     * Updates position values and checks for stop loss / target triggers.
     * <p>
     * Cron: "0 *\/5 9-14 * * MON-FRI"
     * - 0: second (0)
     * - *\/5: every 5 minutes
     * - 9-14: hours 9 AM to 2 PM (stops at 2:55 PM, not 3:55 PM)
     * - *: day of month (any)
     * - *: month (any)
     * - MON-FRI: Monday to Friday
     * <p>
     * Note: This runs at :00, :05, :10, :15, :20, :25, :30, :35, :40, :45, :50, :55
     * of each hour from 9 AM to 2 PM.
     */
    @Scheduled(cron = "0 */5 9-14 * * MON-FRI", zone = "Asia/Kolkata")
    public void monitorPositionsDuringMarketHours() {
        marketCalendarService.isMarketOpen()
                .filter(Boolean::booleanValue)
                .flatMap(ignored -> reactor.core.publisher.Mono.fromRunnable(() -> {
                    log.debug("📊 Monitoring open positions during market hours");

                    // Monitor NIFTY positions
                    tradingOrchestrator.monitorOpenPositions("NIFTY50")
                            .doOnSuccess(count -> {
                                if (count > 0) {
                                    log.debug("Updated {} NIFTY positions", count);
                                }
                            })
                            .doOnError(e -> log.error("Error monitoring NIFTY positions: {}", e.getMessage(), e))
                            .subscribe(ignored2 -> {
                            }, e -> log.error("Unhandled error in reactive chain: {}", e.getMessage(), e));

                    // Monitor NIFTYBANK positions
                    tradingOrchestrator.monitorOpenPositions("NIFTYBANK")
                            .doOnSuccess(count -> {
                                if (count > 0) {
                                    log.debug("Updated {} NIFTYBANK positions", count);
                                }
                            })
                            .doOnError(e -> log.error("Error monitoring NIFTYBANK positions: {}", e.getMessage(), e))
                            .subscribe(ignored2 -> {
                            }, e -> log.error("Unhandled error in reactive chain: {}", e.getMessage(), e));
                }))
                .subscribe(ignored -> {
                }, e -> log.error("Unhandled error in reactive chain: {}", e.getMessage(), e));
    }

    /**
     * Exits all open positions on Thursday at 3:15 PM IST (expiry day).
     * <p>
     * This is the safer expiry day exit before NSE close.
     * Closes all open positions regardless of P&L status.
     * <p>
     * Cron: "0 15 15 * * THU"
     * - 0: second (0)
     * - 15: minute (15)
     * - 15: hour (3 PM, 15:15 = 3:15 PM)
     * - *: day of month (any)
     * - *: month (any)
     * - THU: day of week (Thursday)
     */
    // Expiry day: THU — see trading.expiry-day in application.yml
    @Scheduled(cron = "0 15 15 * * THU", zone = "Asia/Kolkata")
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
     * Generates and sends daily P&L report at 3:45 PM IST on expiry day.
     */
    @Scheduled(cron = "0 45 15 * * THU", zone = "Asia/Kolkata")
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
}
