package com.trademesh.execution;

import com.trademesh.strategy.TradeLeg;
import com.trademesh.strategy.enums.Action;
import com.trademesh.strategy.enums.OptionType;
import com.trademesh.zerodha.options.OptionChain;
import com.trademesh.zerodha.options.OptionStrike;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.test.StepVerifier;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Unit tests for P&L netting fix in PaperTradingEngine.
 * Tests the signed logic for calculateSpreadCurrentValue(), calculateIronCondorCurrentValue(),
 * and calculateDefaultCurrentValue() methods.
 * <p>
 * The fix ensures that:
 * - SELL legs: ADD their LTP (cost to buy back the short position)
 * - BUY legs: SUBTRACT their LTP (value received when selling back the long position)
 * <p>
 * This prevents the buggy behavior of summing all LTPs without considering leg direction.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PaperTradingEngine P&L Netting Fix Tests")
class PaperTradingEngineCurrentValueTest {

    @Mock
    private PaperTradeRepository paperTradeRepository;

    @InjectMocks
    private PaperTradingEngine paperTradingEngine;

    private OptionChain optionChain;

    @BeforeEach
    void setUp() {
        optionChain = OptionChain.builder()
                .symbol("NIFTY50")
                .expiryDate(LocalDate.of(2026, 7, 9))
                .build();
    }

    // ─── Test Case 1: calculateSpreadCurrentValue() - 2-leg spread (SELL + BUY) ───
    @Test
    @DisplayName("calculateSpreadCurrentValue: 2-leg spread with SELL @ 50 and BUY @ 20 should return 30 (signed), not 70 (buggy sum)")
    void testCalculateSpreadCurrentValue_TwoLegSpread_SignedLogic() {
        // Setup: SELL leg at 50 points, BUY leg at 20 points
        // Buggy result (sum): 50 + 20 = 70
        // Correct result (signed): 50 - 20 = 30
        OptionStrike strike1 = OptionStrike.builder()
                .strikePrice(BigDecimal.valueOf(24000))
                .callLTP(BigDecimal.valueOf(50.0))
                .build();

        OptionStrike strike2 = OptionStrike.builder()
                .strikePrice(BigDecimal.valueOf(24100))
                .callLTP(BigDecimal.valueOf(20.0))
                .build();

        optionChain.setStrikes(List.of(strike1, strike2));

        TradeLeg sellLeg = TradeLeg.builder()
                .action(Action.SELL)
                .optionType(OptionType.CE)
                .strikePrice(24000.0)
                .limitPrice(50.0)
                .quantity(1)
                .build();

        TradeLeg buyLeg = TradeLeg.builder()
                .action(Action.BUY)
                .optionType(OptionType.CE)
                .strikePrice(24100.0)
                .limitPrice(20.0)
                .quantity(1)
                .build();

        List<TradeLeg> legs = List.of(sellLeg, buyLeg);

        // Act & Assert
        StepVerifier.create(paperTradingEngine.calculateSpreadCurrentValue(optionChain, legs))
                .expectNext(30.0)  // Correct signed result: 50 - 20 = 30
                .verifyComplete();
    }

