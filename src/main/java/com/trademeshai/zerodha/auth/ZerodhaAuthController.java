package com.trademeshai.zerodha.auth;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.URI;

/**
 * REST controller for Zerodha / Kite Connect OAuth2 authentication.
 *
 * <ul>
 *   <li>{@code GET /zerodha/login}          – redirects the browser to the Kite login page.</li>
 *   <li>{@code GET /zerodha/callback}        – receives the {@code request_token} from Kite,
 *       exchanges it for an access token, stores it in Redis, and returns a success page.</li>
 * </ul>
 */
@Slf4j
@RestController
@RequestMapping("/zerodha")
@RequiredArgsConstructor
@Tag(name = "Zerodha Auth", description = "Kite Connect OAuth2 authentication endpoints")
public class ZerodhaAuthController {

    private final ZerodhaAuthService authService;

    // ─── Login ────────────────────────────────────────────────────────────────────

    /**
     * Redirects the user's browser to the Kite Connect login page.
     *
     * <p>Example: {@code GET /zerodha/login}
     */
    @Operation(summary = "Redirect to Zerodha / Kite Connect login page")
    @GetMapping("/login")
    public Mono<ResponseEntity<Void>> login() {
        String loginUrl = authService.generateLoginUrl();
        log.info("Redirecting to Kite login URL: {}", loginUrl);
        return Mono.just(
                ResponseEntity.status(HttpStatus.FOUND)
                        .location(URI.create(loginUrl))
                        .build()
        );
    }

    // ─── Callback ─────────────────────────────────────────────────────────────────

    /**
     * OAuth2 callback endpoint.
     *
     * <p>Kite Connect redirects here after the user logs in, appending a one-time
     * {@code request_token} as a query parameter.  This method:
     * <ol>
     *   <li>Exchanges the {@code request_token} for an {@code access_token} via the Kite API.</li>
     *   <li>Stores the access token in Redis with an 8-hour TTL.</li>
     *   <li>Returns an HTML success page to the browser.</li>
     * </ol>
     *
     * <p>Example: {@code GET /zerodha/callback?request_token=xxx&status=success}
     *
     * @param requestToken one-time token provided by Kite Connect
     * @param status       optional status string from Kite ({@code "success"} or {@code "error"})
     */
    @Operation(summary = "Kite Connect OAuth2 callback – exchange request_token for access_token")
    @GetMapping("/callback")
    public Mono<ResponseEntity<String>> callback(
            @Parameter(description = "One-time request token from Kite Connect")
            @RequestParam("request_token") String requestToken,
            @Parameter(description = "Status flag from Kite Connect (success / error)")
            @RequestParam(value = "status", required = false) String status) {

        log.info("Kite callback received – request_token={}, status={}", requestToken, status);

        if ("error".equalsIgnoreCase(status)) {
            log.warn("Kite Connect returned error status during callback");
            return Mono.just(ResponseEntity
                    .status(HttpStatus.BAD_REQUEST)
                    .body(buildHtmlPage("Authentication Failed",
                            "Kite Connect returned an error. Please try again.",
                            false)));
        }

        return authService.generateSession(requestToken)
                .map(accessToken -> {
                    log.info("Authentication successful – access token stored in Redis");
                    return ResponseEntity.ok()
                            .header("Content-Type", "text/html;charset=UTF-8")
                            .body(buildHtmlPage("Authentication Successful",
                                    "You have been authenticated with Zerodha successfully. "
                                            + "The access token has been stored and is ready for trading.",
                                    true));
                })
                .onErrorResume(ex -> {
                    log.error("Failed to complete Kite authentication: {}", ex.getMessage(), ex);
                    return Mono.just(ResponseEntity
                            .status(HttpStatus.INTERNAL_SERVER_ERROR)
                            .header("Content-Type", "text/html;charset=UTF-8")
                            .body(buildHtmlPage("Authentication Error",
                                    "An error occurred while exchanging the request token: "
                                            + ex.getMessage(),
                                    false)));
                });
    }

    // ─── Token status (utility) ───────────────────────────────────────────────────

    /**
     * Returns whether a valid access token is currently stored in Redis.
     *
     * <p>Example: {@code GET /zerodha/token/status}
     */
    @Operation(summary = "Check whether a valid Zerodha access token is present in Redis")
    @GetMapping("/token/status")
    public Mono<ResponseEntity<String>> tokenStatus() {
        return authService.isTokenValid()
                .map(valid -> {
                    if (Boolean.TRUE.equals(valid)) {
                        return ResponseEntity.ok("Access token is present and valid.");
                    }
                    return ResponseEntity.status(HttpStatus.NOT_FOUND)
                            .body("No valid access token found. Please re-authenticate via /zerodha/login");
                });
    }

    // ─── HTML helper ─────────────────────────────────────────────────────────────

    private String buildHtmlPage(String title, String message, boolean success) {
        String color = success ? "#2e7d32" : "#c62828";
        String icon  = success ? "✅" : "❌";
        return "<!DOCTYPE html><html lang=\"en\"><head><meta charset=\"UTF-8\">"
                + "<title>" + title + "</title>"
                + "<style>body{font-family:sans-serif;display:flex;justify-content:center;"
                + "align-items:center;height:100vh;margin:0;background:#f5f5f5;}"
                + ".card{background:#fff;border-radius:8px;padding:40px 60px;box-shadow:"
                + "0 2px 12px rgba(0,0,0,.15);text-align:center;max-width:480px;}"
                + "h1{color:" + color + ";font-size:1.6rem;margin-bottom:12px;}"
                + "p{color:#555;line-height:1.6;}</style></head>"
                + "<body><div class=\"card\"><h1>" + icon + " " + title + "</h1>"
                + "<p>" + message + "</p></div></body></html>";
    }
}
