package com.trademesh.trading;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Determines if NSE market is open today based on weekends and hardcoded holiday calendar.
 */
@Slf4j
@Service
public class MarketCalendarService {

    private static final Map<Integer, Set<LocalDate>> NSE_HOLIDAYS = new HashMap<>();

    static {
        // 2025 holidays
        Set<LocalDate> holidays2025 = new HashSet<>();
        holidays2025.add(LocalDate.of(2025, 1, 26));   // Republic Day
        holidays2025.add(LocalDate.of(2025, 2, 19));   // Chhatrapati Shivaji Maharaj Jayanti
        holidays2025.add(LocalDate.of(2025, 3, 14));   // Holi
        holidays2025.add(LocalDate.of(2025, 4, 14));   // Dr. Ambedkar Jayanti
        holidays2025.add(LocalDate.of(2025, 4, 18));   // Good Friday
        holidays2025.add(LocalDate.of(2025, 5, 1));    // Maharashtra Day
        holidays2025.add(LocalDate.of(2025, 8, 15));   // Independence Day
        holidays2025.add(LocalDate.of(2025, 10, 2));   // Gandhi Jayanti
        holidays2025.add(LocalDate.of(2025, 10, 24));  // Dussehra
        holidays2025.add(LocalDate.of(2025, 11, 5));   // Diwali Laxmi Pujan
        holidays2025.add(LocalDate.of(2025, 11, 14));  // Diwali Balipratipada
        holidays2025.add(LocalDate.of(2025, 12, 25));  // Christmas
        NSE_HOLIDAYS.put(2025, holidays2025);

        // 2026 holidays
        Set<LocalDate> holidays2026 = new HashSet<>();
        holidays2026.add(LocalDate.of(2026, 1, 26));   // Republic Day
        holidays2026.add(LocalDate.of(2026, 3, 30));   // Holi
        holidays2026.add(LocalDate.of(2026, 4, 2));    // Ram Navami
        holidays2026.add(LocalDate.of(2026, 4, 14));   // Dr. Ambedkar Jayanti
        holidays2026.add(LocalDate.of(2026, 5, 1));    // Maharashtra Day
        holidays2026.add(LocalDate.of(2026, 8, 15));   // Independence Day
        holidays2026.add(LocalDate.of(2026, 10, 2));   // Gandhi Jayanti
        holidays2026.add(LocalDate.of(2026, 10, 20));  // Dussehra
        NSE_HOLIDAYS.put(2026, holidays2026);
    }

    /**
     * Determines if a given date is a trading day (not a weekend or NSE holiday).
     *
     * @param date the date to check
     * @return true if the date is a trading day, false if it's a weekend or holiday
     */
    public boolean isTradingDay(LocalDate date) {
        DayOfWeek dayOfWeek = date.getDayOfWeek();

        // Check if weekend
        if (dayOfWeek == DayOfWeek.SATURDAY || dayOfWeek == DayOfWeek.SUNDAY) {
            return false;
        }

        // Check if holiday
        Set<LocalDate> yearHolidays = NSE_HOLIDAYS.get(date.getYear());
        if (yearHolidays == null) {
            log.warn("NSE holidays not configured for year {}, assuming {} is a trading day", date.getYear(), date);
            return true;
        }

        return !yearHolidays.contains(date);
    }

    /**
     * Counts the number of trading days strictly after 'from' up to and including 'to'.
     *
     * If from == to, returns 0 (no trading days strictly after from).
     * If to is before from, returns 0 (no valid range).
     *
     * @param from the start date (exclusive)
     * @param to the end date (inclusive)
     * @return the count of trading days in the range (from, to]
     */
    public int countTradingDays(LocalDate from, LocalDate to) {
        // If to is before or equal to from, return 0
        if (!to.isAfter(from)) {
            return 0;
        }

        int count = 0;
        LocalDate current = from.plusDays(1);
        while (!current.isAfter(to)) {
            if (isTradingDay(current)) {
                count++;
            }
            current = current.plusDays(1);
        }
        return count;
    }

    /**
     * Determines if the NSE market is open today based on weekends and holidays.
     *
     * @return Mono<Boolean> true if market is open, false otherwise
     */
    public Mono<Boolean> isMarketOpen() {
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Kolkata"));
        return Mono.just(isTradingDay(today));
    }
}
