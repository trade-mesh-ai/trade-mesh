package com.trademeshai.strategy;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * Placeholder strategy execution engine.
 * TODO: Load active strategies from DB, evaluate signals, and trigger execution pipeline.
 */
@Slf4j
@Component
public class StrategyEngine {

    /**
     * Evaluates all active strategies and emits execution decisions.
     */
    public Mono<Void> evaluate() {
        log.info("Evaluating active strategies...");
        // TODO: fetch active strategies, run each strategy's logic
        return Mono.empty();
    }
}
