package com.trademesh.config;

import com.trademesh.zerodha.auth.ZerodhaConfig;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.util.unit.DataSize;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Configuration for WebClient beans used across the application.
 * Provides specialized WebClient instances for different API endpoints.
 */
@Configuration
@RequiredArgsConstructor
public class WebClientConfig {

    private final ZerodhaConfig zerodhaConfig;
    private final WebClient.Builder webClientBuilder;

    @Value("${zerodha.kite.instruments-buffer-size-mb:10}")
    private DataSize maxInMemorySize;

    /**
     * Creates a dedicated WebClient for fetching the Kite /instruments endpoint.
     * 10MB buffer required for Kite /instruments CSV (~5-8MB, 50k+ rows).
     * Instruments are fetched once per day and cached in Redis for 24 hours.
     *
     * @param accessToken Zerodha access token for authorization
     * @return WebClient configured with 10MB buffer for large CSV responses
     */
    public WebClient instrumentsClient(String accessToken) {
        return webClientBuilder
                .baseUrl(zerodhaConfig.getBaseUrl())
                .defaultHeader(HttpHeaders.AUTHORIZATION, "token " + zerodhaConfig.getApiKey() + ":" + accessToken)
                .codecs(configurer -> configurer
                        .defaultCodecs()
                        .maxInMemorySize((int) maxInMemorySize.toBytes()))
                .build();
    }
}
