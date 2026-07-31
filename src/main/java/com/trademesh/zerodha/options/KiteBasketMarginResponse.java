package com.trademesh.zerodha.options;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * Represents the Kite Connect POST /margins/basket API response.
 * Contains margin requirements for a basket of orders.
 */
@Data
public class KiteBasketMarginResponse {

    private String status;
    private MarginData data;

    /**
     * Margin data containing initial, final, and per-order margin blocks.
     */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class MarginData {

        @JsonProperty("initial")
        private MarginBlock initial;

        @JsonProperty("final")
        private MarginBlock finalMargin;

        @JsonProperty("orders")
        private List<MarginBlock> orders;

        @JsonProperty("charges")
        private Map<String, Object> charges;
    }

    /**
     * Margin block containing margin requirements and components.
     */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class MarginBlock {

        @JsonProperty("total")
        private double total;

        @JsonProperty("span")
        private Double span;

        @JsonProperty("exposure")
        private Double exposure;

        @JsonProperty("option_premium")
        private Double optionPremium;

        @JsonProperty("additional")
        private Double additional;

        @JsonProperty("bo_buildup")
        private Double boBuildup;

        @JsonProperty("cash")
        private Double cash;

        @JsonProperty("var")
        private Double var;

        @JsonProperty("elm")
        private Double elm;

        @JsonProperty("notional_cash")
        private Double notionalCash;

        @JsonProperty("notional_product")
        private String notionalProduct;
    }
}
