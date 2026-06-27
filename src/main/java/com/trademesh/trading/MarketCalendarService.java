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

    public Mono<Boolean> isMarketOpen() {
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Kolkata"));
        DayOfWeek dayOfWeek = today.getDayOfWeek();

        // Check if weekend
        if (dayOfWeek == DayOfWeek.SATURDAY || dayOfWeek == DayOfWeek.SUNDAY) {
            return Mono.just(false);
        }

        // Check if holiday
        Set<LocalDate> yearHolidays = NSE_HOLIDAYS.get(today.getYear());
        if (yearHolidays == null) {
            log.warn("NSE holidays not configured for year {}, assuming market is open", today.getYear());
            return Mono.just(true);
        }

        if (yearHolidays.contains(today)) {
            return Mono.just(false);
        }

        return Mono.just(true);
    }
}
