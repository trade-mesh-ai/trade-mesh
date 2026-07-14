package com.trademesh.intelligence.vix;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * Unit tests for VixHistory entity and factory methods.
 * Verifies that VixHistory.fromVixData() correctly uses the fetchedAt timestamp
 * from VixData, not the current time.
 */
@DisplayName("VixHistory Factory Method Tests")
class VixHistoryTest {

    /**
     * Test: fromVixData() uses vixData.getFetchedAt(), not Instant.now().
     *
     * <p>Constructs a VixData with fetchedAt set to 5 minutes in the past,
     * calls fromVixData(), and asserts that the resulting VixHistory.recordedAt
     * equals that exact past instant, not approximately "now".
     *
     * <p>This ensures audit trail accuracy: the recorded timestamp reflects
     * when data was actually fetched, not when it was persisted.
     */
    @Test
    @DisplayName("Should use vixData.getFetchedAt() for recordedAt, not Instant.now()")
    void testFromVixDataUsesFetchedAtNotNow() {
        // Arrange: Create a VixData with fetchedAt set to 5 minutes ago
        Instant pastInstant = Instant.now().minus(5, ChronoUnit.MINUTES);
        VixData vixData = VixData.builder()
                .currentVix(18.5)
                .vixLevel(VixLevel.NORMAL)
                .previousClose(17.2)
                .changePercent(7.56)
                .fetchedAt(pastInstant)
                .build();

        // Act: Convert VixData to VixHistory
        VixHistory vixHistory = VixHistory.fromVixData(vixData);

        // Assert: recordedAt should equal the past instant, not approximately now
        assertEquals(pastInstant, vixHistory.getRecordedAt(),
                "VixHistory.recordedAt should equal vixData.getFetchedAt()");

        // Assert: recordedAt should NOT be approximately now (within 1 second)
        Instant now = Instant.now();
        assertNotEquals(now, vixHistory.getRecordedAt(),
                "VixHistory.recordedAt should not be the current time");

        // Verify the difference is approximately 5 minutes
        long secondsDifference = ChronoUnit.SECONDS.between(vixHistory.getRecordedAt(), now);
        assertEquals(300, secondsDifference, 2,
                "VixHistory.recordedAt should be approximately 5 minutes in the past");
    }

    /**
     * Test: fromVixData() correctly maps all VixData fields to VixHistory.
     *
     * <p>Verifies that the factory method properly converts all fields:
     * currentVix → vixValue, vixLevel → vixLevel (as name), changePercent → changePercent,
     * and fetchedAt → recordedAt.
     */
    @Test
    @DisplayName("Should correctly map all VixData fields to VixHistory")
    void testFromVixDataMapsAllFields() {
        // Arrange
        Instant fetchedAt = Instant.now().minus(2, ChronoUnit.MINUTES);
        VixData vixData = VixData.builder()
                .currentVix(22.5)
                .vixLevel(VixLevel.ELEVATED)
                .previousClose(20.0)
                .changePercent(12.5)
                .fetchedAt(fetchedAt)
                .build();

        // Act
        VixHistory vixHistory = VixHistory.fromVixData(vixData);

        // Assert
        assertEquals(22.5, vixHistory.getVixValue(), "vixValue should match currentVix");
        assertEquals("ELEVATED", vixHistory.getVixLevel(), "vixLevel should be the enum name");
        assertEquals(12.5, vixHistory.getChangePercent(), "changePercent should match");
        assertEquals(fetchedAt, vixHistory.getRecordedAt(), "recordedAt should match fetchedAt");
    }
}
