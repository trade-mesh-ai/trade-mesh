package com.trademesh.zerodha.options;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.HashMap;
import java.util.Map;

/**
 * Represents the Kite Connect /quote API response for multiple instruments.
 * Contains quote data (price, OI, IV, etc.) for each requested instrument.
 */
@Data
public class KiteQuotesResponse {

    private String status;
    private Map<String, QuoteData> data = new HashMap<>();

    /**
     * Quote data for a single instrument.
     */
    @Data
    public static class QuoteData {

        @JsonProperty("instrument_token")
        private long instrumentToken;

        @JsonProperty("tradingsymbol")
        private String tradingSymbol;

        @JsonProperty("last_price")
        private double lastPrice;

        @JsonProperty("net_change")
        private Double netChange;

        @JsonProperty("timestamp")
        private String timestamp;

        @JsonProperty("last_quantity")
        private long lastQuantity;

        @JsonProperty("last_trade_time")
        private long lastTradeTime;

        @JsonProperty("average_price")
        private double averagePrice;

        @JsonProperty("volume")
        private long volume;

        @JsonProperty("total_buy_quantity")
        private long totalBuyQuantity;

        @JsonProperty("total_sell_quantity")
        private long totalSellQuantity;

        @JsonProperty("ohlc")
        private OHLC ohlc;

        @JsonProperty("open_interest")
        private long openInterest;

        @JsonProperty("oi_day_high")
        private long oiDayHigh;

        @JsonProperty("oi_day_low")
        private long oiDayLow;

        @JsonProperty("bid_quantity")
        private long bidQuantity;

        @JsonProperty("ask_quantity")
        private long askQuantity;

        @JsonProperty("bid")
        private double bid;

        @JsonProperty("ask")
        private double ask;

        @JsonProperty("bid_size")
        private long bidSize;

        @JsonProperty("ask_size")
        private long askSize;

        @JsonProperty("depth")
        private Depth depth;

        @JsonProperty("iv")
        private double impliedVolatility;

        @JsonProperty("greeks")
        private Greeks greeks;

        /**
         * Gets the change in open interest from the previous day.
         * This is calculated as: current OI - previous close OI
         */
        @JsonProperty("oi_day_change")
        private long oiDayChange;

        /**
         * OHLC data for the instrument.
         */
        @Data
        public static class OHLC {
            @JsonProperty("open")
            private double open;

            @JsonProperty("high")
            private double high;

            @JsonProperty("low")
            private double low;

            @JsonProperty("close")
            private double close;
        }

        /**
         * Market depth (bid/ask levels).
         */
        @Data
        public static class Depth {
            @JsonProperty("buy")
            private java.util.List<DepthLevel> buy;

            @JsonProperty("sell")
            private java.util.List<DepthLevel> sell;

            @Data
            public static class DepthLevel {
                @JsonProperty("quantity")
                private long quantity;

                @JsonProperty("price")
                private double price;

                @JsonProperty("orders")
                private int orders;
            }
        }

        /**
         * Greeks for option contracts (delta, gamma, theta, vega, rho).
         */
        @Data
        public static class Greeks {
            @JsonProperty("vega")
            private double vega;

            @JsonProperty("theta")
            private double theta;

            @JsonProperty("gamma")
            private double gamma;

            @JsonProperty("rho")
            private double rho;

            @JsonProperty("delta")
            private double delta;
        }
    }
}