    // ─── Test Case 2: calculateIronCondorCurrentValue() - 4-leg iron condor (2 SELL, 2 BUY) ───
    @Test
    @DisplayName("calculateIronCondorCurrentValue: 4-leg iron condor with signed logic should return 50, not 100 (buggy sum)")
    void testCalculateIronCondorCurrentValue_FourLegIronCondor_SignedLogic() {
        // Setup: 4-leg iron condor
        // SELL call @ 40, SELL put @ 35, BUY call @ 15, BUY put @ 10
        // Buggy result (sum): 40 + 35 + 15 + 10 = 100
        // Correct result (signed): 40 + 35 - 15 - 10 = 50
        OptionStrike shortCallStrike = OptionStrike.builder()
                .strikePrice(BigDecimal.valueOf(24100))
                .callLTP(BigDecimal.valueOf(40.0))
                .build();

        OptionStrike shortPutStrike = OptionStrike.builder()
                .strikePrice(BigDecimal.valueOf(23900))
                .putLTP(BigDecimal.valueOf(35.0))
                .build();

        OptionStrike longCallStrike = OptionStrike.builder()
                .strikePrice(BigDecimal.valueOf(24200))
                .callLTP(BigDecimal.valueOf(15.0))
                .build();

        OptionStrike longPutStrike = OptionStrike.builder()
                .strikePrice(BigDecimal.valueOf(23800))
                .putLTP(BigDecimal.valueOf(10.0))
                .build();

        optionChain.setStrikes(List.of(shortCallStrike, shortPutStrike, longCallStrike, longPutStrike));

        TradeLeg sellCallLeg = TradeLeg.builder()
                .action(Action.SELL)
                .optionType(OptionType.CE)
                .strikePrice(24100.0)
                .limitPrice(40.0)
                .quantity(1)
                .build();

        TradeLeg sellPutLeg = TradeLeg.builder()
                .action(Action.SELL)
                .optionType(OptionType.PE)
                .strikePrice(23900.0)
                .limitPrice(35.0)
                .quantity(1)
                .build();

        TradeLeg buyCallLeg = TradeLeg.builder()
                .action(Action.BUY)
                .optionType(OptionType.CE)
                .strikePrice(24200.0)
                .limitPrice(15.0)
                .quantity(1)
                .build();

        TradeLeg buyPutLeg = TradeLeg.builder()
                .action(Action.BUY)
                .optionType(OptionType.PE)
                .strikePrice(23800.0)
                .limitPrice(10.0)
                .quantity(1)
                .build();

        List<TradeLeg> legs = List.of(sellCallLeg, sellPutLeg, buyCallLeg, buyPutLeg);

        // Act & Assert
        StepVerifier.create(paperTradingEngine.calculateIronCondorCurrentValue(optionChain, legs))
                .expectNext(50.0)  // Correct signed result: 40 + 35 - 15 - 10 = 50
                .verifyComplete();
    }

    // ─── Test Case 3: calculateDefaultCurrentValue() - minimal case (SELL + BUY) ───
    @Test
    @DisplayName("calculateDefaultCurrentValue: minimal case with SELL @ 60 and BUY @ 25 should return 35 (signed), not 85 (buggy sum)")
    void testCalculateDefaultCurrentValue_MinimalCase_SignedLogic() {
        // Setup: SELL leg at 60 points, BUY leg at 25 points
        // Buggy result (sum): 60 + 25 = 85
        // Correct result (signed): 60 - 25 = 35
        OptionStrike strike1 = OptionStrike.builder()
                .strikePrice(BigDecimal.valueOf(24000))
                .putLTP(BigDecimal.valueOf(60.0))
                .build();

        OptionStrike strike2 = OptionStrike.builder()
                .strikePrice(BigDecimal.valueOf(24100))
                .putLTP(BigDecimal.valueOf(25.0))
                .build();

        optionChain.setStrikes(List.of(strike1, strike2));

        TradeLeg sellLeg = TradeLeg.builder()
                .action(Action.SELL)
                .optionType(OptionType.PE)
                .strikePrice(24000.0)
                .limitPrice(60.0)
                .quantity(1)
                .build();

        TradeLeg buyLeg = TradeLeg.builder()
                .action(Action.BUY)
                .optionType(OptionType.PE)
                .strikePrice(24100.0)
                .limitPrice(25.0)
                .quantity(1)
                .build();

        List<TradeLeg> legs = List.of(sellLeg, buyLeg);

        // Act & Assert
        StepVerifier.create(paperTradingEngine.calculateDefaultCurrentValue(optionChain, legs))
                .expectNext(35.0)  // Correct signed result: 60 - 25 = 35
                .verifyComplete();
    }

