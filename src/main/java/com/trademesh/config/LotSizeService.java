package com.trademesh.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;

/**
 * Centralized service for managing lot sizes per trading symbol.
 * Provides a single source of truth for lot size configuration.
 * <p>
 * Lot sizes are fetched from trading.lot-sizes configuration with fallback to 1.
 * This ensures all components use consistent lot size values.
 * <p>
 * Also provides capital-based position sizing via getPositionSize().
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LotSizeService {

    @Value("#{${trading.lot-sizes:{NIFTY50: 65, NIFTYBANK: 30}}}")
    private Map<String, Integer> lotSizes;

    @Value("${trading.account-capital:100000}")
    private double accountCapital;

    @Value("${trading.risk-percent-per-trade:2.0}")
    private double riskPercentPerTrade;

    /**
     * Gets the lot size for a given symbol from configuration.
     * Falls back to 1 if symbol is not configured.
     *
     * @param symbol trading symbol (e.g., "NIFTY50", "NIFTYBANK")
     * @return lot size for the symbol
     */
    public int getLotSize(String symbol) {
        if (symbol == null) {
            log.warn("Symbol is null, returning default lot size 1");
            return 1;
        }
        return lotSizes.getOrDefault(symbol, 1);
    }

    /**
     * Gets the total trading account capital.
     *
     * @return account capital in rupees
     */
    public double getAccountCapital() {
        return accountCapital;
    }

    /**
     * Calculates position size (number of lots) based on account capital and risk per trade.
     * <p>
     * Formula:
     * - riskBudget = accountCapital × (riskPercentPerTrade / 100.0)
     * - computedLots = riskBudget / maxLossPerLot (rounded DOWN)
     * - return max(1, computedLots) — never zero
     * <p>
     * This method is used by spread strategies (Iron Condor, Bull Put, Bear Call)
     * to compute capital-based position sizing. The result is capped by cfg.getMaxLots()
     * in the strategy itself.
     *
     * @param maxLossPerLot maximum loss per single lot (in rupees)
     * @return number of lots to trade, minimum 1
     */
    public int getPositionSize(BigDecimal maxLossPerLot) {
        // Guard against zero or negative maxLossPerLot
        if (maxLossPerLot == null || maxLossPerLot.signum() <= 0) {
            log.warn("maxLossPerLot is null or non-positive, returning 1 lot");
            return 1;
        }

        // Calculate risk budget
        BigDecimal riskBudget = BigDecimal.valueOf(accountCapital)
                .multiply(BigDecimal.valueOf(riskPercentPerTrade / 100.0));

        // Compute lots: riskBudget / maxLossPerLot, rounded DOWN
        int computedLots = riskBudget.divide(maxLossPerLot, 0, RoundingMode.DOWN).intValue();

        // Never return zero — minimum 1 lot
        int result = Math.max(1, computedLots);

        log.debug("Position sizing: accountCapital={}, riskPercent={}, maxLossPerLot={}, computedLots={}, result={}",
                accountCapital, riskPercentPerTrade, maxLossPerLot, computedLots, result);

        return result;
    }

    /**
     * Calculates position size with VIX-based scaling on top of capital-based sizing.
     * <p>
     * Formula:
     * - baseSize = getPositionSize(maxLossPerLot) [capital-based]
     * - multiplier = getVixSizeMultiplier(currentVix)
     * - scaledSize = floor(baseSize × multiplier)
     * - return max(1, scaledSize) — never zero even after scaling
     * <p>
     * VIX scaling tiers:
     * - VIX > 18.0: multiplier = 0.5 (half size)
     * - VIX ≤ 18.0: multiplier = 1.0 (full size, covers both 12-18 and <12)
     *
     * @param maxLossPerLot maximum loss per single lot (in rupees)
     * @param currentVix    current VIX level
     * @return number of lots to trade with VIX scaling applied, minimum 1
     */
    public int getPositionSize(BigDecimal maxLossPerLot, double currentVix) {
        int baseSize = getPositionSize(maxLossPerLot);
        double multiplier = getVixSizeMultiplier(currentVix);
        int scaledSize = (int) Math.floor(baseSize * multiplier);
        int result = Math.max(1, scaledSize);

        log.debug("VIX-scaled position sizing: baseSize={}, currentVix={}, multiplier={}, scaledSize={}, result={}",
                baseSize, currentVix, multiplier, scaledSize, result);

        return result;
    }

    /**
     * Determines VIX-based position size multiplier.
     * <p>
     * Tiers:
     * - VIX > 18.0: return 0.5 (reduce to half size)
     * - VIX ≤ 18.0: return 1.0 (full size)
     *
     * @param currentVix current VIX level
     * @return multiplier to apply to base position size
     */
    private double getVixSizeMultiplier(double currentVix) {
        if (currentVix > 18.0) {
            return 0.5;
        }
        return 1.0; // covers both 12-18 and <12 per README's spec
    }

    /**
     * Calculates position size with both VIX-based and confidence-based scaling.
     * <p>
     * Formula:
     * - vixScaledSize = getPositionSize(maxLossPerLot, currentVix) [VIX-scaled]
     * - confidenceMultiplier = getConfidenceSizeMultiplier(confidenceScore, minScore)
     * - scaledSize = floor(vixScaledSize × confidenceMultiplier)
     * - return max(1, scaledSize) — never zero even after scaling
     * <p>
     * Confidence scaling adapts to configured min-score per strategy:
     * - score = minScore: multiplier = 0.5 (half size)
     * - score = 100: multiplier = 1.0 (full size)
     * - score between minScore and 100: linear interpolation
     *
     * @param maxLossPerLot   maximum loss per single lot (in rupees)
     * @param currentVix      current VIX level
     * @param confidenceScore confidence score (0-100)
     * @param minScore        minimum confidence score threshold for this strategy
     * @return number of lots to trade with both VIX and confidence scaling applied, minimum 1
     */
    public int getPositionSize(BigDecimal maxLossPerLot, double currentVix, int confidenceScore, int minScore) {
        int vixScaledSize = getPositionSize(maxLossPerLot, currentVix);
        double confidenceMultiplier = getConfidenceSizeMultiplier(confidenceScore, minScore);
        int scaledSize = (int) Math.floor(vixScaledSize * confidenceMultiplier);
        int result = Math.max(1, scaledSize);

        log.debug("Confidence-scaled position sizing: vixScaledSize={}, confidenceScore={}, minScore={}, multiplier={}, scaledSize={}, result={}",
                vixScaledSize, confidenceScore, minScore, confidenceMultiplier, scaledSize, result);

        return result;
    }

    /**
     * Determines confidence-based position size multiplier.
     * <p>
     * Formula: multiplier = clamp(0.5 + (score - minScore) / (2.0 * (100 - minScore)), 0.5, 1.0)
     * <p>
     * Scaling:
     * - score = minScore: multiplier = 0.5 (half size)
     * - score = 100: multiplier = 1.0 (full size)
     * - score between minScore and 100: linear interpolation
     * <p>
     * Guard: if minScore >= 100, returns 1.0 to avoid division by zero.
     *
     * @param score    confidence score (0-100)
     * @param minScore minimum confidence score threshold
     * @return multiplier to apply to VIX-scaled position size
     */
    private double getConfidenceSizeMultiplier(int score, int minScore) {
        if (minScore >= 100) {
            return 1.0; // guard against division by zero if min-score is misconfigured at/above 100
        }
        double raw = 0.5 + (score - minScore) / (2.0 * (100 - minScore));
        return Math.max(0.5, Math.min(1.0, raw));
    }
}
