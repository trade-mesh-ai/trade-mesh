package com.trademesh.config;

import java.time.Duration;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Map;

public class ApplicationConstants {

    public static final long TOKEN_NIFTY_50 = 256265L;
    public static final long TOKEN_NIFTY_BANK = 260105L;
    public static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    public static final LocalTime MARKET_OPEN = LocalTime.of(9, 15);
    public static final LocalTime MARKET_CLOSE = LocalTime.of(15, 30);
    public static final String[] SYMBOLS = {"NIFTY50", "NIFTYBANK"};

    public static final Map<Long, String> TOKEN_SYMBOL_MAP = Map.of(
            TOKEN_NIFTY_50, "NIFTY50",
            TOKEN_NIFTY_BANK, "NIFTYBANK"
    );

    public static final Map<String, Long> SYMBOL_TOKEN_MAP = Map.of(
            "NIFTY50", TOKEN_NIFTY_50,
            "NIFTYBANK", TOKEN_NIFTY_BANK
    );


    // ─── INTELLIGENCE Constants ─────────────────────────────────────────────────────────────────
    public static final String REDIS_KEY_INTELLIGENCE_PREFIX = "market:intelligence:";
    public static final Duration CACHE_INTELLIGENCE_TTL = Duration.ofMinutes(5);

    // ─── MAX_PAIN Constants ─────────────────────────────────────────────────────────────────
    public static final String REDIS_KEY_MAX_PAIN_PREFIX = "market:maxpain:";
    public static final Duration CACHE_MAX_PAIN_TTL = Duration.ofMinutes(15);

    // ─── OPTION CHAIN Constants ─────────────────────────────────────────────────────────────────
    public static final String REDIS_KEY_OPTION_CHAIN_PREFIX = "optionchain:";
    public static final Duration CACHE_OPTION_CHAIN_TTL = Duration.ofMinutes(3);

    // ─── ZERODHA TICKER SERVICE Constants ─────────────────────────────────────────────────────────────────
    public static final String PRICE_KEY_PREFIX = "price:";
    public static final Duration CACHE_PRICE_TTL = Duration.ofSeconds(60);


}
