package com.trademesh.intelligence;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import reactor.core.scheduler.Schedulers;

import java.time.Instant;

/**
 * Scheduler for VIX monitoring tasks.
 *
 * <p>Responsibilities:
 * <ul>
 *   <li>Fetches VIX data every 5 minutes (configurable)</li>
 *   <li>Persists VIX data to database every 15 minutes (configurable)</li>
 *   <li>Checks for VIX threshold crossings and triggers alerts</li>
 * </ul>
 */
@Slf4j
@Component
@EnableScheduling
@RequiredArgsConstructor
public class VixScheduler {

    // ─── Configuration ────────────────────────────────────────────────────────────
    @Value("${vix.fetch-interval-minutes:5}")
    private Integer fetchIntervalMinutes;

    @Value("${vix.persist-interval-minutes:15}")
    private Integer persistIntervalMinutes;

    // ─── Dependencies ─────────────────────────────────────────────────────────────
    private final VixMonitorService vixMonitorService;
    private final VixAlertService vixAlertService;
    private final VixHistoryRepository vixHistoryRepository;

    // ─── State ────────────────────────────────────────────────────────────────────
    private VixData lastFetchedVixData;
    private Instant lastPersistTime;

    // ─── Scheduled Tasks ──────────────────────────────────────────────────────────

    /**
     * Fetches VIX data at the configured interval (default: every 5 minutes).
     *
     * <p>This task:
     * <ol>
     *   <li>Fetches current VIX from Zerodha API (or Redis cache)</li>
     *   <li>Checks if VIX has crossed the alert threshold</li>
     *   <li>Triggers alerts if threshold is crossed</li>
     *   <li>Stores the latest data in memory for persistence checks</li>
     * </ol>
     */
    @Scheduled(fixedRateString = "${vix.fetch-interval-minutes:5}", timeUnit = java.util.concurrent.TimeUnit.MINUTES)
    public void fetchVixData() {
        log.debug("Starting VIX fetch task");

        vixMonitorService.fetchCurrentVix()
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe(
                        vixData -> {
                            log.info("VIX fetched: value={}, level={}, safeToTrade={}",
                                    vixData.getCurrentVix(),
                                    vixData.getVixLevel(),
                                    vixData.isSafeToTrade());

                            // Store in memory for persistence checks
                            lastFetchedVixData = vixData;

                            // Check for threshold crossing and trigger alerts
                            vixAlertService.processVixAlert(vixData)
                                    .subscribeOn(Schedulers.boundedElastic())
                                    .subscribe(
                                            alert -> log.warn("VIX alert processed: {}", alert.getAlertMessage()),
                                            error -> log.error("Error processing VIX alert: {}", error.getMessage()),
                                            () -> log.debug("VIX alert processing completed")
                                    );
                        },
                        error -> log.error("Error fetching VIX data: {}", error.getMessage(), error),
                        () -> log.debug("VIX fetch completed")
                );
    }

    /**
     * Persists VIX data to the database at the configured interval (default: every 15 minutes).
     *
     * <p>This task:
     * <ol>
     *   <li>Checks if VIX data has been fetched since last persistence</li>
     *   <li>Creates a VixHistory record from the latest VIX data</li>
     *   <li>Persists to the vix_history table</li>
     * </ol>
     */
    @Scheduled(fixedRateString = "${vix.persist-interval-minutes:15}", timeUnit = java.util.concurrent.TimeUnit.MINUTES)
    public void persistVixData() {
        log.debug("Starting VIX persistence task");

        if (lastFetchedVixData == null) {
            log.warn("No VIX data available for persistence. Skipping.");
            return;
        }

        VixHistory vixHistory = VixHistory.fromVixData(lastFetchedVixData);

        vixHistoryRepository.save(vixHistory)
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe(
                        savedHistory -> {
                            log.info("VIX data persisted to database: id={}, value={}, level={}",
                                    savedHistory.getId(),
                                    savedHistory.getVixValue(),
                                    savedHistory.getVixLevel());
                            lastPersistTime = Instant.now();
                        },
                        error -> log.error("Error persisting VIX data: {}", error.getMessage(), error),
                        () -> log.debug("VIX persistence completed")
                );
    }

    /**
     * Logs VIX statistics periodically (for monitoring purposes).
     * Runs every 30 minutes.
     */
    @Scheduled(fixedRate = 30, timeUnit = java.util.concurrent.TimeUnit.MINUTES)
    public void logVixStatistics() {
        if (lastFetchedVixData == null) {
            log.info("VIX Statistics: No data available yet");
            return;
        }

        log.info("VIX Statistics - Current: {}, Level: {}, SafeToTrade: {}, LastFetch: {}",
                lastFetchedVixData.getCurrentVix(),
                lastFetchedVixData.getVixLevel(),
                lastFetchedVixData.isSafeToTrade(),
                lastFetchedVixData.getFetchedAt());

        // Count records in database
        vixHistoryRepository.countRecords()
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe(
                        count -> log.info("Total VIX history records in database: {}", count),
                        error -> log.error("Error counting VIX history records: {}", error.getMessage())
                );
    }
}
