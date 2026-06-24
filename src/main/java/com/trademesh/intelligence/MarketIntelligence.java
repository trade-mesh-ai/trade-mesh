package com.trademesh.intelligence;

import com.trademesh.intelligence.maxpain.MaxPainResult;
import com.trademesh.intelligence.oi.OIAnalysis;
import com.trademesh.intelligence.vix.VixData;
import com.trademesh.zerodha.options.OptionChain;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Comprehensive market intelligence aggregating all analysis components.
 * <p>
 * Combines VIX data, OI analysis, max pain calculation, and option chain data
 * into a single unified intelligence object with market condition classification
 * and trading safety assessment.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MarketIntelligence {

    /**
     * Trading symbol (e.g., "NIFTY50", "NIFTYBANK").
     */
    private String symbol;

    /**
     * Current spot price of the underlying.
     */
    private Double spotPrice;

    /**
     * Current VIX data.
     */
    private VixData vixData;

    /**
     * OI analysis including walls and market bias.
     */
    private OIAnalysis oiAnalysis;

    /**
     * Max pain calculation result.
     */
    private MaxPainResult maxPainResult;

    /**
     * Complete option chain data.
     */
    private OptionChain optionChain;

    /**
     * Classified market condition.
     */
    private MarketCondition marketCondition;

    /**
     * Whether it is safe to trade based on current conditions.
     */
    private Boolean isSafeToTrade;

    /**
     * Timestamp when this intelligence was generated.
     */
    private Instant generatedAt;

    /**
     * Generates a one-line summary of the market intelligence.
     * <p>
     * Format: "CONDITION | VIX:value | Support:strike | Resistance:strike | MaxPain:strike"
     * Example: "SIDEWAYS | VIX:14.2 | Support:21500 | Resistance:22500 | MaxPain:22000"
     *
     * @return formatted summary string
     */
    public String summary() {
        StringBuilder sb = new StringBuilder();

        // Market condition
        sb.append(marketCondition != null ? marketCondition.name() : "UNKNOWN");

        // VIX value
        if (vixData != null && vixData.getCurrentVix() != null) {
            sb.append(" | VIX:").append(String.format("%.1f", vixData.getCurrentVix()));
        }

        // Support (max put OI strike)
        if (oiAnalysis != null && oiAnalysis.getMaxPutOIStrike() != null) {
            sb.append(" | Support:").append(oiAnalysis.getMaxPutOIStrike().intValue());
        }

        // Resistance (max call OI strike)
        if (oiAnalysis != null && oiAnalysis.getMaxCallOIStrike() != null) {
            sb.append(" | Resistance:").append(oiAnalysis.getMaxCallOIStrike().intValue());
        }

        // Max pain strike
        if (maxPainResult != null && maxPainResult.getMaxPainStrike() != null) {
            sb.append(" | MaxPain:").append(maxPainResult.getMaxPainStrike().intValue());
        }

        return sb.toString();
    }
}
