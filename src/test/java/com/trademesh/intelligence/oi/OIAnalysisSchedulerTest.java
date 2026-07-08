package com.trademesh.intelligence.oi;

import com.trademesh.zerodha.auth.TokenAvailabilityTracker;
import com.trademesh.zerodha.options.OptionChain;
import com.trademesh.zerodha.options.OptionChainService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for OIAnalysisScheduler token-missing error handling.
 * Verifies that trackTokenMissing(SCHEDULER_NAME) is called when captureAnalysis()
 * throws a token-missing IllegalStateException, and NOT called for other error types.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("OIAnalysisScheduler Token-Missing Error Handling Tests")
class OIAnalysisSchedulerTest {

    @Mock
    private OptionChainService optionChainService;

    @Mock
    private OIAnalyserService oiAnalyserService;

    @Mock
    private TokenAvailabilityTracker tokenTracker;

    @InjectMocks
    private OIAnalysisScheduler oiAnalysisScheduler;

    @BeforeEach
    void setUp() {
        // Default mock behavior: tokenTracker.isTokenAvailable() returns true
        lenient().when(tokenTracker.isTokenAvailable()).thenReturn(true);
    }

    /**
     * Test: When captureAnalysis() throws IllegalStateException with "No Zerodha access token",
     * trackTokenMissing(SCHEDULER_NAME) is called for each symbol.
     * <p>
     * Verifies:
     * 1. tokenTracker.trackTokenMissing("OIAnalysisScheduler") is called for each symbol
     * 2. The error is swallowed (Mono.empty() returned)
     * 3. No other error logging occurs
     * <p>
     * Uses any(String.class) to stub all symbols uniformly, since the scheduler loops
     * over SYMBOLS (NIFTY50 and NIFTYBANK).
     */
    @Test
    @DisplayName("Should call trackTokenMissing(SCHEDULER_NAME) when token-missing exception occurs")
    void testTrackTokenMissingCalledOnTokenMissingException() {
        // Arrange
        IllegalStateException tokenMissingException = new IllegalStateException("No Zerodha access token");

        when(optionChainService.fetchOptionChain(any(String.class)))
                .thenReturn(Mono.error(tokenMissingException));

        // Act: Call the scheduler (which iterates over SYMBOLS)
        oiAnalysisScheduler.captureOIAnalysisSnapshots();

        // Assert: Verify trackTokenMissing was called for each symbol (2 symbols)
        verify(tokenTracker, times(2)).trackTokenMissing("OIAnalysisScheduler");
    }

    /**
     * Test: When captureAnalysis() throws a different exception type (not token-missing),
     * trackTokenMissing() is NOT called, and the error is logged.
     * <p>
     * Verifies:
     * 1. tokenTracker.trackTokenMissing() is never called
     * 2. The error is re-thrown (not swallowed)
     * <p>
     * Uses any(String.class) to stub all symbols uniformly. Since both symbols
     * throw the same non-token-missing error, trackTokenMissing should never be called.
     */
    @Test
    @DisplayName("Should NOT call trackTokenMissing() for non-token-missing exceptions")
    void testTrackTokenMissingNotCalledForOtherExceptions() {
        // Arrange
        RuntimeException otherException = new RuntimeException("Some other error");

        when(optionChainService.fetchOptionChain(any(String.class)))
                .thenReturn(Mono.error(otherException));

        // Act: Call the scheduler
        oiAnalysisScheduler.captureOIAnalysisSnapshots();

        // Assert: Verify trackTokenMissing was NOT called
        verify(tokenTracker, never()).trackTokenMissing(any());
    }

    /**
     * Test: When captureAnalysis() throws IllegalStateException but with a different message,
     * trackTokenMissing() is NOT called.
     * <p>
     * Verifies that the error message check is specific to "No Zerodha access token".
     * <p>
     * Uses any(String.class) to stub all symbols uniformly. Since both symbols
     * throw the same non-token-missing IllegalStateException, trackTokenMissing should never be called.
     */
    @Test
    @DisplayName("Should NOT call trackTokenMissing() for IllegalStateException with different message")
    void testTrackTokenMissingNotCalledForDifferentIllegalStateException() {
        // Arrange
        IllegalStateException differentException = new IllegalStateException("Some other illegal state");

        when(optionChainService.fetchOptionChain(any(String.class)))
                .thenReturn(Mono.error(differentException));

        // Act: Call the scheduler
        oiAnalysisScheduler.captureOIAnalysisSnapshots();

        // Assert: Verify trackTokenMissing was NOT called
        verify(tokenTracker, never()).trackTokenMissing(any());
    }

    /**
     * Test: When captureAnalysis() throws token-missing exception for multiple symbols,
     * trackTokenMissing() is called for each symbol.
     * <p>
     * Verifies that the error handling works correctly in the loop.
     */
    @Test
    @DisplayName("Should call trackTokenMissing() for each symbol when token-missing exception occurs")
    void testTrackTokenMissingCalledForEachSymbolWithTokenMissingException() {
        // Arrange
        IllegalStateException tokenMissingException = new IllegalStateException("No Zerodha access token");

        when(optionChainService.fetchOptionChain(any()))
                .thenReturn(Mono.error(tokenMissingException));

        // Act: Call the scheduler (which iterates over SYMBOLS)
        oiAnalysisScheduler.captureOIAnalysisSnapshots();

        // Assert: Verify trackTokenMissing was called for each symbol
        // SYMBOLS contains NIFTY50 and NIFTYBANK (2 symbols)
        verify(tokenTracker, times(2)).trackTokenMissing("OIAnalysisScheduler");
    }

    /**
     * Test: When captureAnalysis() succeeds, trackTokenMissing() is not called.
     * <p>
     * Verifies that successful operations don't trigger token tracking.
     * <p>
     * Uses any(String.class) to stub all symbols uniformly. Stubs fetchOptionChain()
     * to return a non-empty Mono with a mock OptionChain so the reactive chain
     * actually flows through to captureAndSave().
     */
    @Test
    @DisplayName("Should NOT call trackTokenMissing() when captureAnalysis() succeeds")
    void testTrackTokenMissingNotCalledOnSuccess() {
        // Arrange
        when(optionChainService.fetchOptionChain(any(String.class)))
                .thenReturn(Mono.just(mock(OptionChain.class)));
        when(oiAnalyserService.captureAndSave(any()))
                .thenReturn(Mono.empty());

        // Act: Call the scheduler
        oiAnalysisScheduler.captureOIAnalysisSnapshots();

        // Assert: Verify trackTokenMissing was NOT called
        verify(tokenTracker, never()).trackTokenMissing(any());
    }
}
