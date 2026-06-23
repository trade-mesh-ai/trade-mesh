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

    /**
     * Runs trading cycle entry on Thursday at 9:30 AM IST.
     * <p>
     * Triggers strategy evaluation and potential trade entry for both NIFTY and BANKNIFTY.
     * This is the main entry point for the weekly trading cycle.
     * <p>
     * Cron: "0 30 9 * * THU"
     * - 0: second (0)
     * - 30: minute (30)
     * - 9: hour (9 AM)
     * - *: day of month (any)
     * - *: month (any)
     * - THU: day of week (Thursday)
     */
    @Scheduled(cron = "0 30 9 * * THU", zone = "Asia/Kolkata")
    public void runThursdayEntry() {
        log.info("🚀 Starting Thursday 9:30 AM trading cycle entry");

        // Run trading cycle for NIFTY
        tradingOrchestrator.runTradingCycle("NIFTY")
                .doOnSuccess(result -> {
                    if (result.getIsExecutable()) {
                        log.info("✅ NIFTY trading cycle executed successfully");
                    } else {
                        log.info("⏭️ NIFTY trading cycle skipped: {}", result.getSkipReason());
                    }
                })
                .doOnError(e -> log.error("❌ Error in NIFTY trading cycle: {}", e.getMessage(), e))
                .subscribe();

        // Run trading cycle for BANKNIFTY
        tradingOrchestrator.runTradingCycle("BANKNIFTY")
                .doOnSuccess(result -> {
                    if (result.getIsExecutable()) {
                        log.info("✅ BANKNIFTY trading cycle executed successfully");
                    } else {
                        log.info("⏭️ BANKNIFTY trading cycle skipped: {}", result.getSkipReason());
                    }
                })
                .doOnError(e -> log.error("❌ Error in BANKNIFTY trading cycle: {}", e.getMessage(), e))
                .subscribe();
    }

    /**
     * Monitors open positions every 5 minutes during market hours.
     * <p>
     * Runs from 9:15 AM to 3:30 PM IST, Monday to Friday.
     * Updates position values and checks for stop loss / target triggers.
     * <p>
     * Cron: "0 *\/5 9-15 * * MON-FRI"
     * - 0: second (0)
     * - *\/5: every 5 minutes
     * - 9-15: hours 9 AM to 3 PM (note: 15 = 3 PM, but cron hour 15 is 3 PM)
     * - *: day of month (any)
     * - *: month (any)
     * - MON-FRI: Monday to Friday
     * <p>
     * Note: This runs at :00, :05, :10, :15, :20, :25, :30, :35, :40, :45, :50, :55
     * of each hour from 9 AM to 3 PM.
     */
    @Scheduled(cron = "0 */5 9-15 * * MON-FRI", zone = "Asia/Kolkata")
    public void monitorPositionsDuringMarketHours() {
        log.debug("📊 Monitoring open positions during market hours");

        // Monitor NIFTY positions
        tradingOrchestrator.monitorOpenPositions("NIFTY")
                .doOnSuccess(count -> {
                    if (count > 0) {
                        log.debug("Updated {} NIFTY positions", count);
                    }
                })
                .doOnError(e -> log.error("Error monitoring NIFTY positions: {}", e.getMessage(), e))
                .subscribe();

        // Monitor BANKNIFTY positions
        tradingOrchestrator.monitorOpenPositions("BANKNIFTY")
                .doOnSuccess(count -> {
                    if (count > 0) {
                        log.debug("Updated {} BANKNIFTY positions", count);
                    }
                })
                .doOnError(e -> log.error("Error monitoring BANKNIFTY positions: {}", e.getMessage(), e))
                .subscribe();
    }

    /**
     * Forces exit of all open positions on Thursday at 2:30 PM IST.
     * <p>
     * This is the end-of-day forced exit to ensure no positions are held overnight.
     * Closes all open positions regardless of P&L status.
     * <p>
     * Cron: "0 30 14 * * THU"
     * - 0: second (0)
     * - 30: minute (30)
     * - 14: hour (2 PM, 14:30 = 2:30 PM)
     * - *: day of month (any)
     * - *: month (any)
     * - THU: day of week (Thursday)
     */
    @Scheduled(cron = "0 30 14 * * THU", zone = "Asia/Kolkata")
    public void forcedExitAtMarketClose() {
        log.info("🔚 Forcing exit of all open positions at 2:30 PM");

        // Exit all NIFTY positions
        tradingOrchestrator.exitAllPositions("NIFTY")
                .doOnSuccess(count -> {
                    log.info("Closed {} NIFTY positions at market close", count);
                })
                .doOnError(e -> log.error("Error closing NIFTY positions: {}", e.getMessage(), e))
                .subscribe();

        // Exit all BANKNIFTY positions
        tradingOrchestrator.exitAllPositions("BANKNIFTY")
                .doOnSuccess(count -> {
                    log.info("Closed {} BANKNIFTY positions at market close", count);
                })
                .doOnError(e -> log.error("Error closing BANKNIFTY positions: {}", e.getMessage(), e))
                .subscribe();

        // Generate and send daily report
        dailyReportService.generateDailyReport()
                .doOnSuccess(report -> log.info("Daily report generated and sent"))
                .doOnError(e -> log.error("Error generating daily report: {}", e.getMessage(), e))
                .subscribe();
    }
}
