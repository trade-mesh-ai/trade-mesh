package com.trademesh.zerodha.auth;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.ReactiveValueOperations;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ZerodhaAuthServiceTest {

    @Mock
    private ZerodhaConfig config;

    @Mock
    private ReactiveStringRedisTemplate redisTemplate;

    @Mock
    private WebClient.Builder webClientBuilder;

    private ZerodhaAuthService authService;

    @BeforeEach
    void setUp() {
        authService = new ZerodhaAuthService(config, redisTemplate, webClientBuilder);
    }

    @Test
    void testGenerateLoginUrlBuildsCorrectUrl() {
        when(config.getLoginUrl()).thenReturn("https://kite.zerodha.com/connect/login");
        when(config.getApiKey()).thenReturn("test_api_key");

        String loginUrl = authService.generateLoginUrl();

        assertNotNull(loginUrl);
        assertEquals("https://kite.zerodha.com/connect/login?api_key=test_api_key&v=3", loginUrl);
    }

    @Test
    void testStoreAccessTokenStoresInRedisWithTtl() {
        String testToken = "test_access_token";
        ReactiveValueOperations<String, String> valueOps = mock(ReactiveValueOperations.class);

        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.set(anyString(), anyString(), any(Duration.class))).thenReturn(Mono.just(true));
        when(config.getAccessTokenTtlHours()).thenReturn(8L);

        Mono<String> result = authService.storeAccessToken(testToken);

        StepVerifier.create(result)
                .assertNext(token -> assertEquals(testToken, token))
                .verifyComplete();
    }

    @Test
    void testGetAccessTokenRetrievesFromRedis() {
        String testToken = "test_access_token";
        ReactiveValueOperations<String, String> valueOps = mock(ReactiveValueOperations.class);

        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(ZerodhaAuthService.ACCESS_TOKEN_KEY)).thenReturn(Mono.just(testToken));

        Mono<String> result = authService.getAccessToken();

        StepVerifier.create(result)
                .assertNext(token -> assertEquals(testToken, token))
                .verifyComplete();
    }

    @Test
    void testIsTokenValidChecksRedisKeyExistence() {
        when(redisTemplate.hasKey(ZerodhaAuthService.ACCESS_TOKEN_KEY)).thenReturn(Mono.just(true));

        Mono<Boolean> result = authService.isTokenValid();

        StepVerifier.create(result)
                .assertNext(valid -> assertEquals(true, valid))
                .verifyComplete();
    }

    @Test
    void testIsTokenValidReturnsFalseWhenTokenNotPresent() {
        when(redisTemplate.hasKey(ZerodhaAuthService.ACCESS_TOKEN_KEY)).thenReturn(Mono.just(false));

        Mono<Boolean> result = authService.isTokenValid();

        StepVerifier.create(result)
                .assertNext(valid -> assertEquals(false, valid))
                .verifyComplete();
    }
}
