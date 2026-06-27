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
 * R2DBC entity representing a VIX alert event.
 *
 * <p>Persisted to the {@code vix_alerts} table for audit and backtesting purposes.
 * Alerts are triggered when VIX crosses the configured threshold (default: 20.0).
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Table("vix_alerts")
public class VixAlert {

    @Id
    private Long id;

    @Column("vix_value")
    private Double vixValue;

    @Column("vix_level")
    private String vixLevel;

    @Column("alert_message")
    private String alertMessage;

    @Column("telegram_sent")
    private Boolean telegramSent;

    @Column("triggered_at")
    private Instant triggeredAt;

    /**
     * Creates a VixAlert from VixData when threshold is crossed.
     *
     * @param vixData the VixData that triggered the alert
     * @param message the alert message
     * @return a new VixAlert instance
     */
    public static VixAlert fromVixData(VixData vixData, String message) {
        return VixAlert.builder()
                .vixValue(vixData.getCurrentVix())
                .vixLevel(vixData.getVixLevel().name())
                .alertMessage(message)
                .telegramSent(false)
                .triggeredAt(Instant.now())
                .build();
    }
}
