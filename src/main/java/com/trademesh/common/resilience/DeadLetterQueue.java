package com.trademesh.common.resilience;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * Dead-Letter Queue (DLQ) for handling failed orders.
 * <p>
 * Stores failed orders in Redis for manual review and recovery.
 * Each failed order is stored with:
 * - Order ID
 * - Failure reason
 * - Timestamp
 * - Retry count
 * <p>
 * Uses Redis list for persistence and easy retrieval.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DeadLetterQueue {

    private static final String DLQ_KEY = "dlq:failed_orders";
    private static final String DLQ_METADATA_PREFIX = "dlq:metadata:";

    private final ReactiveStringRedisTemplate redisTemplate;

    /**
     * Adds a failed order to the DLQ.
     *
     * @param orderId    order ID
     * @param reason     failure reason
     * @param retryCount number of retries attempted
     * @return Mono completing when order is added to DLQ
     */
    public Mono<Void> addFailedOrder(String orderId, String reason, int retryCount) {
        return Mono.fromRunnable(() -> {
            String metadata = String.format(
                    "orderId=%s|reason=%s|timestamp=%s|retryCount=%d",
                    orderId,
                    reason,
                    LocalDateTime.now(ZoneId.of("Asia/Kolkata")),
                    retryCount
            );

            redisTemplate.opsForList()
                    .leftPush(DLQ_KEY, metadata)
                    .doOnNext(size -> log.error("Failed order added to DLQ: {} (total: {})", orderId, size))
                    .subscribe();
        });
    }

    /**
     * Retrieves all failed orders from the DLQ.
     *
     * @return Flux emitting failed order metadata
     */
    public Flux<String> getFailedOrders() {
        return redisTemplate.opsForList()
                .range(DLQ_KEY, 0, -1)
                .doOnNext(order -> log.debug("Retrieved failed order from DLQ: {}", order));
    }

    /**
     * Retrieves a specific number of failed orders from the DLQ.
     *
     * @param count number of orders to retrieve
     * @return Flux emitting failed order metadata
     */
    public Flux<String> getFailedOrders(long count) {
        return redisTemplate.opsForList()
                .range(DLQ_KEY, 0, count - 1)
                .doOnNext(order -> log.debug("Retrieved failed order from DLQ: {}", order));
    }

    /**
     * Removes a failed order from the DLQ (after successful recovery).
     *
     * @param orderId order ID to remove
     * @return Mono completing when order is removed
     */
    public Mono<Void> removeFailedOrder(String orderId) {
        return Mono.fromRunnable(() -> {
            redisTemplate.opsForList()
                    .range(DLQ_KEY, 0, -1)
                    .filter(metadata -> metadata.contains("orderId=" + orderId))
                    .flatMap(metadata -> redisTemplate.opsForList().remove(DLQ_KEY, 1, metadata))
                    .doOnNext(count -> log.info("Removed failed order from DLQ: {} (removed: {})", orderId, count))
                    .subscribe();
        });
    }

    /**
     * Clears all failed orders from the DLQ.
     *
     * @return Mono completing when DLQ is cleared
     */
    public Mono<Void> clearFailedOrders() {
        return redisTemplate.delete(DLQ_KEY)
                .doOnNext(count -> log.info("Cleared DLQ: {} orders removed", count))
                .then();
    }

    /**
     * Gets the count of failed orders in the DLQ.
     *
     * @return Mono emitting the count
     */
    public Mono<Long> getFailedOrderCount() {
        return redisTemplate.opsForList()
                .size(DLQ_KEY)
                .doOnNext(count -> log.debug("DLQ size: {}", count));
    }

    /**
     * Checks if a specific order is in the DLQ.
     *
     * @param orderId order ID to check
     * @return Mono emitting true if order is in DLQ, false otherwise
     */
    public Mono<Boolean> isInDLQ(String orderId) {
        return redisTemplate.opsForList()
                .range(DLQ_KEY, 0, -1)
                .any(metadata -> metadata.contains("orderId=" + orderId))
                .doOnNext(found -> log.debug("Order {} in DLQ: {}", orderId, found));
    }
}
