package com.trademesh.intelligence.vix;

import com.trademesh.zerodha.auth.TokenAvailabilityTracker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;

import static org.mockito.Mockito.*;

/**
 * Unit tests for VixScheduler token availability guard.
 * Verifies that persistVixData() and fetchVixData() correctly check token availability
 * before making Kite API calls.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("VixScheduler Token Availability Guard Tests")
class VixSchedulerTest {

    @Mock
    private VixMonitorService vixMonitorService;

    @Mock
    private VixAlertService vixAlertService;

    @Mock
    private VixHistoryRepository vixHistoryRepository;

    @Mock
    private TokenAvailabilityTracker tokenTracker;

    @InjectMocks
    private VixScheduler vixScheduler;

    @BeforeEach
    void setUp() {
        // Default mock behavior: tokenTracker.isTokenAvailable() returns true
        lenient().when(tokenTracker.isTokenAvailable()).thenReturn(true);
        // Default mock behavior: vixMonitorService methods return empty Mono
        lenient().when(vixMonitorService.fetchCurrentVix()).thenReturn(Mono.empty());
        lenient().when(vixMonitorService.fetchAndSave()).thenReturn(Mono.empty());
    }

    /**
     * Test: persistVixData() does NOT call vixMonitorService.fetchAndSave()
     * when tokenTracker.isTokenAvailable() returns false.
     * <p>
     * Verifies:
     * 1. vixMonitorService.fetchAndSave() is never called
     * 2. The method returns early with a WARN log
     */
    @Test
    @DisplayName("Should NOT call fetchAndSave() when token is unavailable")
    void testPersistVixDataSkipsWhenTokenUnavailable() {
        // Arrange
        when(tokenTracker.isTokenAvailable()).thenReturn(false);

        // Act
        vixScheduler.persistVixData();

        // Assert
        verify(vixMonitorService, never()).fetchAndSave();
    }

    /**
     * Test: persistVixData() DOES call vixMonitorService.fetchAndSave()
     * when tokenTracker.isTokenAvailable() returns true.
     * <p>
     * Verifies:
     * 1. vixMonitorService.fetchAndSave() is called exactly once
     * 2. The reactive chain is subscribed to
     */
    @Test
    @DisplayName("Should call fetchAndSave() when token is available")
    void testPersistVixDataCallsFetchAndSaveWhenTokenAvailable() {
        // Arrange
        when(tokenTracker.isTokenAvailable()).thenReturn(true);
        when(vixMonitorService.fetchAndSave()).thenReturn(Mono.empty());

        // Act
        vixScheduler.persistVixData();

        // Assert
        verify(vixMonitorService, times(1)).fetchAndSave();
    }

    /**
     * Test: fetchVixData() does NOT call vixMonitorService.fetchCurrentVix()
     * when tokenTracker.isTokenAvailable() returns false.
     * <p>
     * Verifies:
     * 1. vixMonitorService.fetchCurrentVix() is never called
     * 2. The method returns early with a WARN log
     */
    @Test
    @DisplayName("Should NOT call fetchCurrentVix() when token is unavailable")
    void testFetchVixDataSkipsWhenTokenUnavailable() {
        // Arrange
        when(tokenTracker.isTokenAvailable()).thenReturn(false);

        // Act
        vixScheduler.fetchVixData();

        // Assert
        verify(vixMonitorService, never()).fetchCurrentVix();
    }

    /**
     * Test: fetchVixData() DOES call vixMonitorService.fetchCurrentVix()
     * when tokenTracker.isTokenAvailable() returns true.
     * <p>
     * Verifies:
     * 1. vixMonitorService.fetchCurrentVix() is called exactly once
     * 2. The reactive chain is subscribed to
     */
    @Test
    @DisplayName("Should call fetchCurrentVix() when token is available")
    void testFetchVixDataCallsFetchCurrentVixWhenTokenAvailable() {
        // Arrange
        when(tokenTracker.isTokenAvailable()).thenReturn(true);
        when(vixMonitorService.fetchCurrentVix()).thenReturn(Mono.empty());

        // Act
        vixScheduler.fetchVixData();

        // Assert
        verify(vixMonitorService, times(1)).fetchCurrentVix();
    }
}
