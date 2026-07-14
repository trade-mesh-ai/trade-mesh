package com.trademesh.intelligence;

import com.trademesh.domain.NiftyDailyPrice;
import com.trademesh.domain.NiftyDailyPriceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;

/**
 * Service for managing NIFTY50 historical daily price data.
 * <p>
 * Responsibilities:
 * - Saving new daily prices (with idempotency check)
 * - Retrieving historical price data
 * - Querying the latest available date
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NiftyDailyPriceService {

    private final NiftyDailyPriceRepository repository;

    /**
     * Saves a daily price record if it does not already exist for the given trade date.
     * <p>
     * This method is idempotent: calling it multiple times with the same date
     * will only insert once.
     *
     * @param price the daily price record to save
     * @return Mono emitting the saved price record
     */
    public Mono<NiftyDailyPrice> saveIfNotExists(NiftyDailyPrice price) {
        return repository.existsByTradeDate(price.getTradeDate())
                .flatMap(exists -> {
                    if (exists) {
                        log.debug("Daily price already exists for date: {}", price.getTradeDate());
                        return repository.findByTradeDate(price.getTradeDate());
                    } else {
                        log.info("Saving new daily price for date: {}", price.getTradeDate());
                        return repository.save(price);
                    }
                });
    }

    /**
     * Retrieves the last N days of daily price data, ordered by date ascending.
     * <p>
     * Fetches the N most recent records and reverses them to ascending order.
     *
     * @param n the number of days to retrieve
     * @return Flux of daily prices ordered by date ascending
     */
    public Flux<NiftyDailyPrice> getLastNDays(int n) {
        log.debug("Fetching last {} days of daily prices", n);
        return repository.findAll()
                .sort((p1, p2) -> p2.getTradeDate().compareTo(p1.getTradeDate()))
                .take(n)
                .sort((p1, p2) -> p1.getTradeDate().compareTo(p2.getTradeDate()));
    }

    /**
     * Retrieves the most recent trade date available in the database.
     * <p>
     * Returns empty Mono if no records exist.
     *
     * @return Mono emitting the latest trade date, or empty if no data exists
     */
    public Mono<LocalDate> getLatestDate() {
        return repository.findTopByOrderByTradeDateDesc()
                .map(NiftyDailyPrice::getTradeDate)
                .doOnNext(date -> log.debug("Latest trade date in database: {}", date))
                .switchIfEmpty(Mono.defer(() -> {
                    log.debug("No daily price data found in database");
                    return Mono.empty();
                }));
    }

    /**
     * Calculates trend percentage over a specified lookback period.
     * <p>
     * Formula: (currentClose - closeNDaysAgo) / closeNDaysAgo * 100
     * <p>
     * Fetches N+1 days of price data, takes the oldest and newest close prices,
     * and computes the percentage change.
     *
     * @param lookbackDays the number of trading days to look back
     * @return Mono emitting the trend percentage, or empty if insufficient data
     */
    public Mono<BigDecimal> calculateTrendPct(int lookbackDays) {
        log.debug("Calculating trend percentage over {} days", lookbackDays);
        return getLastNDays(lookbackDays + 1)
                .collectList()
                .flatMap(prices -> {
                    if (prices.size() < 2) {
                        log.debug("Insufficient price data for trend calculation: {} days available", prices.size());
                        return Mono.empty();
                    }

                    NiftyDailyPrice oldestPrice = prices.get(0);
                    NiftyDailyPrice newestPrice = prices.get(prices.size() - 1);

                    BigDecimal oldestClose = oldestPrice.getClose();
                    BigDecimal newestClose = newestPrice.getClose();

                    if (oldestClose.compareTo(BigDecimal.ZERO) == 0) {
                        log.warn("Oldest close price is zero, cannot calculate trend");
                        return Mono.empty();
                    }

                    BigDecimal trendPct = newestClose.subtract(oldestClose)
                            .divide(oldestClose, 4, java.math.RoundingMode.HALF_UP)
                            .multiply(BigDecimal.valueOf(100))
                            .setScale(2, RoundingMode.HALF_UP);

                    log.debug("Trend calculation: oldest={}, newest={}, trendPct={}",
                            oldestClose, newestClose, trendPct);

                    return Mono.just(trendPct);
                });
    }
}
