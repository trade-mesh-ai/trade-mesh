package com.trademesh.zerodha.ticker;

import com.trademesh.zerodha.auth.ZerodhaAuthService;
import com.trademesh.zerodha.auth.ZerodhaConfig;
import com.zerodhatech.kiteconnect.kitehttp.exceptions.KiteException;
import com.zerodhatech.models.Tick;
import com.zerodhatech.ticker.KiteTicker;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.Assertions;
import org.mockito.Mock;
import org.mockito.MockedConstruction;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.ReactiveValueOperations;

import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Unit tests for ZerodhaTickerService.connect()
 */
@ExtendWith(MockitoExtension.class)
class ZerodhaTickerServiceTest {

    @Mock
    ZerodhaAuthService authService;

    @Mock
    ZerodhaConfig config;

    @Mock
    ReactiveStringRedisTemplate redisTemplate;

    @Test
    void tickStream_emitsTick_andStoresPriceInRedis() throws Exception {
        // Arrange
        @SuppressWarnings("unchecked")
        ReactiveValueOperations<String, String> valueOps =
                Mockito.mock(ReactiveValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.set(anyString(), anyString(), any(java.time.Duration.class))).thenReturn(Mono.just(Boolean.TRUE));

        ZerodhaTickerService svc = new ZerodhaTickerService(config, authService, redisTemplate);

        Tick tick = Mockito.mock(Tick.class);
        when(tick.getInstrumentToken()).thenReturn(ZerodhaTickerService.TOKEN_NIFTY_50);
        when(tick.getLastTradedPrice()).thenReturn(18000.5);
        when(tick.getOpenPrice()).thenReturn(17900.0);
        when(tick.getHighPrice()).thenReturn(18050.0);
        when(tick.getLowPrice()).thenReturn(17850.0);
        when(tick.getClosePrice()).thenReturn(17950.0);
        when(tick.getVolumeTradedToday()).thenReturn(12345.0);
        when(tick.getTotalBuyQuantity()).thenReturn(1000.0);
        when(tick.getTotalSellQuantity()).thenReturn(900.0);

        java.lang.reflect.Method proc = ZerodhaTickerService.class.getDeclaredMethod("processTick", com.zerodhatech.models.Tick.class);
        proc.setAccessible(true);

        List<com.trademesh.zerodha.ticker.TickData> received = new java.util.concurrent.CopyOnWriteArrayList<>();
        svc.tickStream().subscribe(received::add);

        // Act
        proc.invoke(svc, tick);
        Thread.sleep(50); // allow asynchronous emission and redis write

        // Assert
        Assertions.assertEquals(1, received.size(), "Expected one tick to be emitted");
        TickData td = received.get(0);
        Assertions.assertEquals("NIFTY50", td.getTradingSymbol());
        Assertions.assertEquals(java.math.BigDecimal.valueOf(18000.5), td.getLastPrice());

        verify(valueOps).set(eq("price:NIFTY50"), eq(td.getLastPrice().toPlainString()), any(java.time.Duration.class));
    }

    @Test
    void tickStream_multicast_toMultipleSubscribers() throws Exception {
        // Arrange
        @SuppressWarnings("unchecked")
       ReactiveValueOperations<String, String> valueOps =
                Mockito.mock(ReactiveValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.set(anyString(), anyString(), any(Duration.class))).thenReturn(Mono.just(Boolean.TRUE));

        ZerodhaTickerService svc = new ZerodhaTickerService(config, authService, redisTemplate);

        Tick tick = Mockito.mock(Tick.class);
        when(tick.getInstrumentToken()).thenReturn(ZerodhaTickerService.TOKEN_NIFTY_BANK);
        when(tick.getLastTradedPrice()).thenReturn(41000.25);
        when(tick.getOpenPrice()).thenReturn(40900.0);
        when(tick.getHighPrice()).thenReturn(41100.0);
        when(tick.getLowPrice()).thenReturn(40850.0);
        when(tick.getClosePrice()).thenReturn(40950.0);
        when(tick.getVolumeTradedToday()).thenReturn(54321.0);
        when(tick.getTotalBuyQuantity()).thenReturn(2000.0);
        when(tick.getTotalSellQuantity()).thenReturn(1800.0);

        java.lang.reflect.Method proc = ZerodhaTickerService.class.getDeclaredMethod("processTick", Tick.class);
        proc.setAccessible(true);

        List<TickData> sub1 = new  java.util.concurrent.CopyOnWriteArrayList<>();
        List<TickData> sub2 = new java.util.concurrent.CopyOnWriteArrayList<>();

        // Subscribe both before emission
        svc.tickStream().subscribe(sub1::add);
        svc.tickStream().subscribe(sub2::add);

        // Act
        proc.invoke(svc, tick);
        Thread.sleep(50);

        // Assert both received the same tick
        Assertions.assertEquals(1, sub1.size());
        Assertions.assertEquals(1, sub2.size());
        Assertions.assertEquals(sub1.get(0).getTradingSymbol(), sub2.get(0).getTradingSymbol());
        Assertions.assertEquals(sub1.get(0).getLastPrice(), sub2.get(0).getLastPrice());

        verify(valueOps).set(eq("price:NIFTYBANK"), eq(sub1.get(0).getLastPrice().toPlainString()), any(java.time.Duration.class));
    }

