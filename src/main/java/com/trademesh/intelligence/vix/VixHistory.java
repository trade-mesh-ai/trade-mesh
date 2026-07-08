package com.trademesh.intelligence.vix;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;

/**
 * R2DBC entity representing a historical VIX record.
 *
 * <p>Persisted to the {@code vix_history} table for audit and backtesting purposes.
 * Records are saved every 15 minutes.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Table("vix_history")
public class VixHistory {

    @Id
    private Long id;

    @Column("vix_value")
    private Double vixValue;

    @Column("vix_level")
    private String vixLevel;

    @Column("change_percent")
    private Double changePercent;

    @Column("recorded_at")
    private Instant recordedAt;

    /**
     * Creates a VixHistory record from VixData.
     *
     * @param vixData the VixData to persist
     * @return a new VixHistory instance
     */
    public static VixHistory fromVixData(VixData vixData) {
        return VixHistory.builder()
                .vixValue(vixData.getCurrentVix())
                .vixLevel(vixData.getVixLevel().name())
                .changePercent(vixData.getChangePercent())
                .recordedAt(vixData.getFetchedAt())
                .build();
    }
}
