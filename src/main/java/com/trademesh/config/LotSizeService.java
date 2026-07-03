package com.trademesh.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * Centralized service for managing lot sizes per trading symbol.
 * Provides a single source of truth for lot size configuration.
 * <p>
 * Lot sizes are fetched from trading.lot-sizes configuration with fallback to 1.
 * This ensures all components use consistent lot size values.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LotSizeService {

    @Value("#{${trading.lot-sizes:{NIFTY50: 65, NIFTYBANK: 30}}}")
    private Map<String, Integer> lotSizes;

    /**
     * Gets the lot size for a given symbol from configuration.
     * Falls back to 1 if symbol is not configured.
     *
     * @param symbol trading symbol (e.g., "NIFTY50", "NIFTYBANK")
     * @return lot size for the symbol
     */
    public int getLotSize(String symbol) {
        if (symbol == null) {
            log.warn("Symbol is null, returning default lot size 1");
            return 1;
        }
        return lotSizes.getOrDefault(symbol, 1);
    }
}
