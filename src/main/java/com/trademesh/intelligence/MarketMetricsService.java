package com.trademesh.intelligence;

import com.trademesh.domain.NiftyDailyPrice;
import com.trademesh.intelligence.iv.IvHistory;
import com.trademesh.intelligence.iv.IvHistoryRepository;
import com.trademesh.intelligence.iv.IvHistoryService;
import com.trademesh.zerodha.options.OptionChain;
import com.trademesh.zerodha.options.OptionChainService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

/**
 * Service for calculating market metrics including HV20, ATR14, IVR, and IV/HV ratio.
 * This is a READ-ONLY service that fetches data from existing sources and performs calculations.
 * No database writes, no schedulers.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MarketMetricsService {

    private final NiftyDailyPriceService niftyDailyPriceService;
    private final IvHistoryService ivHistoryService;
    private final IvHistoryRepository ivHistoryRepository;
    private final OptionChainService optionChainService;

    /**
     * Calculates comprehensive market metrics for a given symbol.
     * Fetches price data, IV data, and option chain in parallel, then computes HV20, ATR14, IVR, IV/HV ratio, and PCR.
     *
     * @param symbol trading symbol (e.g., "NIFTY50", "NIFTYBANK")
     * @return Mono emitting the calculated MarketMetrics
     */
    public Mono<MarketMetrics> calculateMetrics(String symbol) {
        log.debug("Starting market metrics calculation for {}", symbol);

        // Fetch last 21 days for HV20 and ATR14
        Mono<List<NiftyDailyPrice>> last21 =
                niftyDailyPriceService.getLastNDays(21).collectList();

        // Fetch last 60 days for ATR percentile
        Mono<List<NiftyDailyPrice>> last60 =
                niftyDailyPriceService.getLastNDays(60).collectList();

        // Fetch IV history for last 365 days for IVR calculation
        Mono<List<IvHistory>> ivHistList =
                ivHistoryRepository
                        .findBySymbolAndTradeDateBetweenOrderByTradeDateAsc(
                                symbol, LocalDate.now().minusDays(365), LocalDate.now())
                        .collectList();

        // Wrap currentIv in Optional so zip always emits even when empty
        Mono<Optional<IvHistory>> currentIv =
                ivHistoryService.calculateAtmIv(symbol)
                        .map(Optional::of)
                        .defaultIfEmpty(Optional.empty())
                        .onErrorResume(e -> {
                            log.warn("Could not calculate current ATM IV for {}: {}", symbol, e.getMessage());
                            return Mono.just(Optional.empty());
                        });

        // Fetch option chain for PCR calculation
        Mono<Optional<OptionChain>> optionChainMono =
                optionChainService.fetchOptionChain(symbol)
                        .map(Optional::of)
                        .defaultIfEmpty(Optional.empty())
                        .onErrorResume(e -> {
                            log.warn("Option chain unavailable for PCR: {}", e.getMessage());
                            return Mono.just(Optional.empty());
                        });

        // Combine all data sources in parallel
        return Mono.zip(last21, last60, ivHistList, currentIv, optionChainMono)
                .map(tuple -> buildMetrics(
                        symbol,
                        tuple.getT1(),  // last21
                        tuple.getT2(),  // last60
                        tuple.getT3(),  // ivHistList
                        tuple.getT4(),  // Optional<IvHistory>
                        tuple.getT5()   // Optional<OptionChain>
                ))
                .doOnSuccess(metrics -> log.info(
                        "Market metrics calculated for {}: HV20={}, ATR14={}, IVR={}, IV/HV={}, PCR={}",
                        symbol, metrics.getHv20(), metrics.getAtr14(), metrics.getIvr(), metrics.getIvHvRatio(), metrics.getPcr()
                ))
                .doOnError(e -> log.error("Error calculating metrics for {}: {}", symbol, e.getMessage(), e));
    }

    /**
     * Builds the MarketMetrics object from collected data.
     */
    private MarketMetrics buildMetrics(
            String symbol,
            List<NiftyDailyPrice> last21,
            List<NiftyDailyPrice> last60,
            List<IvHistory> ivHistList,
            Optional<IvHistory> currentIvOpt,
            Optional<OptionChain> chainOpt) {

        LocalDateTime calculatedAt = LocalDateTime.now(ZoneId.of("Asia/Kolkata"));

        // Get spot price from the most recent price data
        BigDecimal spotPrice = null;
        if (!last21.isEmpty()) {
            spotPrice = last21.get(last21.size() - 1).getClose();
        }

        // Calculate HV20
        BigDecimal hv20 = null;
        boolean hv20Available = false;
        if (last21.size() >= 21) {
            hv20 = calculateHV20(last21);
            hv20Available = true;
        } else {
            log.warn("Insufficient price data for HV20 calculation: {} days available, need 21", last21.size());
        }

        // Calculate ATR14
        BigDecimal atr14 = null;
        if (last21.size() >= 21) {
            atr14 = calculateATR14(last21);
        }

        // Calculate ATR Percentile
        BigDecimal atrPercentile = null;
        if (atr14 != null && last60.size() >= 2) {
            atrPercentile = calculateATRPercentile(last60, atr14);
        }

        // Get current ATM IV
        BigDecimal currentAtmIv = null;
        if (currentIvOpt.isPresent()) {
            currentAtmIv = currentIvOpt.get().getAtmIv();
        }

        // Calculate IVR
        BigDecimal ivr = null;
        boolean ivrAvailable = false;
        int ivHistoryDays = ivHistList.size();
        if (ivHistoryDays >= 30 && currentAtmIv != null) {
            ivr = calculateIVR(ivHistList, currentAtmIv);
            ivrAvailable = true;
        } else if (ivHistoryDays < 30) {
            log.warn("Insufficient IV history for IVR calculation: {} days available, need 30", ivHistoryDays);
        }

        // Calculate IV/HV Ratio
        BigDecimal ivHvRatio = null;
        if (currentAtmIv != null && hv20 != null && hv20.signum() > 0) {
            ivHvRatio = currentAtmIv.divide(hv20, 4, RoundingMode.HALF_UP);
        }

        // Calculate ATR as percentage of spot price
        BigDecimal atrPct = null;
        if (atr14 != null && spotPrice != null && spotPrice.compareTo(BigDecimal.ZERO) > 0) {
            atrPct = atr14.divide(spotPrice, 6, RoundingMode.HALF_UP)
                    .multiply(BigDecimal.valueOf(100))
                    .setScale(4, RoundingMode.HALF_UP);
        }

        // Calculate PCR (Put-Call Ratio) from option chain
        OptionChain chain = chainOpt.orElse(null);
        BigDecimal pcr = calculatePcr(chain);

        return MarketMetrics.builder()
                .symbol(symbol)
                .calculatedAt(calculatedAt)
                .spotPrice(spotPrice)
                .hv20(hv20)
                .atr14(atr14)
                .atrPercentile(atrPercentile)
                .currentAtmIv(currentAtmIv)
                .ivr(ivr)
                .ivHvRatio(ivHvRatio)
                .ivHistoryDays(ivHistoryDays)
                .hv20Available(hv20Available)
                .ivrAvailable(ivrAvailable)
                .atrPct(atrPct)
                .pcr(pcr)
                .build();
    }

    /**
     * Calculates 20-day historical volatility (HV20) from 21 days of price data.
     * Formula: HV20 = dailyStdDev * sqrt(252) * 100
     *
     * @param prices list of 21 NiftyDailyPrice records (oldest first)
     * @return HV20 as percentage, rounded to 4 decimal places
     */
    private BigDecimal calculateHV20(List<NiftyDailyPrice> prices) {
        log.debug("Calculating HV20 from {} price records", prices.size());

        // Calculate 20 log returns
        double[] logReturns = new double[20];
        for (int i = 1; i < prices.size(); i++) {
            BigDecimal prevClose = prices.get(i - 1).getClose();
            BigDecimal currClose = prices.get(i).getClose();

            if (prevClose.signum() <= 0 || currClose.signum() <= 0) {
                log.warn("Invalid price data for log return calculation at index {}", i);
                continue;
            }

            double ratio = currClose.doubleValue() / prevClose.doubleValue();
            logReturns[i - 1] = Math.log(ratio);
        }

        // Calculate mean of log returns
        double sum = 0;
        for (double logReturn : logReturns) {
            sum += logReturn;
        }
        double mean = sum / 20;

        // Calculate sample variance (divide by 19 for sample variance)
        double varianceSum = 0;
        for (double logReturn : logReturns) {
            varianceSum += Math.pow(logReturn - mean, 2);
        }
        double variance = varianceSum / 19;

        // Calculate daily standard deviation
        double dailyStdDev = Math.sqrt(variance);

        // Annualize: multiply by sqrt(252) and convert to percentage
        double hv20Value = dailyStdDev * Math.sqrt(252) * 100;

        log.debug("HV20 calculated: {}", hv20Value);
        return BigDecimal.valueOf(hv20Value).setScale(4, RoundingMode.HALF_UP);
    }

    /**
     * Calculates 14-day Average True Range (ATR14) from 21 days of price data.
     * Uses the last 14 True Range values.
     *
     * @param prices list of 21 NiftyDailyPrice records (oldest first)
     * @return ATR14 in points, rounded to 2 decimal places
     */
    private BigDecimal calculateATR14(List<NiftyDailyPrice> prices) {
        log.debug("Calculating ATR14 from {} price records", prices.size());

        // Calculate True Range for each day (from index 1 to 20)
        double[] trValues = new double[20];
        for (int i = 1; i < prices.size(); i++) {
            NiftyDailyPrice curr = prices.get(i);
            NiftyDailyPrice prev = prices.get(i - 1);

            BigDecimal high = curr.getHigh();
            BigDecimal low = curr.getLow();
            BigDecimal prevClose = prev.getClose();

            // TR = max(high - low, abs(high - prevClose), abs(low - prevClose))
            double tr1 = high.subtract(low).doubleValue();
            double tr2 = high.subtract(prevClose).abs().doubleValue();
            double tr3 = low.subtract(prevClose).abs().doubleValue();

            trValues[i - 1] = Math.max(tr1, Math.max(tr2, tr3));
        }

        // ATR14 = average of last 14 TR values
        double atrSum = 0;
        for (int i = 6; i < 20; i++) {  // Last 14 values (indices 6-19)
            atrSum += trValues[i];
        }
        double atr14Value = atrSum / 14;

        log.debug("ATR14 calculated: {}", atr14Value);
        return BigDecimal.valueOf(atr14Value).setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * Calculates ATR percentile from 60 days of price data.
     * Percentile = (count of TR < current ATR14) / total TR values * 100
     *
     * @param prices list of 60 NiftyDailyPrice records (oldest first)
     * @param atr14  current ATR14 value
     * @return ATR percentile (0-100), rounded to 2 decimal places
     */
    private BigDecimal calculateATRPercentile(List<NiftyDailyPrice> prices, BigDecimal atr14) {
        log.debug("Calculating ATR percentile from {} price records", prices.size());

        // Calculate True Range for each day (from index 1 onwards)
        double[] trValues = new double[prices.size() - 1];
        for (int i = 1; i < prices.size(); i++) {
            NiftyDailyPrice curr = prices.get(i);
            NiftyDailyPrice prev = prices.get(i - 1);

            BigDecimal high = curr.getHigh();
            BigDecimal low = curr.getLow();
            BigDecimal prevClose = prev.getClose();

            // TR = max(high - low, abs(high - prevClose), abs(low - prevClose))
            double tr1 = high.subtract(low).doubleValue();
            double tr2 = high.subtract(prevClose).abs().doubleValue();
            double tr3 = low.subtract(prevClose).abs().doubleValue();

            trValues[i - 1] = Math.max(tr1, Math.max(tr2, tr3));
        }

        // Count how many TR values are less than current ATR14
        double atr14Double = atr14.doubleValue();
        int countLessThanATR = 0;
        for (double tr : trValues) {
            if (tr < atr14Double) {
                countLessThanATR++;
            }
        }

        // Calculate percentile
        double percentile = (double) countLessThanATR / trValues.length * 100;

        log.debug("ATR percentile calculated: {}", percentile);
        return BigDecimal.valueOf(percentile).setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * Calculates IV Rank (IVR) from IV history.
     * IVR = (currentIV - minIV) / (maxIV - minIV) * 100
     * If maxIV == minIV, returns 50 to avoid division by zero.
     *
     * @param ivHistList   list of IvHistory records (at least 30 days)
     * @param currentAtmIv current ATM IV value
     * @return IVR (0-100), rounded to 2 decimal places
     */
    private BigDecimal calculateIVR(List<IvHistory> ivHistList, BigDecimal currentAtmIv) {
        log.debug("Calculating IVR from {} IV history records", ivHistList.size());

        // Find min and max IV
        BigDecimal minIv = ivHistList.get(0).getAtmIv();
        BigDecimal maxIv = ivHistList.get(0).getAtmIv();

        for (IvHistory iv : ivHistList) {
            BigDecimal atmIv = iv.getAtmIv();
            if (atmIv.compareTo(minIv) < 0) {
                minIv = atmIv;
            }
            if (atmIv.compareTo(maxIv) > 0) {
                maxIv = atmIv;
            }
        }

        // Calculate IVR
        BigDecimal ivrValue;
        if (maxIv.compareTo(minIv) == 0) {
            // Avoid division by zero
            ivrValue = BigDecimal.valueOf(50);
            log.debug("IV range is zero (all values equal), returning IVR=50");
        } else {
            BigDecimal numerator = currentAtmIv.subtract(minIv);
            BigDecimal denominator = maxIv.subtract(minIv);
            ivrValue = numerator.divide(denominator, 4, RoundingMode.HALF_UP)
                    .multiply(BigDecimal.valueOf(100));
        }

        log.debug("IVR calculated: {} (minIV={}, maxIV={}, currentIV={})", ivrValue, minIv, maxIv, currentAtmIv);
        return ivrValue.setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * Calculates Put-Call Ratio (PCR) from option chain data.
     * PCR = total put OI / total call OI across all strikes.
     * Balanced range: 0.8 - 1.2
     *
     * @param chain the option chain to analyze
     * @return PCR value rounded to 4 decimal places, or null if chain unavailable
     */
    private BigDecimal calculatePcr(OptionChain chain) {
        if (chain == null || chain.getStrikes() == null ||
                chain.getStrikes().isEmpty()) {
            return null;
        }

        long totalCallOI = chain.getStrikes().stream()
                .mapToLong(s -> s.getCallOI())
                .sum();

        long totalPutOI = chain.getStrikes().stream()
                .mapToLong(s -> s.getPutOI())
                .sum();

        if (totalCallOI == 0) {
            log.warn("Total call OI is zero, cannot calculate PCR");
            return null;
        }

        BigDecimal pcr = BigDecimal.valueOf(totalPutOI)
                .divide(BigDecimal.valueOf(totalCallOI), 4, RoundingMode.HALF_UP);

        log.debug("PCR calculated: putOI={}, callOI={}, pcr={}",
                totalPutOI, totalCallOI, pcr);

        return pcr;
    }
}
