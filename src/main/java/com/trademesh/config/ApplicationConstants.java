package com.trademesh.config;

import java.time.Duration;
import java.time.ZoneId;

public class ApplicationConstants {
    // ─── INTELLIGENCE Constants ─────────────────────────────────────────────────────────────────
    public static final String REDIS_KEY_INTELLIGENCE_PREFIX = "market:intelligence:";
    public static final Duration CACHE_INTELLIGENCE_TTL = Duration.ofMinutes(5);
    public static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    // ─── MAX_PAIN Constants ─────────────────────────────────────────────────────────────────
    public static final String REDIS_KEY_MAX_PAIN_PREFIX = "market:maxpain:";
    public static final Duration CACHE_MAX_PAIN_TTL = Duration.ofMinutes(15);

}
