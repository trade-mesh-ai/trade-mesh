package com.trademesh.intelligence.iv;

import com.trademesh.zerodha.options.OptionChain;
import com.trademesh.zerodha.options.OptionChainService;
import com.trademesh.zerodha.options.OptionStrike;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * Service for managing daily ATM (At-The-Money) implied volatility data.
 * Provides two method variants following the architecture convention:
 * - READ method (REST-facing): calculateAtmIv() — fetches and calculates, no save
 * - SAVE method (scheduler-facing): calculateAndSaveAtmIv() — calls read method then saves
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IvHistoryService {

    private final OptionChainService optionChainService;
    private final IvHistoryRepository repository;

    /**
     * READ method: Calculates ATM IV for a symbol without persisting.
     * Fetches the option chain, identifies the ATM strike (closest to spot price),
     * and calculates the average of call and put IVs.
     *
     * @param symbol the trading symbol (e.g., "NIFTY50")
     * @return Mono emitting the calculated IvHistory (not saved)
     */
    public Mono<IvHistory> calculateAtmIv(String symbol) {
        return optionChainService.fetchOptionChain(symbol)
                .flatMap(optionChain -> {
                    try {
                        IvHistory ivHistory = buildIvHistoryFromOptionChain(symbol, optionChain);
                        if (ivHistory == null) {
                            log.warn("Failed to calculate ATM IV for {}: no valid ATM strike found", symbol);
                            return Mono.empty();
                        }
                        return Mono.just(ivHistory);
                    } catch (Exception e) {
                        log.error("Error calculating ATM IV for {}: {}", symbol, e.getMessage(), e);
                        return Mono.error(e);
                    }
                })
                .doOnError(e -> log.error("Failed to fetch option chain for {}: {}", symbol, e.getMessage()));
    }

    /**
     * SAVE method: Calculates ATM IV and persists to database if not already recorded today.
     * Implements idempotency: if a record already exists for the symbol and today's date,
     * logs a debug message and returns empty.
     *
     * @param symbol the trading symbol (e.g., "NIFTY50")
     * @return Mono emitting the saved IvHistory, or empty if already recorded today
     */
    public Mono<IvHistory> calculateAndSaveAtmIv(String symbol) {
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Kolkata"));

        return calculateAtmIv(symbol)
                .flatMap(ivHistory -> {
                    // Check if IV already recorded for this symbol today
                    return repository.existsBySymbolAndTradeDate(symbol, today)
                            .flatMap(exists -> {
                                if (exists) {
                                    log.debug("IV already recorded for {} today, skipping", symbol);
                                    return Mono.empty();
                                } else {
                                    // Create a new record using factory method and populate fields
                                    IvHistory newRecord = IvHistory.createNew();
                                    newRecord.setSymbol(ivHistory.getSymbol());
                                    newRecord.setAtmStrike(ivHistory.getAtmStrike());
                                    newRecord.setAtmIv(ivHistory.getAtmIv());
                                    newRecord.setCallIv(ivHistory.getCallIv());
                                    newRecord.setPutIv(ivHistory.getPutIv());
                                    newRecord.setSpotPrice(ivHistory.getSpotPrice());
                                    newRecord.setExpiryDate(ivHistory.getExpiryDate());
                                    newRecord.setTradeDate(today);
                                    newRecord.setRecordedAt(LocalDateTime.now(ZoneId.of("Asia/Kolkata")));

                                    // Save to database
                                    return repository.save(newRecord)
                                            .doOnSuccess(saved -> log.info(
                                                    "ATM IV recorded for {}: atmIv={}%, callIv={}%, putIv={}%, atmStrike={}",
                                                    symbol,
                                                    saved.getAtmIv(),
                                                    saved.getCallIv(),
                                                    saved.getPutIv(),
                                                    saved.getAtmStrike()
                                            ));
                                }
                            });
                });
    }

    /**
     * Builds an IvHistory object from an OptionChain.
     * Finds the ATM strike (closest to spot price), extracts call and put IVs,
     * and calculates the average IV.
     *
     * @param symbol      the trading symbol
     * @param optionChain the option chain data
     * @return IvHistory object with calculated values, or null if no valid ATM strike found
     */
    private IvHistory buildIvHistoryFromOptionChain(String symbol, OptionChain optionChain) {
        if (optionChain == null || optionChain.getStrikes() == null || optionChain.getStrikes().isEmpty()) {
            log.warn("Option chain for {} is empty or null", symbol);
            return null;
        }

        BigDecimal spotPrice = optionChain.getSpotPrice();
        if (spotPrice == null || spotPrice.signum() <= 0) {
            log.warn("Invalid spot price for {}: {}", symbol, spotPrice);
            return null;
        }

        // Find ATM strike: the strike closest to spot price
        OptionStrike atmStrike = optionChain.getStrikes().stream()
                .min((s1, s2) -> {
                    BigDecimal diff1 = s1.getStrikePrice().subtract(spotPrice).abs();
                    BigDecimal diff2 = s2.getStrikePrice().subtract(spotPrice).abs();
                    return diff1.compareTo(diff2);
                })
                .orElse(null);

        if (atmStrike == null) {
            log.warn("No ATM strike found for {}", symbol);
            return null;
        }

        // Extract call and put IVs
        BigDecimal callIv = atmStrike.getCallIV();
        BigDecimal putIv = atmStrike.getPutIV();

        // Handle zero IVs: use whichever is non-zero
        BigDecimal effectiveCallIv = callIv;
        BigDecimal effectivePutIv = putIv;

        if (callIv == null || callIv.signum() == 0) {
            if (putIv == null || putIv.signum() == 0) {
                log.warn("Both call and put IVs are zero or null for {} at strike {}", symbol, atmStrike.getStrikePrice());
                return null;
            }
            effectiveCallIv = putIv;
        }

        if (putIv == null || putIv.signum() == 0) {
            effectivePutIv = callIv;
        }

        // Calculate ATM IV as average of call and put IVs
        BigDecimal atmIv = effectiveCallIv.add(effectivePutIv)
                .divide(BigDecimal.valueOf(2), 4, java.math.RoundingMode.HALF_UP);

        // Build and return IvHistory
        return IvHistory.builder()
                .symbol(symbol)
                .atmStrike(atmStrike.getStrikePrice())
                .atmIv(atmIv)
                .callIv(effectiveCallIv)
                .putIv(effectivePutIv)
                .spotPrice(spotPrice)
                .expiryDate(optionChain.getExpiryDate())
                .build();
    }
}
