package com.trademesh.zerodha.options;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.time.LocalDate;
import java.util.List;

/**
 * Represents the Kite Connect /instruments API response.
 * Contains a list of all available instruments (stocks, options, futures, etc.).
 */
@Data
public class KiteInstrumentsResponse {

    private List<Instrument> data;

    /**
     * Represents a single instrument from the Kite API.
     */
    @Data
    public static class Instrument {

        @JsonProperty("instrument_token")
        private long instrumentToken;

        @JsonProperty("exchange_token")
        private long exchangeToken;

        @JsonProperty("tradingsymbol")
        private String tradingSymbol;

        @JsonProperty("name")
        private String name;

        @JsonProperty("last_price")
        private double lastPrice;

        @JsonProperty("expiry")
        private LocalDate expiryDate;

        @JsonProperty("strike")
        private double strikePrice;

        @JsonProperty("lot_size")
        private int lotSize;

        @JsonProperty("instrument_type")
        private String instrumentType; // CE, PE, FUT, etc.

        @JsonProperty("segment")
        private String segment; // NFO, NSE, BSE, etc.

        @JsonProperty("exchange")
        private String exchange;
    }
}
