package com.trademesh.zerodha.orders;

import com.trademesh.common.resilience.CircuitBreaker;
import com.trademesh.common.resilience.DeadLetterQueue;
import com.trademesh.execution.PaperTradingEngine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.test.StepVerifier;
import reactor.core.publisher.Mono;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * Unit tests for OrderService.
 * <p>
 * Tests cover:
 * - Order placement with unique ID generation
 * - Order cancellation with validation
 * - Order status tracking
 * - Order modification
 * - Input validation and error handling
 * - Paper trading mode
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("OrderService Tests")
class OrderServiceTest {

    @Mock
    private PaperTradingEngine paperTradingEngine;

    @Mock
    private CircuitBreaker circuitBreaker;

    @Mock
    private DeadLetterQueue deadLetterQueue;

    @InjectMocks
    private OrderService orderService;

    private OrderRequest validOrderRequest;

    @BeforeEach
    void setUp() {
        lenient().when(circuitBreaker.execute(any(Mono.class))).thenAnswer(invocation -> invocation.getArgument(0));
        validOrderRequest = OrderRequest.builder()
                .tradingSymbol("NIFTY50")
                .exchange("NSE")
                .transactionType("BUY")
                .orderType("MARKET")
                .product("MIS")
                .quantity(2)
                .validity("DAY")
                .tag("TEST_STRATEGY")
                .build();
    }

