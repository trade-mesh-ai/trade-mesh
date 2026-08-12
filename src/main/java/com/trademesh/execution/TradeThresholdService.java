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
     * Reads persisted, real exit-decision fields (stopDistancePoints, targetThreshold)
     * computed at entry time. Falls back to naive formula only if fields are null
     * (for older/edge-case trades from strategies that don't populate these fields).
     * <p>
     * Returns a record containing:
     * - lots: number of lots from the first trade leg
     * - totalEntryCredit: entryCredit × lots (in points)
     * - stopLossThreshold: stop-loss threshold in points (lots-scaled)
     * - stopLossThresholdAmount: stop-loss threshold in rupees
     * - targetThreshold: target profit threshold in points (lots-scaled)
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

        // Unified logic for ALL strategies (IRON_CONDOR, BULL_PUT_SPREAD, BEAR_CALL_SPREAD, EXPIRY_DAY_STRANGLE)
        // Read persisted exit-decision fields, with defensive null-fallback
        String strategyName = trade.getStrategyName();

        if ("IRON_CONDOR".equals(strategyName) || "BULL_PUT_SPREAD".equals(strategyName) || 
            "BEAR_CALL_SPREAD".equals(strategyName) || "EXPIRY_DAY_STRANGLE".equals(strategyName)) {
            
            StrategyProperties cfg = strategiesConfig.getStrategy(strategyName);
            
            // Compute stop-loss threshold (in points, lots-scaled)
            double stopLossThreshold;
            if (trade.getStopDistancePoints() != null) {
                // Use persisted field: -(stopDistancePoints × lots)
                stopLossThreshold = -(trade.getStopDistancePoints().doubleValue() * lots);
            } else {
                // Fallback to naive formula for older/edge-case trades
                double scaledEntryCredit = trade.getEntryCredit().doubleValue() * lots;
                stopLossThreshold = -(scaledEntryCredit * cfg.getStopLossPercent() / 100.0);
            }
            
            // Compute target threshold (in points, lots-scaled)
            double targetThreshold;
            if (trade.getTargetThreshold() != null) {
                // Use persisted field: targetThreshold × lots
                targetThreshold = trade.getTargetThreshold().doubleValue() * lots;
            } else {
                // Fallback to naive formula for older/edge-case trades
                double scaledEntryCredit = trade.getEntryCredit().doubleValue() * lots;
                targetThreshold = scaledEntryCredit * cfg.getTargetProfitPercent() / 100.0;
            }

            return new TradeThresholds(
                    lots,
                    Math.round(totalEntryCredit * 100.0) / 100.0,
                    Math.round(stopLossThreshold * 100.0) / 100.0,
                    Math.round(stopLossThreshold * lotSize * 100.0) / 100.0,
                    Math.round(targetThreshold * 100.0) / 100.0,
                    Math.round(targetThreshold * lotSize * 100.0) / 100.0
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
