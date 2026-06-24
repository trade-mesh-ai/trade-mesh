package com.trademesh.zerodha.auth;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;

/**
 * Service responsible for Zerodha / Kite Connect authentication.
 * <p>
 * Flow:
 * 1. User is redirected to generateLoginUrl() → Kite login page.
 * 2. Kite redirects back with request_token.
 * 3. generateSession(String) exchanges the request token for an access token
 * and stores it in Redis with an 8-hour TTL.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ZerodhaAuthService {

    /**
     * Redis key under which the Zerodha access token is stored.
     */
    public static final String ACCESS_TOKEN_KEY = "zerodha:access_token";

    private final ZerodhaConfig config;
    private final ReactiveStringRedisTemplate redisTemplate;
    private final WebClient.Builder webClientBuilder;
    private final TokenAvailabilityTracker tokenAvailabilityTracker;

    // ─── Login URL ────────────────────────────────────────────────────────────────

    /**
     * Builds the Kite Connect OAuth2 login URL.
     *
     * @return fully-qualified login URL, e.g.
     * https://kite.zerodha.com/connect/login?api_key=xxx&v=3
     */
    public String generateLoginUrl() {
        String url = config.getLoginUrl() + "?api_key=" + config.getApiKey() + "&v=3";
        log.debug("Generated Kite login URL: {}", url);
        return url;
    }

    // ─── Session / Access Token ───────────────────────────────────────────────────

    /**
     * Exchanges a request_token (received from Kite callback) for an
     * access_token by calling the Kite Connect session API.
     * <p>
     * The access token is stored in Redis under ACCESS_TOKEN_KEY
     * with a TTL of zerodha.kite.access-token-ttl-hours (default 8 h).
     *
     * @param requestToken one-time token received from Kite Connect callback
     * @return Mono emitting the access token string
     */
    public Mono<String> generateSession(String requestToken) {
        log.info("Exchanging request_token for access_token ...");

        String checksum = computeChecksum(config.getApiKey(), requestToken, config.getApiSecret());

        WebClient client = webClientBuilder
                .baseUrl(config.getBaseUrl())
                .build();

        return client.post()
                .uri("/session/token")
                .header("X-Kite-Version", "3")
                .bodyValue("api_key=" + config.getApiKey()
                        + "&request_token=" + requestToken
                        + "&checksum=" + checksum)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .retrieve()
                .bodyToMono(KiteSessionResponse.class)
                .flatMap(this::handleKiteSessionResponse)
                .doOnError(e -> log.error("Failed to generate Kite session: {}", e.getMessage(), e));
    }

    // ─── Redis helpers ────────────────────────────────────────────────────────────

    /**
     * Stores the access token in Redis with the configured TTL.
     *
     * @param accessToken Kite Connect access token
     * @return Mono emitting the stored access token
     */
    public Mono<String> storeAccessToken(String accessToken) {
        Duration ttl = Duration.ofHours(config.getAccessTokenTtlHours());
        return redisTemplate.opsForValue()
                .set(ACCESS_TOKEN_KEY, accessToken, ttl)
                .doOnSuccess(ok -> log.info("Access token stored in Redis (TTL={}h)", config.getAccessTokenTtlHours()))
                .thenReturn(accessToken);
    }

    /**
     * Validates the Kite session response, extracts the access token and stores it.
     */
    private Mono<String> handleKiteSessionResponse(KiteSessionResponse response) {
        if (response == null) {
            return Mono.error(new IllegalStateException("Response is null"));
        }

        KiteSessionResponse.SessionData data = response.getData();
        if (data == null) {
            return Mono.error(new IllegalStateException("Response data is null"));
        }

        String accessToken = data.getAccessToken();
        log.info("Access token received: [{}]", SensitiveDataMasker.maskToken(accessToken));

        if (accessToken == null || accessToken.isBlank()) {
            return Mono.error(new IllegalStateException("Access token is null or empty"));
        }

        return storeAccessToken(accessToken)
                .doOnSuccess(token -> tokenAvailabilityTracker.resetAll());
    }

    /**
     * Retrieves the current access token from Redis.
     *
     * @return Mono emitting the access token, or empty if not present / expired
     */
    public Mono<String> getAccessToken() {
        return redisTemplate.opsForValue().get(ACCESS_TOKEN_KEY);
    }

    /**
     * Checks whether a valid (non-expired) access token exists in Redis.
     *
     * @return Mono emitting true if a token is present
     */
    public Mono<Boolean> isTokenValid() {
        return redisTemplate.hasKey(ACCESS_TOKEN_KEY);
    }

    // ─── Checksum ─────────────────────────────────────────────────────────────────

    /**
     * Computes the SHA-256 checksum required by Kite Connect:
     * SHA256(api_key + request_token + api_secret).
     */
    private String computeChecksum(String apiKey, String requestToken, String apiSecret) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(
                    (apiKey + requestToken + apiSecret).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm not available", e);
        }
    }

    // ─── Inner DTO ────────────────────────────────────────────────────────────────

    /**
     * Minimal representation of the Kite Connect POST /session/token response.
     */
    @lombok.Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    static class KiteSessionResponse {

        private String status;
        private SessionData data;

        @lombok.Data
        @JsonIgnoreProperties(ignoreUnknown = true)
        static class SessionData {

            @JsonProperty("access_token")
            private String accessToken;

            @JsonProperty("public_token")
            private String publicToken;

            @JsonProperty("user_id")
            private String userId;

            @JsonProperty("login_time")
            private String loginTime;
        }
    }
}
