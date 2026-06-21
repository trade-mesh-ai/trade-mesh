package com.trademeshai.zerodha.ticker;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

/**
 * Placeholder service for Zerodha Kite Ticker (WebSocket market data).
 * TODO: Integrate KiteTicker WebSocket client and publish ticks to the sink.
 */
@Slf4j
@Service
public class TickerService {

    private final Sinks.Many<TickData> tickSink = Sinks.many().multicast().onBackpressureBuffer();

    /**
     * Returns a hot Flux of live tick data for subscribers.
     */
    public Flux<TickData> tickStream() {
        return tickSink.asFlux();
    }

    /**
     * Called by the WebSocket listener to push a new tick.
     */
    public void onTick(TickData tick) {
        log.debug("Tick received: {}", tick);
        tickSink.tryEmitNext(tick);
    }
}
