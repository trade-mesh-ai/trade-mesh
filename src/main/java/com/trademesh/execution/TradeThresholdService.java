package com.trademesh.execution;

import com.trademesh.config.LotSizeService;
import com.trademesh.strategy.config.StrategiesConfig;
import com.trademesh.strategy.config.StrategyProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Service for calculating trade threshold values (stop-loss and target profit).
 * <p>
 * Computes lots-aware P&L thresholds for a given PaperTrade based on its strategy.
 * Supports three strategy types with distinct calculation formulas:
 * - IRON_CONDOR, BULL_PUT_SPREAD, BEAR_CALL_SPREAD: entryCredit-based formula
 * - EXPIRY_DAY_STRANGLE: hardcoded multipliers (×2 for SL, ×0.5 for target)
 * - Unknown strategies: null thresholds
 * <p>
 * All calculations are synchronous, in-memory operations with no I/O.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TradeThresholdService {

    private final LotSizeService lotSizeService;
    private final StrategiesConfig strategiesConfig;

    /**
     * Calculates trade thresholds (stop-loss and target profit) for a given paper trade.
     * <p>
     * Returns a record containing:
     * - lots: number of lots from the first trade leg
     * - totalEntryCredit: entryCredit × lots (in points)
     * - stopLossThreshold: stop-loss threshold in points
     * - stopLossThresholdAmount: stop-loss threshold in rupees
     * - targetThreshold: target profit threshold in points
     * - targetThresholdAmount: target profit threshold in rupees
     * <p>
     * For unknown strategies, all threshold fields are set to null.
     *
     * @param trade the PaperTrade to calculate thresholds for
     * @return TradeThresholds record with all computed values
     */
    public TradeThresholds calculateThresholds(PaperTrade trade) {
        // Extract lot count from first leg
        int lots = 0;
        if (trade.getLegs() != null && !trade.getLegs().isEmpty()) {
            lots = trade.getLegs().get(0).getQuantity();
        }

        // Calculate total entry credit (in points)
        double totalEntryCredit = trade.getEntryCredit().doubleValue() * lots;

        // Get lot size for rupee conversion
        int lotSize = lotSizeService.getLotSize(trade.getSymbol());

        // Branch on strategy name
        String strategyName = trade.getStrategyName();

        if ("IRON_CONDOR".equals(strategyName) || "BULL_PUT_SPREAD".equals(strategyName) || "BEAR_CALL_SPREAD".equals(strategyName)) {
            // IRON_CONDOR, BULL_PUT_SPREAD, BEAR_CALL_SPREAD: entryCredit-based formula
            StrategyProperties cfg = strategiesConfig.getStrategy(strategyName);
            double scaledEntryCredit = trade.getEntryCredit().doubleValue() * lots;
            double stopLossThreshold = -(scaledEntryCredit * cfg.getStopLossPercent() / 100.0);
            double targetThreshold = scaledEntryCredit * cfg.getTargetProfitPercent() / 100.0;

            return new TradeThresholds(
                    lots,
                    Math.round(totalEntryCredit * 100.0) / 100.0,
                    Math.round(stopLossThreshold * 100.0) / 100.0,
                    Math.round(stopLossThreshold * lotSize * 100.0) / 100.0,
                    Math.round(targetThreshold * 100.0) / 100.0,
                    Math.round(targetThreshold * lotSize * 100.0) / 100.0
            );
        } else if ("EXPIRY_DAY_STRANGLE".equals(strategyName)) {
            // Expiry Day Strangle: hardcoded rule (per-lot thresholds, must scale by lots for rupee amount)
            double entryCredit = trade.getEntryCredit().doubleValue();
            double stopLossThreshold = entryCredit * 2.0;
            double targetThreshold = entryCredit * 0.5;

            return new TradeThresholds(
                    lots,
                    Math.round(totalEntryCredit * 100.0) / 100.0,
                    Math.round(stopLossThreshold * 100.0) / 100.0,
                    Math.round(stopLossThreshold * lotSize * lots * 100.0) / 100.0,
                    Math.round(targetThreshold * 100.0) / 100.0,
                    Math.round(targetThreshold * lotSize * lots * 100.0) / 100.0
            );
        } else {
            // Unknown strategy — omit thresholds
            return new TradeThresholds(
                    lots,
                    Math.round(totalEntryCredit * 100.0) / 100.0,
                    null,
                    null,
                    null,
                    null
            );
        }
    }

    /**
     * Record holding calculated trade threshold values.
     * <p>
     * All numeric fields are rounded to 2 decimal places.
     * Threshold fields may be null for unknown strategies.
     *
     * @param lots                      number of lots in the trade
     * @param totalEntryCredit          total entry credit in points (entryCredit × lots)
     * @param stopLossThreshold         stop-loss threshold in points (may be null)
     * @param stopLossThresholdAmount   stop-loss threshold in rupees (may be null)
     * @param targetThreshold           target profit threshold in points (may be null)
     * @param targetThresholdAmount     target profit threshold in rupees (may be null)
     */
    public record TradeThresholds(
            int lots,
            double totalEntryCredit,
            Double stopLossThreshold,
            Double stopLossThresholdAmount,
            Double targetThreshold,
            Double targetThresholdAmount
    ) {}
}
