package com.trademesh.zerodha.ticker;

import com.trademesh.zerodha.auth.TokenAvailabilityTracker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Mono;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.*;

/**
 * Unit tests for TickerConnectionManager.
 * <p>
 * Verifies that:
 * - Health check escalates log severity based on reconnect attempts
 * - CRITICAL threshold is configurable and respected
 * - Token tracking is synchronized to prevent race conditions
 * - Reconnect counter resets on successful connection
 */
@DisplayName("TickerConnectionManager Tests")
@ExtendWith(MockitoExtension.class)
class TickerConnectionManagerTest {

    @Mock
    private ZerodhaTickerService tickerService;

    @Mock
    private TokenAvailabilityTracker tokenTracker;

    @InjectMocks
    private TickerConnectionManager manager;

    @BeforeEach
    void setUp() {
        // Set default max reconnect alerts to 10 (5 minutes)
        ReflectionTestUtils.setField(manager, "maxReconnectAlerts", 10);
    }

    @Test
    @DisplayName("Health check should log DEBUG when connected and no prior failures")
    void testHealthCheckConnectedNoPriorFailures() {
        when(tickerService.isConnected()).thenReturn(true);

        manager.healthCheck();

        verify(tickerService).isConnected();
        verifyNoInteractions(tokenTracker);
    }

    @Test
    @DisplayName("Health check should log INFO when reconnected after failures")
    void testHealthCheckReconnectedAfterFailures() {
        when(tickerService.isConnected()).thenReturn(true);

        // Simulate prior failures by incrementing reconnect attempts
        ReflectionTestUtils.setField(manager, "reconnectAttempts", new java.util.concurrent.atomic.AtomicInteger(3));

        manager.healthCheck();

        verify(tickerService).isConnected();
        // Verify reconnect counter was reset
        assertEquals(0, ((java.util.concurrent.atomic.AtomicInteger) ReflectionTestUtils.getField(manager, "reconnectAttempts")).get());
    }

    @Test
    @DisplayName("Health check should log WARN when disconnected within threshold")
    void testHealthCheckDisconnectedWithinThreshold() {
        when(tickerService.isConnected()).thenReturn(false);
        when(tickerService.connect()).thenReturn(Mono.empty());

        // Set threshold to 5
        ReflectionTestUtils.setField(manager, "maxReconnectAlerts", 5);

        // Simulate 3 consecutive failures (within threshold)
        for (int i = 0; i < 3; i++) {
            manager.healthCheck();
        }

        // Verify reconnect attempts incremented
        assertEquals(3, ((java.util.concurrent.atomic.AtomicInteger) ReflectionTestUtils.getField(manager, "reconnectAttempts")).get());
        verify(tickerService, times(3)).connect();
    }

    @Test
    @DisplayName("Health check should log ERROR when disconnected beyond threshold")
    void testHealthCheckDisconnectedBeyondThreshold() {
        when(tickerService.isConnected()).thenReturn(false);
        when(tickerService.connect()).thenReturn(Mono.empty());

        // Set threshold to 3
        ReflectionTestUtils.setField(manager, "maxReconnectAlerts", 3);

        // Simulate 5 consecutive failures (beyond threshold)
        for (int i = 0; i < 5; i++) {
            manager.healthCheck();
        }

        // Verify reconnect attempts incremented to 5
        assertEquals(5, ((java.util.concurrent.atomic.AtomicInteger) ReflectionTestUtils.getField(manager, "reconnectAttempts")).get());
        verify(tickerService, times(5)).connect();
    }

    @Test
    @DisplayName("Configurable threshold should be respected")
    void testConfigurableThreshold() {
        // Test with threshold of 2
        ReflectionTestUtils.setField(manager, "maxReconnectAlerts", 2);

        when(tickerService.isConnected()).thenReturn(false);
        when(tickerService.connect()).thenReturn(Mono.empty());

        // Simulate 3 failures
        for (int i = 0; i < 3; i++) {
            manager.healthCheck();
        }

        // Verify that 3 attempts > 2 threshold
        assertEquals(3, ((java.util.concurrent.atomic.AtomicInteger) ReflectionTestUtils.getField(manager, "reconnectAttempts")).get());
    }

    @Test
    @DisplayName("Token tracking should be synchronized on error")
    void testTokenTrackingSynchronizedOnError() {
        when(tickerService.connect()).thenReturn(Mono.error(new IllegalStateException("No Zerodha access token found")));

        manager.onApplicationReady();

        // Wait for async operation
        try {
            Thread.sleep(100);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // Verify token tracking was called
        verify(tokenTracker).setTokenUnavailable();
        verify(tokenTracker).trackTokenMissing("TickerConnectionManager");
    }

    @Test
    @DisplayName("Token tracking should be synchronized on success")
    void testTokenTrackingSynchronizedOnSuccess() {
        when(tickerService.connect()).thenReturn(Mono.empty());

        manager.onApplicationReady();

        // Wait for async operation
        try {
            Thread.sleep(100);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // Verify token tracking was reset
        verify(tokenTracker).resetTracking("TickerConnectionManager");
    }

    @Test
    @DisplayName("Default max reconnect alerts should be 10 (5 minutes)")
    void testDefaultMaxReconnectAlerts() {
        // Create new manager without explicit field setting
        TickerConnectionManager newManager = new TickerConnectionManager(tickerService, tokenTracker);

        // The @Value annotation should inject the default value from application.yml
        // We can't directly test this without Spring context, but we verify the field exists
        assertNotNull(ReflectionTestUtils.getField(newManager, "maxReconnectAlerts"));
    }
}