    // ─── Test Case 4: calculateSpreadCurrentValue() - edge case with null legs ───
    @Test
    @DisplayName("calculateSpreadCurrentValue: null legs should return 0.0")
    void testCalculateSpreadCurrentValue_NullLegs_ReturnsZero() {
        // Act & Assert
        StepVerifier.create(paperTradingEngine.calculateSpreadCurrentValue(optionChain, null))
                .expectNext(0.0)
                .verifyComplete();
    }

    // ─── Test Case 5: calculateSpreadCurrentValue() - edge case with empty legs ───
    @Test
    @DisplayName("calculateSpreadCurrentValue: empty legs should return 0.0")
    void testCalculateSpreadCurrentValue_EmptyLegs_ReturnsZero() {
        // Act & Assert
        StepVerifier.create(paperTradingEngine.calculateSpreadCurrentValue(optionChain, List.of()))
                .expectNext(0.0)
                .verifyComplete();
    }

    // ─── Test Case 6: calculateIronCondorCurrentValue() - edge case with null legs ───
    @Test
    @DisplayName("calculateIronCondorCurrentValue: null legs should return 0.0")
    void testCalculateIronCondorCurrentValue_NullLegs_ReturnsZero() {
        // Act & Assert
        StepVerifier.create(paperTradingEngine.calculateIronCondorCurrentValue(optionChain, null))
                .expectNext(0.0)
                .verifyComplete();
    }

    // ─── Test Case 7: calculateDefaultCurrentValue() - edge case with null legs ───
    @Test
    @DisplayName("calculateDefaultCurrentValue: null legs should return 0.0")
    void testCalculateDefaultCurrentValue_NullLegs_ReturnsZero() {
        // Act & Assert
        StepVerifier.create(paperTradingEngine.calculateDefaultCurrentValue(optionChain, null))
                .expectNext(0.0)
                .verifyComplete();
    }

    // ─── Test Case 8: calculateSpreadCurrentValue() - single SELL leg ───
    @Test
    @DisplayName("calculateSpreadCurrentValue: single SELL leg should return its LTP")
    void testCalculateSpreadCurrentValue_SingleSellLeg_ReturnsLtp() {
        // Setup: Single SELL leg at 45 points
        OptionStrike strike = OptionStrike.builder()
                .strikePrice(BigDecimal.valueOf(24000))
                .callLTP(BigDecimal.valueOf(45.0))
                .build();

        optionChain.setStrikes(List.of(strike));

        TradeLeg sellLeg = TradeLeg.builder()
                .action(Action.SELL)
                .optionType(OptionType.CE)
                .strikePrice(24000.0)
                .limitPrice(45.0)
                .quantity(1)
                .build();

        // Act & Assert
        StepVerifier.create(paperTradingEngine.calculateSpreadCurrentValue(optionChain, List.of(sellLeg)))
                .expectNext(45.0)
                .verifyComplete();
    }

    // ─── Test Case 9: calculateSpreadCurrentValue() - single BUY leg ───
    @Test
    @DisplayName("calculateSpreadCurrentValue: single BUY leg should return negative of its LTP")
    void testCalculateSpreadCurrentValue_SingleBuyLeg_ReturnsNegativeLtp() {
        // Setup: Single BUY leg at 30 points
        OptionStrike strike = OptionStrike.builder()
                .strikePrice(BigDecimal.valueOf(24000))
                .callLTP(BigDecimal.valueOf(30.0))
                .build();

        optionChain.setStrikes(List.of(strike));

        TradeLeg buyLeg = TradeLeg.builder()
                .action(Action.BUY)
                .optionType(OptionType.CE)
                .strikePrice(24000.0)
                .limitPrice(30.0)
                .quantity(1)
                .build();

        // Act & Assert
        StepVerifier.create(paperTradingEngine.calculateSpreadCurrentValue(optionChain, List.of(buyLeg)))
                .expectNext(-30.0)
                .verifyComplete();
    }

