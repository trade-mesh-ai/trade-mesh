/**
 * VIX (Volatility Index) monitoring and intelligence module.
 * <p>
 * This package provides real-time VIX data fetching, caching, and alert management
 * for the trade-mesh platform. It integrates with Zerodha Kite API to fetch INDIA VIX
 * data and provides market intelligence for trading decisions.
 * <p>
 * Key Components:
 * - VixLevel: Enumeration of VIX volatility levels
 * - VixData: Domain model for current VIX data
 * - VixMonitorService: Fetches and caches VIX data from Zerodha API
 * - VixAlertService: Handles VIX threshold alerts and Telegram notifications
 * - VixScheduler: Scheduled tasks for VIX fetching and persistence
 * - VixController: REST endpoint for VIX data retrieval
 * <p>
 * Database Entities:
 * - VixHistory: Historical VIX records (persisted every 15 minutes)
 * - VixAlert: VIX alert events (triggered when VIX crosses threshold)
 * <p>
 * Configuration example:
 * vix.fetch-interval-minutes: 5        # Fetch VIX every 5 minutes
 * vix.persist-interval-minutes: 15     # Persist to DB every 15 minutes
 * vix.alert-threshold: 20.0            # Alert when VIX >= 20
 * <p>
 * REST endpoints:
 * - GET /intelligence/vix - Returns current VIX data
 * <p>
 * VIX Levels:
 * - LOW: VIX < 13 (Low volatility, calm market)
 * - NORMAL: VIX 13-17 (Normal volatility)
 * - ELEVATED: VIX 17-20 (Elevated volatility, caution advised)
 * - HIGH: VIX 20-25 (High volatility, risk alert)
 * - EXTREME: VIX > 25 (Extreme volatility, market stress)
 */
package com.trademesh.intelligence.vix;
