package com.trademesh.zerodha.auth;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ZerodhaAuthControllerTest {

    @Mock
    private ZerodhaAuthService authService;

    private ZerodhaAuthController controller;

    @BeforeEach
    void setUp() {
        controller = new ZerodhaAuthController(authService);
    }

    @Test
    void testLoginRedirectsToKiteLoginUrl() {
        String expectedUrl = "https://kite.zerodha.com/connect/login?api_key=test&v=3";
        when(authService.generateLoginUrl()).thenReturn(expectedUrl);

        Mono<ResponseEntity<Void>> result = controller.login();

        StepVerifier.create(result)
                .assertNext(response -> {
                    assertEquals(HttpStatus.FOUND, response.getStatusCode());
                    assertNotNull(response.getHeaders().getLocation());
                    assertEquals(expectedUrl, response.getHeaders().getLocation().toString());
                })
                .verifyComplete();
    }

    @Test
    void testCallbackWithErrorStatusReturnsBadRequest() {
        Mono<ResponseEntity<String>> result = controller.callback("test_token", "error");

        StepVerifier.create(result)
                .assertNext(response -> {
                    assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
                    assertNotNull(response.getBody());
                    assertEquals("text/html;charset=UTF-8", response.getHeaders().getContentType().toString());
                })
                .verifyComplete();
    }

    @Test
    void testCallbackWithValidTokenReturnsSuccess() {
        String testToken = "test_access_token";
        when(authService.generateSession(anyString())).thenReturn(Mono.just(testToken));

        Mono<ResponseEntity<String>> result = controller.callback("test_request_token", "success");

        StepVerifier.create(result)
                .assertNext(response -> {
                    assertEquals(HttpStatus.OK, response.getStatusCode());
                    assertNotNull(response.getBody());
                    assertEquals("text/html;charset=UTF-8", response.getHeaders().getContentType().toString());
                })
                .verifyComplete();
    }

    @Test
    void testCallbackWithAuthenticationErrorReturnsInternalServerError() {
        when(authService.generateSession(anyString()))
                .thenReturn(Mono.error(new RuntimeException("Kite API error")));

        Mono<ResponseEntity<String>> result = controller.callback("test_request_token", "success");

        StepVerifier.create(result)
                .assertNext(response -> {
                    assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
                    assertNotNull(response.getBody());
                    assertEquals("text/html;charset=UTF-8", response.getHeaders().getContentType().toString());
                })
                .verifyComplete();
    }

    @Test
    void testTokenStatusWithValidTokenReturnsOk() {
        when(authService.isTokenValid()).thenReturn(Mono.just(true));

        Mono<ResponseEntity<String>> result = controller.tokenStatus();

        StepVerifier.create(result)
                .assertNext(response -> {
                    assertEquals(HttpStatus.OK, response.getStatusCode());
                    assertNotNull(response.getBody());
                    assertEquals("text/html;charset=UTF-8", response.getHeaders().getContentType().toString());
                })
                .verifyComplete();
    }

    @Test
    void testTokenStatusWithoutValidTokenReturnsUnauthorized() {
        when(authService.isTokenValid()).thenReturn(Mono.just(false));

        Mono<ResponseEntity<String>> result = controller.tokenStatus();

        StepVerifier.create(result)
                .assertNext(response -> {
                    assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
                    assertNotNull(response.getBody());
                    assertEquals("text/html;charset=UTF-8", response.getHeaders().getContentType().toString());
                })
                .verifyComplete();
    }
}
