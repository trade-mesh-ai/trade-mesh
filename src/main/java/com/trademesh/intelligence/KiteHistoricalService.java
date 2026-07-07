package com.trademesh.intelligence;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.trademesh.config.ApplicationConstants;
import com.trademesh.domain.NiftyDailyPrice;
import com.trademesh.zerodha.auth.ZerodhaAuthService;
import com.trademesh.zerodha.auth.ZerodhaConfig;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Service for fetching NIFTY50 historical OHLC data from Kite Connect API.
 * <p>
 * Integrates with Zerodha's Kite Connect historical candles endpoint to retrieve
 * daily OHLC data for NIFTY50 index.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KiteHistoricalService {

    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private final ZerodhaAuthService authService;
    private final ZerodhaConfig zerodhaConfig;
    private final WebClient.Builder webClientBuilder;

    /**
     * Fetches historical OHLC data for NIFTY50 from Kite Connect API.
     * <p>
     * Calls the Kite historical candles endpoint:
     * GET /instruments/historical/{instrument_token}/day
     * <p>
     * Returns a Flux of NiftyDailyPrice entities with isNew=true.
     *
     * @param from the start date (inclusive)
     * @param to   the end date (inclusive)
     * @return Flux of daily price records
     */
    public Flux<NiftyDailyPrice> fetchHistoricalOHLC(LocalDate from, LocalDate to) {
        log.info("Fetching historical OHLC data for NIFTY50 from {} to {}", from, to);

        return authService.getAccessToken()
                .switchIfEmpty(Mono.error(new IllegalStateException("No access token available")))
                .flatMapMany(accessToken -> {
                    WebClient client = webClientBuilder
                            .baseUrl(zerodhaConfig.getBaseUrl())
                            .defaultHeader("Authorization", "token " + zerodhaConfig.getApiKey() + ":" + accessToken)
                            .build();

                    String fromStr = from.format(DATE_FORMATTER);
                    String toStr = to.format(DATE_FORMATTER);

                    return client.get()
                            .uri(uriBuilder -> uriBuilder
                                    .path("/instruments/historical/{instrument_token}/day")
                                    .queryParam("from", fromStr)
                                    .queryParam("to", toStr)
                                    .queryParam("continuous", 0)
                                    .queryParam("oi", 0)
                                    .build(ApplicationConstants.TOKEN_NIFTY_50))
                            .retrieve()
                            .bodyToMono(KiteHistoricalResponse.class)
                            .doOnSuccess(response -> log.info("Received {} candles from Kite API",
                                    response.getData() != null && response.getData().getCandles() != null
                                            ? response.getData().getCandles().size()
                                            : 0))
                            .doOnError(e -> log.error("Error fetching historical data from Kite API: {}", e.getMessage(), e))
                            .flatMapMany(response -> {
                                if (response.getData() == null || response.getData().getCandles() == null) {
                                    log.warn("No candle data in Kite response");
                                    return Flux.empty();
                                }
                                return Flux.fromIterable(response.getData().getCandles())
                                        .map(this::parseCandle);
                            });
                });
    }

    /**
     * Parses a single candle array from Kite API response.
     * <p>
     * Candle format: [timestamp, open, high, low, close, volume, oi]
     *
     * @param candle the candle array
     * @return NiftyDailyPrice entity with isNew=true
     */
    private NiftyDailyPrice parseCandle(List<?> candle) {
        if (candle.size() < 6) {
            log.warn("Invalid candle format: expected at least 6 elements, got {}", candle.size());
            return null;
        }

        try {
            // Index 0: timestamp (String, e.g., "2024-01-01 15:30:00")
            String timestamp = (String) candle.get(0);
            LocalDate tradeDate = LocalDate.parse(timestamp.substring(0, 10), DATE_FORMATTER);

            // Index 1-5: OHLCV
            BigDecimal open = new BigDecimal(candle.get(1).toString());
            BigDecimal high = new BigDecimal(candle.get(2).toString());
            BigDecimal low = new BigDecimal(candle.get(3).toString());
            BigDecimal close = new BigDecimal(candle.get(4).toString());
            Long volume = ((Number) candle.get(5)).longValue();

            NiftyDailyPrice price = NiftyDailyPrice.createNew();
            price.setTradeDate(tradeDate);
            price.setOpen(open);
            price.setHigh(high);
            price.setLow(low);
            price.setClose(close);
            price.setVolume(volume);
            price.setCreatedAt(LocalDateTime.now());
            return price;
        } catch (Exception e) {
            log.error("Error parsing candle: {}", e.getMessage(), e);
            return null;
        }
    }

    // ─── Inner DTOs for Kite API Response ──────────────────────────────────────

    /**
     * Represents the Kite Connect historical candles API response.
     */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    static class KiteHistoricalResponse {
        private String status;
        private HistoricalData data;
    }

    /**
     * Represents the data section of the Kite historical response.
     */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    static class HistoricalData {
        @JsonProperty("candles")
        private List<List<?>> candles;
    }
}