    @Test
    void tickStream_isHot_lateSubscriberDoesNotReceivePastEmission() throws Exception {
        // Arrange
        @SuppressWarnings("unchecked")
        ReactiveValueOperations<String, String> valueOps =
                Mockito.mock(ReactiveValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.set(anyString(), anyString(), any(java.time.Duration.class))).thenReturn(Mono.just(Boolean.TRUE));

        ZerodhaTickerService svc = new ZerodhaTickerService(config, authService, redisTemplate);

        Tick tick = Mockito.mock(Tick.class);
        when(tick.getInstrumentToken()).thenReturn(ZerodhaTickerService.TOKEN_NIFTY_50);
        when(tick.getLastTradedPrice()).thenReturn(18100.0);
        when(tick.getOpenPrice()).thenReturn(18000.0);
        when(tick.getHighPrice()).thenReturn(18200.0);
        when(tick.getLowPrice()).thenReturn(17950.0);
        when(tick.getClosePrice()).thenReturn(18050.0);
        when(tick.getVolumeTradedToday()).thenReturn(11111.0);
        when(tick.getTotalBuyQuantity()).thenReturn(1200.0);
        when(tick.getTotalSellQuantity()).thenReturn(1100.0);

        java.lang.reflect.Method proc = ZerodhaTickerService.class.getDeclaredMethod("processTick", com.zerodhatech.models.Tick.class);
        proc.setAccessible(true);

        List<com.trademesh.zerodha.ticker.TickData> early = new java.util.concurrent.CopyOnWriteArrayList<>();
        List<com.trademesh.zerodha.ticker.TickData> late = new java.util.concurrent.CopyOnWriteArrayList<>();

        // Subscribe early, emit, then subscribe late
        svc.tickStream().subscribe(early::add);
        proc.invoke(svc, tick);
        Thread.sleep(50);

        svc.tickStream().subscribe(late::add);
        Thread.sleep(50);

        Assertions.assertEquals(1, early.size(), "Early subscriber should receive the emission");
        Assertions.assertEquals(0, late.size(), "Late subscriber should not receive past emission");
    }

    @Test
    void connect_noAccessToken_returnsError() {
        when(authService.getAccessToken()).thenReturn(Mono.empty());

        ZerodhaTickerService svc = new ZerodhaTickerService(config, authService, redisTemplate);

        StepVerifier.create(svc.connect())
                .expectErrorSatisfies(err -> {
                    assertTrue(err instanceof IllegalStateException);
                    assertTrue(err.getMessage().contains("No Zerodha access token"));
                })
                .verify();
    }

    @Test
    void connect_withAccessToken_initialisesKiteTickerAndConnects() throws KiteException {
        when(authService.getAccessToken()).thenReturn(Mono.just("dummy-access-token"));
        when(config.getApiKey()).thenReturn("dummy-api-key");

        ZerodhaTickerService svc = new ZerodhaTickerService(config, authService, redisTemplate);

        try (MockedConstruction<KiteTicker> mocked = Mockito.mockConstruction(KiteTicker.class,
                (mock, context) -> {
                    // Provide reasonable defaults if code calls isConnectionOpen()
                    when(mock.isConnectionOpen()).thenReturn(false);
                })) {

            StepVerifier.create(svc.connect())
                    .verifyComplete();

            List<KiteTicker> constructed = mocked.constructed();
            assertEquals(1, constructed.size(), "Expected one KiteTicker instance to be constructed");

            KiteTicker kt = constructed.get(0);

            // verify lifecycle configuration and connect invocation
            verify(kt).setTryReconnection(true);
            verify(kt).setMaximumRetries(5);
            verify(kt).setMaximumRetryInterval(5);
            verify(kt).connect();
        }
    }
}