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
    DailyReportMetrics computeMetrics(List<PaperTrade> trades) {
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
     * Generates and sends the daily trading report.
     * <p>
     * Aggregates all trades from today and sends a comprehensive summary via Telegram.
     *
     * @return Mono emitting void
     */
    public Mono<Void> generateDailyReport() {
        log.info("Generating daily trading report");

        LocalDate today = LocalDate.now(ZoneId.of("Asia/Kolkata"));

        return getMetricsForDate(today)
                .flatMap(metrics -> {
                    if (metrics.totalTrades == 0) {
                        log.info("No trades executed today");
                        return sendNoTradesAlert();
                    }

                    return sendDailyReport(metrics);
                })
                .doOnError(e -> log.error("Error generating daily report: {}", e.getMessage(), e));
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
                        "No trades executed today.\n" +
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
