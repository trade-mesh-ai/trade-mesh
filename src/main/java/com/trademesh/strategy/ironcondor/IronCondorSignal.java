package com.trademesh.strategy.ironcondor;

import com.trademesh.strategy.TradeLeg;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

/**
 * Represents a complete Iron Condor trading signal.
 * Contains strike selection, LTPs, P&L matrix, and entry context.
 * This is a plain DTO (not a DB entity) used for strategy evaluation.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class IronCondorSignal {

    // ─── Strikes ──────────────────────────────────────────────────────────────
    /**
     * Short call strike price.
     */
    private BigDecimal shortCallStrike;

    /**
     * Short put strike price.
     */
    private BigDecimal shortPutStrike;

    /**
     * Long call strike price (protective).
     */
    private BigDecimal longCallStrike;

    /**
     * Long put strike price (protective).
     */
    private BigDecimal longPutStrike;

    /**
     * Expiry date of the option chain.
     */
    private String expiryDate;

    // ─── LTPs (Last Traded Prices) ────────────────────────────────────────────
    /**
     * Last traded price of short call.
     */
    private BigDecimal shortCallLtp;

    /**
     * Last traded price of short put.
     */
    private BigDecimal shortPutLtp;

    /**
     * Last traded price of long call.
     */
    private BigDecimal longCallLtp;

    /**
     * Last traded price of long put.
     */
    private BigDecimal longPutLtp;

    // ─── Credit ───────────────────────────────────────────────────────────────
    /**
     * Net credit received from the entire position.
     * = (shortCallLtp + shortPutLtp) - (longCallLtp + longPutLtp)
     */
    private BigDecimal netCredit;

    // ─── P&L Matrix ───────────────────────────────────────────────────────────
    /**
     * Maximum profit if position expires worthless.
     * = netCredit × lotSize × lots
     */
    private BigDecimal maxProfit;

    /**
     * Maximum loss if position moves against us.
     * = (wingWidth - netCredit) × lotSize × lots
     */
    private BigDecimal maxLoss;

    /**
     * Upper breakeven point.
     * = shortCallStrike + netCredit
     */
    private BigDecimal upperBreakeven;

    /**
     * Lower breakeven point.
     * = shortPutStrike - netCredit
     */
    private BigDecimal lowerBreakeven;

    /**
     * Risk/reward ratio.
     * = maxProfit / maxLoss
     */
    private BigDecimal riskRewardRatio;

    // ─── Entry Context ────────────────────────────────────────────────────────
    /**
     * Confidence score (0-100) based on market metrics.
     */
    private int confidenceScore;

    /**
     * IV Rank at time of signal generation.
     */
    private BigDecimal ivr;

    /**
     * IV/HV ratio at time of signal generation.
     */
    private BigDecimal ivHvRatio;

    /**
     * ATR as percentage of spot price.
     */
    private BigDecimal atrPct;

    /**
     * Current ATM implied volatility.
     */
    private BigDecimal currentAtmIv;

    /**
     * Days to expiry.
     */
    private int dte;

    // ─── Adjusted Exit Levels ─────────────────────────────────────────────────
    /**
     * Adjusted profit target (in points).
     * Dynamically adjusted based on ATR% volatility.
     */
    private BigDecimal adjustedProfitTarget;

    /**
     * Adjusted stop loss (in points).
     * Dynamically adjusted based on ATR% volatility.
     */
    private BigDecimal adjustedStopLoss;

    // ─── Trade Legs ───────────────────────────────────────────────────────────
    /**
     * List of trade legs (4 legs for iron condor).
     * Order: SELL short call, SELL short put, BUY long call, BUY long put
     */
    private List<TradeLeg> legs;

    // ─── ATR-Adjusted Risk Snapshot Fields ─────────────────────────────────────
    /**
     * Stop loss distance in points (ATR-adjusted).
     * = netCredit × (stopLossPercent / 100) × atrMultiplier
     */
    private BigDecimal stopDistancePoints;

    /**
     * Target profit threshold in points (ATR-adjusted).
     * = netCredit × (targetProfitPercent / 100) × targetMultiplier
     */
    private BigDecimal targetThreshold;

    /**
     * Planned risk per lot in points (ATR-adjusted).
     * = stopDistancePoints × lotSize
     */
    private BigDecimal plannedRiskPerLot;
}
