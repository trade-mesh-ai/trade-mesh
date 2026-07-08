package com.trademesh.intelligence.iv;

import com.trademesh.zerodha.options.OptionChain;
import com.trademesh.zerodha.options.OptionChainService;
import com.trademesh.zerodha.options.OptionStrike;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for IvHistoryService.
 * Verifies idempotency check ordering and save behavior.
 */
@ExtendWith(MockitoExtension.class)
class IvHistoryServiceTest {

    @Mock
    private OptionChainService optionChainService;

    @Mock
    private IvHistoryRepository repository;

    private IvHistoryService service;

    @BeforeEach
    void setUp() {
        service = new IvHistoryService(optionChainService, repository);
    }

    /**
     * Test 1: Verify existsBySymbolAndTradeDate() is called BEFORE calculateAtmIv().
     * This confirms the idempotency check runs first, short-circuiting expensive API calls.
     */
    @Test
    void testIdempotencyCheckRunsFirst() {
        String symbol = "NIFTY50";
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Kolkata"));

        // Mock: record already exists
        when(repository.existsBySymbolAndTradeDate(symbol, today))
                .thenReturn(Mono.just(true));

        // Execute
        StepVerifier.create(service.calculateAndSaveAtmIv(symbol))
                .expectComplete()
                .verify();

        // Verify: existsBySymbolAndTradeDate was called
        verify(repository, times(1)).existsBySymbolAndTradeDate(symbol, today);

        // Verify: calculateAtmIv was NEVER called (proves short-circuit)
        verify(optionChainService, never()).fetchOptionChain(any());
    }

    /**
     * Test 2: Verify calculateAtmIv() is NOT called when a record already exists.
     * This confirms the wasted-fetch problem is actually fixed.
     */
    @Test
    void testCalculateAtmIvNotCalledWhenRecordExists() {
        String symbol = "NIFTYBANK";
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Kolkata"));

        // Mock: record already exists
        when(repository.existsBySymbolAndTradeDate(symbol, today))
                .thenReturn(Mono.just(true));

        // Execute
        service.calculateAndSaveAtmIv(symbol)
                .subscribe();

        // Verify: optionChainService.fetchOptionChain was never called
        verify(optionChainService, never()).fetchOptionChain(symbol);

        // Verify: repository.save was never called
        verify(repository, never()).save(any());
    }

    /**
     * Test 3: Verify normal save-path behavior unchanged when no record exists.
     * This is a regression test to ensure the fix doesn't break the happy path.
     */
    @Test
    void testSavePathWhenNoRecordExists() {
        String symbol = "NIFTY50";
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Kolkata"));

        // Mock: no record exists
        when(repository.existsBySymbolAndTradeDate(symbol, today))
                .thenReturn(Mono.just(false));

        // Mock: option chain fetch succeeds
        OptionChain optionChain = createMockOptionChain(symbol);
        when(optionChainService.fetchOptionChain(symbol))
                .thenReturn(Mono.just(optionChain));

        // Mock: save succeeds
        IvHistory savedRecord = IvHistory.createNew();
        savedRecord.setSymbol(symbol);
        savedRecord.setAtmStrike(BigDecimal.valueOf(21000));
        savedRecord.setAtmIv(BigDecimal.valueOf(14.5));
        savedRecord.setCallIv(BigDecimal.valueOf(14.5));
        savedRecord.setPutIv(BigDecimal.valueOf(14.5));
        savedRecord.setSpotPrice(BigDecimal.valueOf(21050));
        savedRecord.setTradeDate(today);

        when(repository.save(any(IvHistory.class)))
                .thenReturn(Mono.just(savedRecord));

        // Execute
        StepVerifier.create(service.calculateAndSaveAtmIv(symbol))
                .expectNext(savedRecord)
                .expectComplete()
                .verify();

        // Verify: existsBySymbolAndTradeDate was called
        verify(repository, times(1)).existsBySymbolAndTradeDate(symbol, today);

        // Verify: calculateAtmIv was called (via optionChainService.fetchOptionChain)
        verify(optionChainService, times(1)).fetchOptionChain(symbol);

        // Verify: save was called
        verify(repository, times(1)).save(any(IvHistory.class));
    }

    /**
     * Test 4: Verify InOrder execution - existsBySymbolAndTradeDate before calculateAtmIv.
     * This explicitly tests the order of method calls using Mockito InOrder.
     */
    @Test
    void testMethodCallOrderWithInOrder() {
        String symbol = "NIFTY50";
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Kolkata"));

        // Mock: no record exists
        when(repository.existsBySymbolAndTradeDate(symbol, today))
                .thenReturn(Mono.just(false));

        // Mock: option chain fetch succeeds
        OptionChain optionChain = createMockOptionChain(symbol);
        when(optionChainService.fetchOptionChain(symbol))
                .thenReturn(Mono.just(optionChain));

        // Mock: save succeeds
        IvHistory savedRecord = IvHistory.createNew();
        savedRecord.setSymbol(symbol);
        savedRecord.setAtmStrike(BigDecimal.valueOf(21000));
        savedRecord.setAtmIv(BigDecimal.valueOf(14.5));
        savedRecord.setCallIv(BigDecimal.valueOf(14.5));
        savedRecord.setPutIv(BigDecimal.valueOf(14.5));
        savedRecord.setSpotPrice(BigDecimal.valueOf(21050));
        savedRecord.setTradeDate(today);

        when(repository.save(any(IvHistory.class)))
                .thenReturn(Mono.just(savedRecord));

        // Execute
        service.calculateAndSaveAtmIv(symbol)
                .subscribe();

        // Verify order: existsBySymbolAndTradeDate MUST be called before fetchOptionChain
        InOrder inOrder = inOrder(repository, optionChainService);
        inOrder.verify(repository).existsBySymbolAndTradeDate(symbol, today);
        inOrder.verify(optionChainService).fetchOptionChain(symbol);
    }

    /**
     * Helper method to create a mock OptionChain for testing.
     */
    private OptionChain createMockOptionChain(String symbol) {
        OptionStrike atmStrike = new OptionStrike();
        atmStrike.setStrikePrice(BigDecimal.valueOf(21000));
        atmStrike.setCallIV(BigDecimal.valueOf(14.5));
        atmStrike.setPutIV(BigDecimal.valueOf(14.5));

        OptionChain optionChain = new OptionChain();
        optionChain.setSpotPrice(BigDecimal.valueOf(21050));
        optionChain.setStrikes(List.of(atmStrike));
        optionChain.setExpiryDate(LocalDate.now(ZoneId.of("Asia/Kolkata")).plusDays(7));

        return optionChain;
    }
}
