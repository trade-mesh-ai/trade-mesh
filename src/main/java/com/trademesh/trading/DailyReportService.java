package com.trademesh.trading;

import com.trademesh.alert.Alert;
import com.trademesh.alert.AlertType;
import com.trademesh.alert.TelegramNotificationBot;
import com.trademesh.config.LotSizeService;
import com.trademesh.execution.PaperTrade;
import com.trademesh.execution.PaperTradeRepository;
import com.trademesh.execution.TradeStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.List;

/**
 * Service for generating and sending daily trading reports.
 * <p>
 * Aggregates daily trading metrics and sends comprehensive P&L summary via Telegram.
 * Includes:
 * - Total trades executed
 * - Winning and losing trades count
 * - Total P&L for the day
 * - Best and worst trades
 * - Win rate percentage
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DailyReportService {

    private final PaperTradeRepository paperTradeRepository;
    private final TelegramNotificationBot telegramNotificationBot;
    private final LotSizeService lotSizeService;

    /**
     * Computes aggregated daily trading metrics from a list of trades.
     * <p>
     * Calculates all metrics including trade counts, P&L values (in points and rupees),
     * win rate, and identifies best/worst trades with deterministic tie-breaking.
     * <p>
     * Best/worst trades are determined by:
     * - Primary: realizedPnl (descending for best, ascending for worst)
     * - Secondary: exitTime ascending (for deterministic ordering when PnL is equal)
     *
     * @param trades list of closed trades for the day
     * @return DailyReportMetrics with all calculated values; never null
     */
    public DailyReportMetrics computeMetrics(List<PaperTrade> trades) {
        DailyReportMetrics metrics = new DailyReportMetrics();

        if (trades.isEmpty()) {
            return metrics;
        }

        metrics.totalTrades = trades.size();
        metrics.winningTrades = trades.stream()
                .filter(t -> t.getRealizedPnl() != null && t.getRealizedPnl() > 0)
                .count();
        metrics.losingTrades = trades.stream()
                .filter(t -> t.getRealizedPnl() != null && t.getRealizedPnl() < 0)
                .count();

        metrics.totalPnl = trades.stream()
                .mapToDouble(t -> t.getRealizedPnl() != null ? t.getRealizedPnl() : 0)
                .sum();

        // Best trade: highest realizedPnl, with exitTime ascending as tie-breaker
        metrics.bestTrade = trades.stream()
                .max(Comparator.comparing((PaperTrade t) -> t.getRealizedPnl() != null ? t.getRealizedPnl() : 0)
                        .thenComparing(PaperTrade::getExitTime, Comparator.reverseOrder()))
                .orElse(null);

        // Worst trade: lowest realizedPnl, with exitTime ascending as tie-breaker
        metrics.worstTrade = trades.stream()
                .min(Comparator.comparing((PaperTrade t) -> t.getRealizedPnl() != null ? t.getRealizedPnl() : 0)
                        .thenComparing(PaperTrade::getExitTime))
                .orElse(null);

        metrics.winRate = metrics.totalTrades > 0 ? (metrics.winningTrades * 100.0) / metrics.totalTrades : 0;

        // Calculate rupee amounts using per-trade lot sizes
        metrics.totalPnlAmount = trades.stream()
                .mapToDouble(t -> {
                    int lotSize = lotSizeService.getLotSize(t.getSymbol());
                    return (t.getRealizedPnl() != null ? t.getRealizedPnl() : 0) * lotSize;
                })
                .sum();

        if (metrics.bestTrade != null && metrics.bestTrade.getRealizedPnl() != null) {
            metrics.bestPnlAmount = metrics.bestTrade.getRealizedPnl() * lotSizeService.getLotSize(metrics.bestTrade.getSymbol());
        }

        if (metrics.worstTrade != null && metrics.worstTrade.getRealizedPnl() != null) {
            metrics.worstPnlAmount = metrics.worstTrade.getRealizedPnl() * lotSizeService.getLotSize(metrics.worstTrade.getSymbol());
        }

        return metrics;
    }

    /**
     * Retrieves aggregated daily trading metrics for a specific date.
     * <p>
     * Fetches all closed trades for the given date and computes aggregated metrics.
     *
     * @param date the trading date
     * @return Mono emitting DailyReportMetrics (never empty)
     */
    public Mono<DailyReportMetrics> getMetricsForDate(LocalDate date) {
        return paperTradeRepository.findByStatusAndTradeDate(TradeStatus.CLOSED, date)
                .collectList()
                .doOnNext(trades -> {
                    log.info("getMetricsForDate fetched {} trades for {}", trades.size(), date);
                })
                .map(this::computeMetrics);
    }

    /**
     * Retrieves today's status summary with three independent counts and P&L metrics.
     * <p>
     * Combines three independent queries:
     * - Trades entered today (via tradeDate, any status)
     * - Trades closed today (via exitTime, any entry date)
     * - All currently open trades (via status, any entry date)
     * <p>
     * Also includes aggregated P&L metrics from closed trades.
     * This is the single source of truth for daily status reporting.
     *
     * @param date the trading date
     * @return Mono emitting DailyStatusSummary with all counts and metrics
     */
    public Mono<DailyStatusSummary> getTodayStatusSummary(LocalDate date) {
        LocalDateTime dateStart = date.atStartOfDay();
        LocalDateTime dateEnd = date.atTime(23, 59, 59);

        // Query 1: Count trades entered on this date (any status)
        Mono<Long> enteredTodayCount = paperTradeRepository.findByTradeDate(date)
                .count()
                .doOnNext(count -> log.debug("getTodayStatusSummary: trades entered on {}: {}", date, count));

        // Query 2: Fetch trades closed on this date (by exitTime, any entry date)
        Mono<List<PaperTrade>> closedTodayTrades = paperTradeRepository
                .findByStatusAndExitTimeBetween(TradeStatus.CLOSED, dateStart, dateEnd)
                .collectList()
                .doOnNext(trades -> log.debug("getTodayStatusSummary: trades closed on {}: {}", date, trades.size()));

        // Query 3: Count all currently open trades (any entry date)
        Mono<Long> openTradesCount = paperTradeRepository.countByStatus(TradeStatus.OPEN)
                .doOnNext(count -> log.debug("getTodayStatusSummary: currently open trades: {}", count));

        // Combine all three queries with metrics computation
        return Mono.zip(enteredTodayCount, closedTodayTrades, openTradesCount)
                .map(tuple -> {
                    long enteredToday = tuple.getT1();
                    List<PaperTrade> closedTodayList = tuple.getT2();
                    long openCount = tuple.getT3();

                    DailyReportMetrics metrics = computeMetrics(closedTodayList);
                    return new DailyStatusSummary(enteredToday, openCount, closedTodayList.size(), metrics);
                })
                .doOnNext(summary -> log.info("getTodayStatusSummary for {}: entered={}, open={}, closed={}, closedMetrics.totalTrades={}",
                        date, summary.enteredToday(), summary.openCount(), summary.closedTodayCount(), summary.metrics().totalTrades));
    }


    /**
     * Generates and sends the daily trading report.
     * <p>
     * Aggregates all trades from today and sends a comprehensive summary via Telegram.
     * Uses getTodayStatusSummary() as the single source of truth for daily counts.
     * <p>
     * Sends "no trades" alert only when all three counts are zero (nothing entered today,
     * nothing currently open, nothing closed today). Otherwise, sends full report with
     * entered/open/closed counts, even if closedTodayCount is zero.
     *
     * @return Mono emitting void
     */
    public Mono<Void> generateDailyReport() {
        log.info("Generating daily trading report");

        LocalDate today = LocalDate.now(ZoneId.of("Asia/Kolkata"));

        return getTodayStatusSummary(today)
                .flatMap(summary -> {
                    if (summary.closedTodayCount() == 0 && summary.enteredToday() == 0 && summary.openCount() == 0) {
                        log.info("No trades entered, open, or closed today");
                        return sendNoTradesAlert();
                    }

                    return sendDailyReportWithCounts(summary.metrics(), summary.enteredToday(), summary.openCount(), summary.closedTodayCount());
                })
                .doOnError(e -> log.error("Error generating daily report: {}", e.getMessage(), e));
    }

    /**
     * Sends the daily report via Telegram with independent trade counts.
     * <p>
     * Reports three independent statistics:
     * - Entered today: trades that entered on this date (any status)
     * - Currently open: all open trades (any entry date)
     * - Closed today: trades that closed on this date (any entry date)
     *
     * @param metrics          aggregated metrics from closed trades
     * @param enteredToday     count of trades entered today
     * @param openCount        count of all currently open trades
     * @param closedTodayCount count of trades closed today
     * @return Mono emitting void
     */
    private Mono<Void> sendDailyReportWithCounts(DailyReportMetrics metrics, long enteredToday, long openCount, int closedTodayCount) {
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Kolkata"));
        String emoji = metrics.totalPnl >= 0 ? "📊" : "📉";

        String message = String.format(
                "%s <b>Daily Report - %s</b>\n\n" +
                        "<b>Trade Activity</b>\n" +
                        "Entered today: <b>%d</b> | Currently open: <b>%d</b> | Closed today: <b>%d</b>\n\n" +
                        "<b>Closed Trades Summary</b>\n" +
                        "Total: <b>%d</b>\n" +
                        "Wins: <b>%d</b> | Losses: <b>%d</b>\n" +
                        "Win Rate: <b>%.1f%%</b>\n\n" +
                        "<b>P&L (from closed trades)</b>\n" +
                        "Total: <b>%.2f pts</b> (₹ <b>%.2f</b>)\n" +
                        "Best: <b>%.2f pts</b> (₹ <b>%.2f</b>) - %s\n" +
                        "Worst: <b>%.2f pts</b> (₹ <b>%.2f</b>) - %s",
                emoji,
                today,
                enteredToday,
                openCount,
                closedTodayCount,
                metrics.totalTrades,
                metrics.winningTrades,
                metrics.losingTrades,
                metrics.winRate,
                metrics.totalPnl,
                metrics.totalPnlAmount,
                metrics.bestTrade != null ? metrics.bestTrade.getRealizedPnl() : 0,
                metrics.bestPnlAmount,
                metrics.bestTrade != null ? metrics.bestTrade.getStrategyName() : "N/A",
                metrics.worstTrade != null ? metrics.worstTrade.getRealizedPnl() : 0,
                metrics.worstPnlAmount,
                metrics.worstTrade != null ? metrics.worstTrade.getStrategyName() : "N/A"
        );

        Alert alert = Alert.builder()
                .type(AlertType.SIGNAL_GENERATED)
                .tradingSymbol("DAILY_REPORT")
                .message(message)
                .triggeredAt(LocalDateTime.now(ZoneId.of("Asia/Kolkata")))
                .build();

        return telegramNotificationBot.sendAlert(alert)
                .doOnSuccess(v -> log.info("Daily report sent successfully"))
                .doOnError(e -> log.error("Error sending daily report: {}", e.getMessage(), e));
    }

    /**
     * Sends the daily report via Telegram.
     *
     * @param metrics aggregated daily trading metrics
     * @return Mono emitting void
     */
    private Mono<Void> sendDailyReport(DailyReportMetrics metrics) {
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Kolkata"));
        String emoji = metrics.totalPnl >= 0 ? "📊" : "📉";

        String message = String.format(
                "%s <b>Daily Report - %s</b>\n\n" +
                        "<b>Summary</b>\n" +
                        "Trades: <b>%d</b>\n" +
                        "Wins: <b>%d</b> | Losses: <b>%d</b>\n" +
                        "Win Rate: <b>%.1f%%</b>\n\n" +
                        "<b>P&L</b>\n" +
                        "Total: <b>%.2f pts</b> (₹ <b>%.2f</b>)\n" +
                        "Best: <b>%.2f pts</b> (₹ <b>%.2f</b>) - %s\n" +
                        "Worst: <b>%.2f pts</b> (₹ <b>%.2f</b>) - %s",
                emoji,
                today,
                metrics.totalTrades,
                metrics.winningTrades,
                metrics.losingTrades,
                metrics.winRate,
                metrics.totalPnl,
                metrics.totalPnlAmount,
                metrics.bestTrade != null ? metrics.bestTrade.getRealizedPnl() : 0,
                metrics.bestPnlAmount,
                metrics.bestTrade != null ? metrics.bestTrade.getStrategyName() : "N/A",
                metrics.worstTrade != null ? metrics.worstTrade.getRealizedPnl() : 0,
                metrics.worstPnlAmount,
                metrics.worstTrade != null ? metrics.worstTrade.getStrategyName() : "N/A"
        );

        Alert alert = Alert.builder()
                .type(AlertType.SIGNAL_GENERATED)
                .tradingSymbol("DAILY_REPORT")
                .message(message)
                .triggeredAt(LocalDateTime.now(ZoneId.of("Asia/Kolkata")))
                .build();

        return telegramNotificationBot.sendAlert(alert)
                .doOnSuccess(v -> log.info("Daily report sent successfully"))
                .doOnError(e -> log.error("Error sending daily report: {}", e.getMessage(), e));
    }

    /**
     * Sends an alert when no trades were executed today.
     *
     * @return Mono emitting void
     */
    private Mono<Void> sendNoTradesAlert() {
        String message = String.format(
                "📊 <b>Daily Report - %s</b>\n\n" +
                        "No trades closed today.\n" +
                        "Market conditions were not favorable for trading.",
                LocalDate.now(ZoneId.of("Asia/Kolkata"))
        );

        Alert alert = Alert.builder()
                .type(AlertType.SIGNAL_GENERATED)
                .tradingSymbol("DAILY_REPORT")
                .message(message)
                .triggeredAt(LocalDateTime.now(ZoneId.of("Asia/Kolkata")))
                .build();

        return telegramNotificationBot.sendAlert(alert)
                .doOnSuccess(v -> log.info("No trades alert sent"))
                .doOnError(e -> log.error("Error sending no trades alert: {}", e.getMessage(), e));
    }

    /**
     * Public record bundling daily status summary with P&L metrics.
     * <p>
     * Combines three independent daily counts with closed-trade P&L metrics:
     * - enteredToday: trades that entered on this date (any status)
     * - openCount: all currently open trades (any entry date)
     * - closedTodayCount: trades that closed on this date (any entry date)
     * - metrics: aggregated P&L from closed trades
     */
    public record DailyStatusSummary(
            long enteredToday,
            long openCount,
            int closedTodayCount,
            DailyReportMetrics metrics
    ) {
    }

    /**
     * Package-private nested class holding aggregated daily trading metrics.
     * <p>
     * Contains all calculated metrics for a single trading day:
     * - Trade counts (total, winning, losing)
     * - P&L values (total, best, worst) in both points and rupees
     * - Win rate percentage
     * - Best and worst trade references
     */
    static class DailyReportMetrics {
        public int totalTrades;
        public long winningTrades;
        public long losingTrades;
        public double totalPnl;
        public PaperTrade bestTrade;
        public PaperTrade worstTrade;
        public double winRate;
        public double totalPnlAmount;
        public double bestPnlAmount;
        public double worstPnlAmount;

        DailyReportMetrics() {
            this.totalTrades = 0;
            this.winningTrades = 0;
            this.losingTrades = 0;
            this.totalPnl = 0;
            this.bestTrade = null;
            this.worstTrade = null;
            this.winRate = 0;
            this.totalPnlAmount = 0;
            this.bestPnlAmount = 0;
            this.worstPnlAmount = 0;
        }
    }

}
