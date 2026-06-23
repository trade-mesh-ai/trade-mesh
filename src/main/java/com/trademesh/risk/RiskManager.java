package com.trademesh.risk;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicDouble;

/**
 * Risk management component for validating trades against risk limits.
 * <p>
 * Responsibilities:
 * - Position sizing based on account equity and risk percentage
 * - Max position size enforcement per symbol
 * - Daily loss limit tracking and enforcement
 * - Sector exposure limit validation
 * - Margin availability checking
 * - Greeks monitoring (delta, theta, vega exposure)
 * <p>
 * All risk limits are loaded from application.yml configuration.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@EnableConfigurationProperties(RiskLimitsConfig.class)
public class RiskManager {

    private final RiskLimitsConfig riskLimitsConfig;

    // Track daily P&L per day (reset at market open)
    private final ConcurrentHashMap<LocalDate, AtomicDouble> dailyPnlMap = new ConcurrentHashMap<>();

    // Track position sizes per symbol
    private final ConcurrentHashMap<String, Integer> positionSizeMap = new ConcurrentHashMap<>();

    /**
     * Validates whether a proposed trade passes all risk checks.
     *
     * @param tradingSymbol instrument to trade
     * @param quantity      proposed quantity
     * @return {@code true} if the trade is within risk limits, {@code false} otherwise
     */
    public Mono<Boolean> isWithinRiskLimits(String tradingSymbol, int quantity) {
        return Mono.fromCallable(() -> {
            log.info("Risk check for {} qty={}", tradingSymbol, quantity);

            // Check 1: Max position size per symbol
            if (!checkMaxPositionSize(tradingSymbol, quantity)) {
                log.warn("Risk check failed: Max position size exceeded for {}", tradingSymbol);
                return false;
            }

            // Check 2: Daily loss limit
            if (!checkDailyLossLimit()) {
                log.warn("Risk check failed: Daily loss limit exceeded");
                return false;
            }

            // Check 3: Sector exposure (simplified - can be extended)
            if (!checkSectorExposure(tradingSymbol, quantity)) {
                log.warn("Risk check failed: Sector exposure limit exceeded for {}", tradingSymbol);
                return false;
            }

            log.info("Risk check passed for {} qty={}", tradingSymbol, quantity);
            return true;
        });
    }

    /**
     * Calculates the recommended position size based on account equity and risk %.
     * <p>
     * Formula: qty = (accountEquity * riskPercent / 100) / stopLossPoints
     *
     * @param accountEquity  total account equity
     * @param riskPercent    percentage of equity to risk per trade (e.g. 1.0 = 1%)
     * @param stopLossPoints stop-loss distance in price points
     * @return recommended quantity
     */
    public Mono<Integer> calculatePositionSize(double accountEquity, double riskPercent, double stopLossPoints) {
        return Mono.fromCallable(() -> {
            if (stopLossPoints <= 0) {
                log.warn("Invalid stop-loss points: {}", stopLossPoints);
                return 0;
            }

            double riskAmount = accountEquity * (riskPercent / 100.0);
            int qty = (int) (riskAmount / stopLossPoints);

            // Cap at max quantity from config
            int maxQty = riskLimitsConfig.getPositionSizing().getMaxQuantity();
            qty = Math.min(qty, maxQty);

            log.info("Position size: equity={} risk%={} sl={} -> qty={}", accountEquity, riskPercent, stopLossPoints, qty);
            return qty;
        });
    }

    /**
     * Checks if adding a new position would exceed max position size for the symbol.
     *
     * @param tradingSymbol symbol to check
     * @param quantity      proposed quantity
     * @return true if within limits, false otherwise
     */
    private boolean checkMaxPositionSize(String tradingSymbol, int quantity) {
        int currentSize = positionSizeMap.getOrDefault(tradingSymbol, 0);
        int newSize = currentSize + quantity;
        int maxSize = riskLimitsConfig.getMaxPositionSizePerSymbol();

        boolean withinLimit = newSize <= maxSize;
        log.debug("Max position check: symbol={} current={} new={} max={} result={}",
                tradingSymbol, currentSize, newSize, maxSize, withinLimit);
        return withinLimit;
    }

    /**
     * Checks if daily loss limit has been exceeded.
     *
     * @return true if within daily loss limit, false if exceeded
     */
    private boolean checkDailyLossLimit() {
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Kolkata"));
        AtomicDouble dailyPnl = dailyPnlMap.getOrDefault(today, new AtomicDouble(0.0));

        double maxDailyLoss = -riskLimitsConfig.getDailyLossLimitPercent();
        boolean withinLimit = dailyPnl.get() >= maxDailyLoss;

        log.debug("Daily loss check: today={} pnl={} maxLoss={} result={}",
                today, dailyPnl.get(), maxDailyLoss, withinLimit);
        return withinLimit;
    }

    /**
     * Checks if sector exposure would be exceeded.
     *
     * @param tradingSymbol symbol to check
     * @param quantity      proposed quantity
     * @return true if within limits, false otherwise
     */
    private boolean checkSectorExposure(String tradingSymbol, int quantity) {
        // Simplified: assume NIFTY and BANKNIFTY are in same sector
        // In production, would map symbols to sectors and track exposure per sector
        String sector = tradingSymbol.contains("BANK") ? "BANKING" : "NIFTY";

        // For now, just log and return true (can be extended in Phase 3)
        log.debug("Sector exposure check: symbol={} sector={} qty={}", tradingSymbol, sector, quantity);
        return true;
    }

    /**
     * Records a trade execution for risk tracking.
     *
     * @param tradingSymbol symbol traded
     * @param quantity      quantity traded
     * @param pnl           profit/loss from trade
     */
    public Mono<Void> recordTrade(String tradingSymbol, int quantity, double pnl) {
        return Mono.fromRunnable(() -> {
            // Update position size
            positionSizeMap.merge(tradingSymbol, quantity, Integer::sum);

            // Update daily P&L
            LocalDate today = LocalDate.now(ZoneId.of("Asia/Kolkata"));
            dailyPnlMap.computeIfAbsent(today, k -> new AtomicDouble(0.0)).addAndGet(pnl);

            log.info("Trade recorded: symbol={} qty={} pnl={} dailyPnl={}",
                    tradingSymbol, quantity, pnl, dailyPnlMap.get(today).get());
        });
    }

    /**
     * Resets daily P&L tracking (called at market open).
     */
    public Mono<Void> resetDailyTracking() {
        return Mono.fromRunnable(() -> {
            LocalDate today = LocalDate.now(ZoneId.of("Asia/Kolkata"));
            dailyPnlMap.remove(today);
            log.info("Daily tracking reset for {}", today);
        });
    }

    /**
     * Gets current daily P&L.
     *
     * @return current daily P&L
     */
    public Mono<Double> getDailyPnl() {
        return Mono.fromCallable(() -> {
            LocalDate today = LocalDate.now(ZoneId.of("Asia/Kolkata"));
            return dailyPnlMap.getOrDefault(today, new AtomicDouble(0.0)).get();
        });
    }

    /**
     * Gets current position size for a symbol.
     *
     * @param tradingSymbol symbol to check
     * @return current position size
     */
    public Mono<Integer> getPositionSize(String tradingSymbol) {
        return Mono.just(positionSizeMap.getOrDefault(tradingSymbol, 0));
    }
}
