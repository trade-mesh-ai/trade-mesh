/**
 * VIX (Volatility Index) monitoring and intelligence module.
 * 
 * <p>This package provides real-time VIX data fetching, caching, and alert management
 * for the trade-mesh platform. It integrates with Zerodha Kite API to fetch INDIA VIX
 * data and provides market intelligence for trading decisions.
 * 
 * <h2>Key Components</h2>
 * <ul>
 *   <li><strong>VixLevel</strong> - Enumeration of VIX volatility levels</li>
 *   <li><strong>VixData</strong> - Domain model for current VIX data</li>
 *   <li><strong>VixMonitorService</strong> - Fetches and caches VIX data from Zerodha API</li>
 *   <li><strong>VixAlertService</strong> - Handles VIX threshold alerts and Telegram notifications</li>
 *   <li><strong>VixScheduler</strong> - Scheduled tasks for VIX fetching and persistence</li>
 *   <li><strong>VixController</strong> - REST endpoint for VIX data retrieval</li>
 * </ul>
 * 
 * <h2>Database Entities</h2>
 * <ul>
 *   <li><strong>VixHistory</strong> - Historical VIX records (persisted every 15 minutes)</li>
 *   <li><strong>VixAlert</strong> - VIX alert events (triggered when VIX crosses threshold)</li>
 * </ul>
 * 
 * <h2>Configuration</h2>
 * <pre>
 * vix:
 *   fetch-interval-minutes: 5        # Fetch VIX every 5 minutes
 *   persist-interval-minutes: 15     # Persist to DB every 15 minutes
 *   alert-threshold: 20.0            # Alert when VIX >= 20
 * 
 * telegram:
 *   bot-token: ${TELEGRAM_BOT_TOKEN}
 *   chat-id: ${TELEGRAM_CHAT_ID}
 * </pre>
 * 
 * <h2>REST Endpoints</h2>
 * <ul>
 *   <li><strong>GET /intelligence/vix</strong> - Returns current VIX data</li>
 * </ul>
 * 
 * <h2>VIX Levels</h2>
 * <ul>
 *   <li>LOW: VIX < 13 (Low volatility, calm market)</li>
 *   <li>NORMAL: VIX 13-17 (Normal volatility)</li>
 *   <li>ELEVATED: VIX 17-20 (Elevated volatility, caution advised)</li>
 *   <li>HIGH: VIX 20-25 (High volatility, risk alert)</li>
 *   <li>EXTREME: VIX > 25 (Extreme volatility, market stress)</li>
 * </ul>
 */
package com.trademesh.intelligence;
