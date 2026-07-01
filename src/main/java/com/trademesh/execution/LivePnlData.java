package com.trademesh.execution;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * DTO for live P&L data calculated at read time from Redis.
 * Used by REST endpoints to return fresh P&L values without writing to DB.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LivePnlData {

    /**
     * Live unrealized P&L calculated from current Redis option chain data.
     * Calculated as: entryCredit - currentValue (based on live LTPs).
     */
    private Double liveUnrealizedPnl;

    /**
     * Flag indicating whether liveUnrealizedPnl is fresh from Redis.
     * true = calculated from current Redis data
     * false = Redis had no data, using last stored DB value (may be up to 60 seconds old)
     */
    private Boolean isPnlLive;
}
