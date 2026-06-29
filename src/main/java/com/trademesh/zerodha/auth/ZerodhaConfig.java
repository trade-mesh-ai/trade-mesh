package com.trademesh.zerodha.auth;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Binds Zerodha / Kite Connect configuration from application.yml.
 *
 * <pre>
 * zerodha:
 *   kite:
 *     api-key: ${ZERODHA_API_KEY}
 *     api-secret: ${ZERODHA_API_SECRET}
 *     redirect-url: ${ZERODHA_REDIRECT_URL}
 *     base-url: https://api.kite.trade
 *     login-url: https://kite.zerodha.com/connect/login
 *     access-token-ttl-hours: 8
 * </pre>
 */
@Data
@ConfigurationProperties(prefix = "zerodha.kite")
public class ZerodhaConfig {

    /**
     * Kite Connect API key (from Zerodha developer console).
     */
    private String apiKey;

    /**
     * Kite Connect API secret (from Zerodha developer console).
     */
    private String apiSecret;

    /**
     * OAuth2 redirect / callback URL registered in the Kite app.
     */
    private String redirectUrl;

    /**
     * Base REST URL for Kite Connect API.
     */
    private String baseUrl = "https://api.kite.trade";

    /**
     * Kite Connect login page URL.
     */
    private String loginUrl = "https://kite.zerodha.com/connect/login";

    /**
     * How long (in hours) the access token should be cached in Redis.
     * Zerodha tokens are valid for one trading day; we default to 8 hours.
     */
    private long accessTokenTtlHours = 16;
}