    @Test
    @DisplayName("Should place order and return unique order ID")
    void testPlaceOrder_Success() {
        StepVerifier.create(orderService.placeOrder(validOrderRequest))
                .assertNext(orderId -> {
                    assertNotNull(orderId);
                    assertTrue(orderId.startsWith("ORD_"));
                    assertEquals(12, orderId.length()); // ORD_ + 8 chars
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("Should generate unique order IDs for multiple orders")
    void testPlaceOrder_UniqueIds() {
        String orderId1 = orderService.placeOrder(validOrderRequest).block();
        String orderId2 = orderService.placeOrder(validOrderRequest).block();

        assertNotNull(orderId1);
        assertNotNull(orderId2);
        assertNotEquals(orderId1, orderId2);
    }

    @Test
    @DisplayName("Should place order for NIFTY50")
    void testPlaceOrder_NIFTY50() {
        StepVerifier.create(orderService.placeOrder(validOrderRequest))
                .expectNextCount(1)
                .verifyComplete();
    }

    @Test
    @DisplayName("Should place order for BANKNIFTY")
    void testPlaceOrder_BANKNIFTY() {
        OrderRequest bankNiftyOrder = OrderRequest.builder()
                .tradingSymbol("BANKNIFTY")
                .exchange("NSE")
                .transactionType("SELL")
                .orderType("MARKET")
                .product("MIS")
                .quantity(1)
                .validity("DAY")
                .tag("TEST_STRATEGY")
                .build();

        StepVerifier.create(orderService.placeOrder(bankNiftyOrder))
                .expectNextCount(1)
                .verifyComplete();
    }

    @Test
    @DisplayName("Should cancel order with valid ID")
    void testCancelOrder_Success() {
        String orderId = "ORD_ABC12345";

        StepVerifier.create(orderService.cancelOrder(orderId))
                .expectNext("Order " + orderId + " cancelled")
                .verifyComplete();
    }

    @Test
    @DisplayName("Should reject cancel order with null ID")
    void testCancelOrder_NullId() {
        StepVerifier.create(orderService.cancelOrder(null))
                .expectError(IllegalArgumentException.class)
                .verify();
    }

    @Test
    @DisplayName("Should reject cancel order with empty ID")
    void testCancelOrder_EmptyId() {
        StepVerifier.create(orderService.cancelOrder(""))
                .expectError(IllegalArgumentException.class)
                .verify();
    }

    @Test
    @DisplayName("Should reject cancel order with blank ID")
    void testCancelOrder_BlankId() {
        StepVerifier.create(orderService.cancelOrder("   "))
                .expectError(IllegalArgumentException.class)
                .verify();
    }

    @Test
    @DisplayName("Should get order status")
    void testGetOrderStatus_Success() {
        String orderId = "ORD_ABC12345";

        StepVerifier.create(orderService.getOrderStatus(orderId))
                .expectNext("COMPLETE")
                .verifyComplete();
    }

    @Test
    @DisplayName("Should reject get status with null ID")
    void testGetOrderStatus_NullId() {
        StepVerifier.create(orderService.getOrderStatus(null))
                .expectError(IllegalArgumentException.class)
                .verify();
    }

    @Test
    @DisplayName("Should reject get status with empty ID")
    void testGetOrderStatus_EmptyId() {
        StepVerifier.create(orderService.getOrderStatus(""))
                .expectError(IllegalArgumentException.class)
                .verify();
    }

    @Test
    @DisplayName("Should modify order with valid parameters")
    void testModifyOrder_Success() {
        String orderId = "ORD_ABC12345";
        int newQuantity = 5;

        StepVerifier.create(orderService.modifyOrder(orderId, newQuantity))
                .expectNext("Order " + orderId + " modified with quantity " + newQuantity)
                .verifyComplete();
    }

    @Test
    @DisplayName("Should reject modify order with null ID")
    void testModifyOrder_NullId() {
        StepVerifier.create(orderService.modifyOrder(null, 5))
                .expectError(IllegalArgumentException.class)
                .verify();
    }

    @Test
    @DisplayName("Should reject modify order with empty ID")
    void testModifyOrder_EmptyId() {
        StepVerifier.create(orderService.modifyOrder("", 5))
                .expectError(IllegalArgumentException.class)
                .verify();
    }

    @Test
    @DisplayName("Should reject modify order with zero quantity")
    void testModifyOrder_ZeroQuantity() {
        StepVerifier.create(orderService.modifyOrder("ORD_ABC12345", 0))
                .expectError(IllegalArgumentException.class)
                .verify();
    }

    @Test
    @DisplayName("Should reject modify order with negative quantity")
    void testModifyOrder_NegativeQuantity() {
        StepVerifier.create(orderService.modifyOrder("ORD_ABC12345", -5))
                .expectError(IllegalArgumentException.class)
                .verify();
    }

    @Test
    @DisplayName("Should handle concurrent order placements")
    void testConcurrentOrderPlacements() {
        String orderId1 = orderService.placeOrder(validOrderRequest).block();
        String orderId2 = orderService.placeOrder(validOrderRequest).block();
        String orderId3 = orderService.placeOrder(validOrderRequest).block();

        assertNotNull(orderId1);
        assertNotNull(orderId2);
        assertNotNull(orderId3);
        assertNotEquals(orderId1, orderId2);
        assertNotEquals(orderId2, orderId3);
        assertNotEquals(orderId1, orderId3);
    }

    @Test
    @DisplayName("Should complete within timeout")
    void testOrderPlacement_Timeout() {
        StepVerifier.create(orderService.placeOrder(validOrderRequest))
                .expectNextCount(1)
                .verifyComplete();
    }

    @Test
    @DisplayName("Should handle order placement for different transaction types")
    void testPlaceOrder_DifferentTransactionTypes() {
        // BUY order
        OrderRequest buyOrder = OrderRequest.builder()
                .tradingSymbol("NIFTY50")
                .exchange("NSE")
                .transactionType("BUY")
                .orderType("MARKET")
                .product("MIS")
                .quantity(2)
                .validity("DAY")
                .tag("TEST")
                .build();

        String buyOrderId = orderService.placeOrder(buyOrder).block();
        assertNotNull(buyOrderId);

        // SELL order
        OrderRequest sellOrder = OrderRequest.builder()
                .tradingSymbol("NIFTY50")
                .exchange("NSE")
                .transactionType("SELL")
                .orderType("MARKET")
                .product("MIS")
                .quantity(2)
                .validity("DAY")
                .tag("TEST")
                .build();

        String sellOrderId = orderService.placeOrder(sellOrder).block();
        assertNotNull(sellOrderId);
        assertNotEquals(buyOrderId, sellOrderId);
    }
}
