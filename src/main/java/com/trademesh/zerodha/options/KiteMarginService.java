package com.trademesh.zerodha.options;

import com.trademesh.strategy.TradeLeg;
import com.trademesh.strategy.enums.Action;
import com.trademesh.zerodha.auth.ZerodhaConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Service for fetching margin requirements from Zerodha Kite Connect.
 * <p>
 * Provides read-only margin shadow-logging for informational purposes.
 * All errors degrade silently and never affect trade execution.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KiteMarginService {

    private final ZerodhaConfig config;
    private final WebClient.Builder webClientBuilder;

    /**
     * Fetches basket margin requirement from Kite Connect POST /margins/basket endpoint.
     * <p>
     * This is a read-only, informational call for shadow-logging purposes.
     * All errors are logged as warnings and return Mono.empty() — never propagate errors
     * that could affect the caller or trade execution.
     *
     * @param legs the list of trade legs to calculate margin for
     * @param accessToken the Zerodha access token
     * @return Mono emitting the final margin requirement as BigDecimal, or empty on any error
     */
    public Mono<BigDecimal> fetchBasketMargin(List<TradeLeg> legs, String accessToken) {
        if (legs == null || legs.isEmpty()) {
            log.warn("Cannot fetch basket margin: legs list is null or empty");
            return Mono.empty();
        }

        if (accessToken == null || accessToken.isBlank()) {
            log.warn("Cannot fetch basket margin: access token is null or blank");
            return Mono.empty();
        }

        try {
            // Build request body: array of order objects
            List<Map<String, Object>> orders = new ArrayList<>();
            for (TradeLeg leg : legs) {
                Map<String, Object> order = new HashMap<>();
                order.put("exchange", "NFO");
                order.put("tradingsymbol", leg.getInstrument());
                order.put("transaction_type", leg.getAction() == Action.BUY ? "BUY" : "SELL");
                order.put("variety", "regular");
                order.put("product", "NRML");
                order.put("order_type", "MARKET");
                order.put("quantity", leg.getQuantity());
                order.put("price", 0);
                order.put("trigger_price", 0);
                orders.add(order);
            }

            WebClient client = webClientBuilder
                    .baseUrl(config.getBaseUrl())
                    .defaultHeader(HttpHeaders.AUTHORIZATION, "token " + config.getApiKey() + ":" + accessToken)
                    .build();

            return client.post()
                    .uri("/margins/basket")
                    .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                    .bodyValue(orders)
                    .retrieve()
                    .bodyToMono(KiteBasketMarginResponse.class)
                    .timeout(Duration.ofSeconds(10))
                    .flatMap(response -> {
                        if (response == null || response.getData() == null || response.getData().getFinalMargin() == null) {
                            log.warn("Invalid margin response: missing data or finalMargin");
                            return Mono.empty();
                        }
                        return Mono.just(BigDecimal.valueOf(response.getData().getFinalMargin().getTotal()));
                    })
                    .onErrorResume(e -> {
                        log.warn("Failed to fetch basket margin from Kite API: {}", e.getMessage());
                        return Mono.empty();
                    });
        } catch (Exception e) {
            log.warn("Error building basket margin request: {}", e.getMessage());
            return Mono.empty();
        }
    }
}
