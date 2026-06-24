package com.trademesh.trading;

import com.trademesh.alert.Alert;
import com.trademesh.alert.TelegramNotificationBot;
import com.trademesh.alert.AlertType;
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

        // Fetch all closed trades for today
        return paperTradeRepository.findByStatus(TradeStatus.CLOSED)
                .filter(trade -> trade.getTradeDate().equals(today))
                .collectList()
                .flatMap(trades -> {
                    if (trades.isEmpty()) {
                        log.info("No trades executed today");
                        return sendNoTradesAlert();
                    }

                    // Calculate metrics
                    int totalTrades = trades.size();
                    long winningTrades = trades.stream()
                            .filter(t -> t.getRealizedPnl() != null && t.getRealizedPnl() > 0)
                            .count();
                    long losingTrades = trades.stream()
                            .filter(t -> t.getRealizedPnl() != null && t.getRealizedPnl() < 0)
                            .count();

                    double totalPnl = trades.stream()
                            .mapToDouble(t -> t.getRealizedPnl() != null ? t.getRealizedPnl() : 0)
                            .sum();

                    // Calculate best and worst trades
                    PaperTrade bestTrade = trades.stream()
                            .max((t1, t2) -> Double.compare(
                                    t1.getRealizedPnl() != null ? t1.getRealizedPnl() : 0,
                                    t2.getRealizedPnl() != null ? t2.getRealizedPnl() : 0
                            ))
                            .orElse(null);

                    PaperTrade worstTrade = trades.stream()
                            .min((t1, t2) -> Double.compare(
                                    t1.getRealizedPnl() != null ? t1.getRealizedPnl() : 0,
                                    t2.getRealizedPnl() != null ? t2.getRealizedPnl() : 0
                            ))
                            .orElse(null);

                    double winRate = totalTrades > 0 ? (winningTrades * 100.0) / totalTrades : 0;

                    // Send report
                    return sendDailyReport(today, totalTrades, winningTrades, losingTrades, totalPnl, bestTrade, worstTrade, winRate);
                })
                .doOnError(e -> log.error("Error generating daily report: {}", e.getMessage(), e));
    }

    /**
     * Sends the daily report via Telegram.
     *
     * @param date          the trading date
     * @param totalTrades   total number of trades
     * @param winningTrades number of winning trades
     * @param losingTrades  number of losing trades
     * @param totalPnl      total P&L for the day
     * @param bestTrade     the best performing trade
     * @param worstTrade    the worst performing trade
     * @param winRate       win rate percentage
     * @return Mono emitting void
     */
    private Mono<Void> sendDailyReport(LocalDate date, int totalTrades, long winningTrades, long losingTrades,
                                       double totalPnl, PaperTrade bestTrade, PaperTrade worstTrade, double winRate) {

        // Calculate rupee amount (assuming lot sizes)
        double totalPnlAmount = calculatePnlAmount(totalPnl);
        double bestPnlAmount = bestTrade != null && bestTrade.getRealizedPnl() != null
                ? calculatePnlAmount(bestTrade.getRealizedPnl())
                : 0;
        double worstPnlAmount = worstTrade != null && worstTrade.getRealizedPnl() != null
                ? calculatePnlAmount(worstTrade.getRealizedPnl())
                : 0;

        String emoji = totalPnl >= 0 ? "📊" : "📉";

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
                date,
                totalTrades,
                winningTrades,
                losingTrades,
                winRate,
                totalPnl,
                totalPnlAmount,
                bestTrade != null ? bestTrade.getRealizedPnl() : 0,
                bestPnlAmount,
                bestTrade != null ? bestTrade.getStrategyName() : "N/A",
                worstTrade != null ? worstTrade.getRealizedPnl() : 0,
                worstPnlAmount,
                worstTrade != null ? worstTrade.getStrategyName() : "N/A"
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
     * Calculates the rupee amount from points based on lot sizes.
     * <p>
     * Assumes:
     * - NIFTY: 75 contracts per lot
     * - BANKNIFTY: 30 contracts per lot
     * <p>
     * For simplicity, uses average lot size of 50 contracts.
     *
     * @param points the P&L in points
     * @return P&L in rupees
     */
    private double calculatePnlAmount(double points) {
        // Average lot size: 50 contracts
        // Each point = 1 rupee per contract
        return points * 50;
    }
}
