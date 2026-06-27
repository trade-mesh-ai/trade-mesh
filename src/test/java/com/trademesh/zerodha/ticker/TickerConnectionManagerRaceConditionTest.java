package com.trademesh.zerodha.ticker;

import com.trademesh.zerodha.auth.TokenAvailabilityTracker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Tests for the race condition fix in TickerConnectionManager.
 * <p>
 * Verifies that when connect() fails with "No token" error, the reactive chain
 * does NOT emit a false "token available" signal. The fix ensures that
 * doOnSuccess() only fires on genuine successful connections, not on error paths.
 */
@DisplayName("TickerConnectionManager Race Condition Tests")
@ExtendWith(MockitoExtension.class)
class TickerConnectionManagerRaceConditionTest {

    @Mock
    private ZerodhaTickerService tickerService;

    @Mock
    private TokenAvailabilityTracker tokenTracker;

    @InjectMocks
    private TickerConnectionManager manager;

    @BeforeEach
    void setUp() {
        // Reset mocks before each test
    }

    @Test
    @DisplayName("Token missing error should NOT trigger resetTracking (no false 'token available' signal)")
    void testTokenMissingErrorDoesNotTriggerReset() throws InterruptedException {
        // Arrange: connect() fails with "No token" error
        doReturn(Mono.error(new IllegalStateException("No Zerodha access token found in Redis. Please authenticate via GET /zerodha/login first.")))
                .when(tickerService).connect();

        // Act: attempt to connect
        manager.attemptConnect();

        // Give the async operation time to complete
        Thread.sleep(100);

        // Assert: setTokenUnavailable and trackTokenMissing should be called
        verify(tokenTracker, times(1)).setTokenUnavailable();
        verify(tokenTracker, times(1)).trackTokenMissing("TickerConnectionManager");

        // Assert: resetTracking should NOT be called (this was the bug)
        verify(tokenTracker, times(0)).resetTracking("TickerConnectionManager");
    }

    @Test
    @DisplayName("Successful connection should trigger resetTracking")
    void testSuccessfulConnectionTriggersReset() throws InterruptedException {
        // Arrange: connect() succeeds
        doReturn(Mono.empty())
                .when(tickerService).connect();

        // Act: attempt to connect
        manager.attemptConnect();

        // Give the async operation time to complete
        Thread.sleep(100);

        // Assert: resetTracking should be called on success
        verify(tokenTracker, times(1)).resetTracking("TickerConnectionManager");

        // Assert: setTokenUnavailable and trackTokenMissing should NOT be called
        verify(tokenTracker, times(0)).setTokenUnavailable();
        verify(tokenTracker, times(0)).trackTokenMissing("TickerConnectionManager");
    }

    @Test
    @DisplayName("Multiple concurrent token-missing errors should not produce false 'token available' signals")
    void testConcurrentTokenMissingErrorsNoFalseSignals() throws InterruptedException {
        // Arrange: connect() always fails with token missing error
        doReturn(Mono.error(new IllegalStateException("No Zerodha access token found in Redis. Please authenticate via GET /zerodha/login first.")))
                .when(tickerService).connect();

        int concurrentAttempts = 5;
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch endLatch = new CountDownLatch(concurrentAttempts);

        // Act: simulate 5 concurrent connection attempts (e.g., from different schedulers)
        for (int i = 0; i < concurrentAttempts; i++) {
            new Thread(() -> {
                try {
                    startLatch.await();
                    manager.attemptConnect();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    endLatch.countDown();
                }
            }).start();
        }

        startLatch.countDown();
        endLatch.await();

        // Give async operations time to complete
        Thread.sleep(200);

        // Assert: setTokenUnavailable and trackTokenMissing should be called for each attempt
        verify(tokenTracker, times(concurrentAttempts)).setTokenUnavailable();
        verify(tokenTracker, times(concurrentAttempts)).trackTokenMissing("TickerConnectionManager");

        // Assert: resetTracking should NEVER be called (this was the race condition)
        verify(tokenTracker, times(0)).resetTracking("TickerConnectionManager");
    }

    @Test
    @DisplayName("Non-token errors should not trigger token tracking")
    void testNonTokenErrorsDoNotTriggerTokenTracking() throws InterruptedException {
        // Arrange: connect() fails with a different error
        doReturn(Mono.error(new RuntimeException("Network connection failed")))
                .when(tickerService).connect();

        // Act: attempt to connect
        manager.attemptConnect();

        // Give the async operation time to complete
        Thread.sleep(100);

        // Assert: token tracking methods should NOT be called
        verify(tokenTracker, times(0)).setTokenUnavailable();
        verify(tokenTracker, times(0)).trackTokenMissing("TickerConnectionManager");
        verify(tokenTracker, times(0)).resetTracking("TickerConnectionManager");
    }

    @Test
    @DisplayName("Sequence: token missing → token available → token missing again should log correctly")
    void testTokenStateTransitionSequence() throws InterruptedException {
        // First attempt: token missing
        doReturn(Mono.error(new IllegalStateException("No Zerodha access token found in Redis. Please authenticate via GET /zerodha/login first.")))
                .when(tickerService).connect();

        manager.attemptConnect();
        Thread.sleep(100);

        // Verify first token missing was logged
        verify(tokenTracker, times(1)).setTokenUnavailable();
        verify(tokenTracker, times(1)).trackTokenMissing("TickerConnectionManager");
        verify(tokenTracker, times(0)).resetTracking("TickerConnectionManager");

        // Second attempt: token now available (connection succeeds)
        doReturn(Mono.empty())
                .when(tickerService).connect();

        manager.attemptConnect();
        Thread.sleep(100);

        // Verify token available was logged
        verify(tokenTracker, times(1)).resetTracking("TickerConnectionManager");

        // Third attempt: token missing again
        doReturn(Mono.error(new IllegalStateException("No Zerodha access token found in Redis. Please authenticate via GET /zerodha/login first.")))
                .when(tickerService).connect();

        manager.attemptConnect();
        Thread.sleep(100);

        // Verify second token missing was logged
        verify(tokenTracker, times(2)).setTokenUnavailable();
        verify(tokenTracker, times(2)).trackTokenMissing("TickerConnectionManager");

        // Verify resetTracking was only called once (for the successful connection)
        verify(tokenTracker, times(1)).resetTracking("TickerConnectionManager");
    }
}
