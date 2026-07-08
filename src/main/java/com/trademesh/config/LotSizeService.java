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
}