    // ─── Test Case 10: calculateIronCondorCurrentValue() - all SELL legs ───
    @Test
    @DisplayName("calculateIronCondorCurrentValue: all SELL legs should sum their LTPs")
    void testCalculateIronCondorCurrentValue_AllSellLegs_SumsLtps() {
        // Setup: 2 SELL legs at 40 and 35 points
        OptionStrike strike1 = OptionStrike.builder()
                .strikePrice(BigDecimal.valueOf(24100))
                .callLTP(BigDecimal.valueOf(40.0))
                .build();

        OptionStrike strike2 = OptionStrike.builder()
                .strikePrice(BigDecimal.valueOf(23900))
                .putLTP(BigDecimal.valueOf(35.0))
                .build();

        optionChain.setStrikes(List.of(strike1, strike2));

        TradeLeg sellCallLeg = TradeLeg.builder()
                .action(Action.SELL)
                .optionType(OptionType.CE)
                .strikePrice(24100.0)
                .limitPrice(40.0)
                .quantity(1)
                .build();

        TradeLeg sellPutLeg = TradeLeg.builder()
                .action(Action.SELL)
                .optionType(OptionType.PE)
                .strikePrice(23900.0)
                .limitPrice(35.0)
                .quantity(1)
                .build();

        // Act & Assert
        StepVerifier.create(paperTradingEngine.calculateIronCondorCurrentValue(optionChain, List.of(sellCallLeg, sellPutLeg)))
                .expectNext(75.0)  // 40 + 35 = 75
                .verifyComplete();
    }

    // ─── Test Case 11: calculateIronCondorCurrentValue() - all BUY legs ───
    @Test
    @DisplayName("calculateIronCondorCurrentValue: all BUY legs should negate their LTPs")
    void testCalculateIronCondorCurrentValue_AllBuyLegs_NegatesLtps() {
        // Setup: 2 BUY legs at 15 and 10 points
        OptionStrike strike1 = OptionStrike.builder()
                .strikePrice(BigDecimal.valueOf(24200))
                .callLTP(BigDecimal.valueOf(15.0))
                .build();

        OptionStrike strike2 = OptionStrike.builder()
                .strikePrice(BigDecimal.valueOf(23800))
                .putLTP(BigDecimal.valueOf(10.0))
                .build();

        optionChain.setStrikes(List.of(strike1, strike2));

        TradeLeg buyCallLeg = TradeLeg.builder()
                .action(Action.BUY)
                .optionType(OptionType.CE)
                .strikePrice(24200.0)
                .limitPrice(15.0)
                .quantity(1)
                .build();

        TradeLeg buyPutLeg = TradeLeg.builder()
                .action(Action.BUY)
                .optionType(OptionType.PE)
                .strikePrice(23800.0)
                .limitPrice(10.0)
                .quantity(1)
                .build();

        // Act & Assert
        StepVerifier.create(paperTradingEngine.calculateIronCondorCurrentValue(optionChain, List.of(buyCallLeg, buyPutLeg)))
                .expectNext(-25.0)  // -15 - 10 = -25
                .verifyComplete();
    }

    // ─── Test Case 12: calculateDefaultCurrentValue() - null chainData graceful fallback ───
    @Test
    @DisplayName("calculateDefaultCurrentValue: null chainData falls back to leg.getLimitPrice() for SELL leg")
    void testCalculateDefaultCurrentValue_NullChainData_FallsBackToLimitPrice() {
        // Setup: null optionChain triggers graceful fallback in extractLTP()
        // extractLTP() null-checks chainData (line 539) and returns leg.getLimitPrice()
        // For a SELL leg: totalCurrentValue += 50.0 (the limitPrice)
        TradeLeg leg = TradeLeg.builder()
                .action(Action.SELL)
                .optionType(OptionType.CE)
                .strikePrice(24000.0)
                .limitPrice(50.0)
                .quantity(1)
                .build();

        // Act & Assert
        StepVerifier.create(paperTradingEngine.calculateDefaultCurrentValue(null, List.of(leg)))
                .expectNext(50.0)  // Graceful fallback: SELL leg returns its limitPrice
                .verifyComplete();
    }
}
