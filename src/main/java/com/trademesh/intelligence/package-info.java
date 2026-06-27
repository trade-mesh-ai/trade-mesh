/**
 * Market intelligence and analysis module for the trade-mesh platform.
 * <p>
 * This package provides core market intelligence services, including:
 * - Real-time market condition analysis
 * - Trading signal generation
 * - VIX (Volatility Index) monitoring (see vix sub-package)
 * - Max Pain calculation (see maxpain sub-package)
 * - Open Interest analysis (see oi sub-package)
 * <p>
 * Key Components:
 * - MarketIntelligence: Aggregated market data and analysis
 * - MarketIntelligenceService: Core service for market analysis
 * - MarketIntelligenceController: REST endpoints for market intelligence
 * - MarketCondition: Enumeration of market conditions
 * - TradingSignal: Domain model for trading signals
 * - SignalService: Service for signal generation and management
 * - IntelligenceScheduler: Scheduled tasks for intelligence gathering
 * <p>
 * Sub-packages:
 * - vix: VIX monitoring, alerting, and historical tracking
 * - maxpain: Max Pain calculation and analysis
 * - oi: Open Interest analysis and market bias detection
 * <p>
 * REST endpoints:
 * - GET /intelligence/market - Returns current market intelligence
 * - GET /intelligence/vix - Returns current VIX data (see vix sub-package)
 * - GET /intelligence/maxpain - Returns max pain analysis (see maxpain sub-package)
 * - GET /intelligence/oi - Returns OI analysis (see oi sub-package)
 */
package com.trademesh.intelligence;
