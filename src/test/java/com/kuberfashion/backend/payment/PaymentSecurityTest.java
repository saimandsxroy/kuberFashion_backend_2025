package com.kuberfashion.backend.payment;

import com.kuberfashion.backend.config.PhonePeConfig;
import com.kuberfashion.backend.controller.PaymentController;
import com.kuberfashion.backend.dto.ApiResponse;
import com.kuberfashion.backend.entity.Category;
import com.kuberfashion.backend.entity.Order;
import com.kuberfashion.backend.entity.OrderItem;
import com.kuberfashion.backend.entity.PaymentTransaction;
import com.kuberfashion.backend.entity.Product;
import com.kuberfashion.backend.entity.User;
import com.kuberfashion.backend.repository.CategoryRepository;
import com.kuberfashion.backend.repository.OrderRepository;
import com.kuberfashion.backend.repository.PaymentTransactionRepository;
import com.kuberfashion.backend.repository.ProductRepository;
import com.kuberfashion.backend.repository.UserRepository;
import com.kuberfashion.backend.service.PaymentService;
import com.phonepe.sdk.pg.common.exception.PhonePeException;
import com.phonepe.sdk.pg.common.models.response.CallbackResponse;
import com.phonepe.sdk.pg.payments.v2.StandardCheckoutClient;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Payment security regression tests.
 *
 * <h3>What is tested</h3>
 * <ol>
 *   <li>An unverified webhook callback cannot mark an order paid.</li>
 *   <li>A verified PhonePe webhook callback updates the correct payment and order state.</li>
 *   <li>A callback with an amount mismatch is rejected and does not mark the order paid.</li>
 *   <li>An ordinary customer cannot confirm or fail an arbitrary payment when simulation mode is disabled (404 Not Found & IllegalStateException).</li>
 *   <li>One customer cannot initiate payment for another customer's order (IDOR prevention).</li>
 *   <li>One customer cannot poll the payment status of another customer's transaction (IDOR prevention).</li>
 *   <li>A replayed verified callback does not repeat side effects (idempotency guard).</li>
 *   <li>A late-arriving FAILED callback cannot override an already-COMPLETED payment.</li>
 *   <li>Existing development simulation mode checkout remains functional when {@code phonepe.simulation-enabled=true}.</li>
 * </ol>
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
@TestMethodOrder(MethodOrderer.DisplayName.class)
class PaymentSecurityTest {

    @Autowired
    private PaymentService paymentService;

    @Autowired
    private PaymentController paymentController;

    @Autowired
    private PhonePeConfig phonePeConfig;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private PaymentTransactionRepository transactionRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private CategoryRepository categoryRepository;

    // ── Fixtures ──────────────────────────────────────────────────────────────

    private User customerA;
    private User customerB;
    private Order orderOfA;

    @BeforeEach
    void setUp() {
        // Customer A — legitimate order owner
        customerA = new User();
        customerA.setFirstName("Alice");
        customerA.setLastName("A");
        customerA.setPhone(String.format("9%09d", Math.abs((System.nanoTime() + 1) % 1_000_000_000L)));
        customerA.setEmail("alice-" + System.nanoTime() + "@test.com");
        customerA.setPassword("$2a$10$dummy.bcrypt.hash.placeholder.for.tests.only");
        customerA.setRole(User.Role.USER);
        customerA = userRepository.save(customerA);

        // Customer B — unauthorized third-party user
        customerB = new User();
        customerB.setFirstName("Bob");
        customerB.setLastName("B");
        customerB.setPhone(String.format("8%09d", Math.abs((System.nanoTime() + 2) % 1_000_000_000L)));
        customerB.setEmail("bob-" + System.nanoTime() + "@test.com");
        customerB.setPassword("$2a$10$dummy.bcrypt.hash.placeholder.for.tests.only");
        customerB.setRole(User.Role.USER);
        customerB = userRepository.save(customerB);

        // Order belonging to customerA
        orderOfA = new Order();
        orderOfA.setUser(customerA);
        orderOfA.setOrderNumber("TEST-ORD-001");
        orderOfA.setTotalAmount(new BigDecimal("999.00"));
        orderOfA.setSubtotal(new BigDecimal("999.00"));
        orderOfA.setTaxAmount(BigDecimal.ZERO);
        orderOfA.setShippingAmount(BigDecimal.ZERO);
        orderOfA.setTotalItems(1);
        orderOfA.setStatus(Order.OrderStatus.PENDING);
        orderOfA.setPaymentStatus(Order.PaymentStatus.PENDING);
        orderOfA.setPaymentMethod(Order.PaymentMethod.PHONEPE);
        orderOfA = orderRepository.save(orderOfA);
    }

    // ── Test 1: Webhook verification security ─────────────────────────────────

    @Test
    @DisplayName("1a. Unverified webhook callback without SDK is rejected")
    void unverifiedWebhookCannotMarkOrderPaid() {
        String fakeAuthorization = "Basic ZmFrZTpmYWtl";
        String fakeBody = "{\"event\":\"PAYMENT_SUCCESS\",\"payload\":{\"merchantOrderId\":\"KF12345\","
                + "\"state\":\"COMPLETED\",\"amount\":99900}}";

        boolean result = paymentService.handleCallback(fakeAuthorization, fakeBody);
        assertThat(result)
                .as("Unverified callback must be rejected (return false)")
                .isFalse();
    }

    @Test
    @DisplayName("1b. Missing Authorization header on webhook is rejected")
    void missingAuthorizationOnWebhookIsRejected() {
        boolean result = paymentService.handleCallback(null, "{}");
        assertThat(result).isFalse();
    }

    @Test
    @DisplayName("1c. PhonePe SDK callback verification failure rejects callback")
    void phonePeSdkVerificationFailureRejectsCallback() throws Exception {
        PaymentTransaction tx = createPendingTransaction("KF-SDK-FAIL-001");
        StandardCheckoutClient mockClient = mock(StandardCheckoutClient.class);
        when(mockClient.validateCallback(anyString(), anyString(), anyString(), anyString()))
                .thenThrow(new PhonePeException("Invalid HMAC signature"));

        Object originalClient = ReflectionTestUtils.getField(paymentService, "phonePeClient");
        try {
            ReflectionTestUtils.setField(paymentService, "phonePeClient", mockClient);

            boolean result = paymentService.handleCallback("Basic invalidAuth", "{}");
            assertThat(result).isFalse();

            PaymentTransaction unchanged = transactionRepository
                    .findByMerchantOrderId(tx.getMerchantOrderId()).orElseThrow();
            assertThat(unchanged.getStatus())
                    .as("Status must remain PENDING after failed SDK verification")
                    .isEqualTo(PaymentTransaction.TransactionStatus.PENDING);
        } finally {
            ReflectionTestUtils.setField(paymentService, "phonePeClient", originalClient);
        }
    }

    @Test
    @DisplayName("1d. Verified PhonePe webhook callback updates the correct payment and order")
    void verifiedCallbackUpdatesCorrectPayment() throws Exception {
        PaymentTransaction tx = createPendingTransaction("KF-SDK-VERIFIED-001");

        StandardCheckoutClient mockClient = mock(StandardCheckoutClient.class);
        CallbackResponse mockResponse = mock(CallbackResponse.class, RETURNS_DEEP_STUBS);
        when(mockResponse.getPayload().getState()).thenReturn("COMPLETED");
        when(mockResponse.getPayload().getOriginalMerchantOrderId()).thenReturn(tx.getMerchantOrderId());
        when(mockResponse.getPayload().getAmount()).thenReturn(99900L); // 999.00 * 100 paise
        when(mockResponse.getPayload().getOrderId()).thenReturn("PHONEPE_TXN_REAL_999");

        when(mockClient.validateCallback(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(mockResponse);

        Object originalClient = ReflectionTestUtils.getField(paymentService, "phonePeClient");
        try {
            ReflectionTestUtils.setField(paymentService, "phonePeClient", mockClient);

            boolean result = paymentService.handleCallback("Basic validAuth", "{\"body\":\"valid\"}");
            assertThat(result).isTrue();

            PaymentTransaction updatedTx = transactionRepository
                    .findByMerchantOrderId(tx.getMerchantOrderId()).orElseThrow();
            assertThat(updatedTx.getStatus()).isEqualTo(PaymentTransaction.TransactionStatus.COMPLETED);
            assertThat(updatedTx.getPhonePeTransactionId()).isEqualTo("PHONEPE_TXN_REAL_999");

            Order updatedOrder = orderRepository.findById(orderOfA.getId()).orElseThrow();
            assertThat(updatedOrder.getPaymentStatus()).isEqualTo(Order.PaymentStatus.PAID);
            assertThat(updatedOrder.getStatus()).isEqualTo(Order.OrderStatus.CONFIRMED);
            assertThat(updatedOrder.getPaidAt()).isNotNull();
        } finally {
            ReflectionTestUtils.setField(paymentService, "phonePeClient", originalClient);
        }
    }

    @Test
    @DisplayName("1e. Callback with mismatched amount is rejected even if cryptographically valid")
    void callbackAmountMismatchIsRejected() throws Exception {
        PaymentTransaction tx = createPendingTransaction("KF-AMOUNT-MISMATCH-001");

        StandardCheckoutClient mockClient = mock(StandardCheckoutClient.class);
        CallbackResponse mockResponse = mock(CallbackResponse.class, RETURNS_DEEP_STUBS);
        when(mockResponse.getPayload().getState()).thenReturn("COMPLETED");
        when(mockResponse.getPayload().getOriginalMerchantOrderId()).thenReturn(tx.getMerchantOrderId());
        // Expected amount is 99900 paise (999.00 rupees), but payload reports 1000 paise (10.00 rupees)
        when(mockResponse.getPayload().getAmount()).thenReturn(1000L);
        when(mockResponse.getPayload().getOrderId()).thenReturn("PHONEPE_UNDERPAID");

        when(mockClient.validateCallback(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(mockResponse);

        Object originalClient = ReflectionTestUtils.getField(paymentService, "phonePeClient");
        try {
            ReflectionTestUtils.setField(paymentService, "phonePeClient", mockClient);

            boolean result = paymentService.handleCallback("Basic validAuth", "{}");
            assertThat(result).isFalse();

            PaymentTransaction unchanged = transactionRepository
                    .findByMerchantOrderId(tx.getMerchantOrderId()).orElseThrow();
            assertThat(unchanged.getStatus())
                    .as("Status must remain PENDING when callback amount does not match order amount")
                    .isEqualTo(PaymentTransaction.TransactionStatus.PENDING);
        } finally {
            ReflectionTestUtils.setField(paymentService, "phonePeClient", originalClient);
        }
    }

    // ── Test 2: Simulation endpoints disabled in production ───────────────────

    @Test
    @DisplayName("2. Customers cannot confirm or fail arbitrary payments when simulation is disabled (HTTP 404)")
    void cannotConfirmOrFailPaymentsWhenSimulationDisabled() {
        PaymentTransaction tx = createPendingTransaction("KF-PROD-ATTACK-001");

        // Temporarily disable simulation mode (as in production)
        ReflectionTestUtils.setField(phonePeConfig, "simulationEnabled", false);
        try {
            // Service-level protection
            assertThatThrownBy(() -> paymentService.confirmSimulatedPayment(tx.getMerchantOrderId(), "FAKE_TXN"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Simulation mode is disabled");

            assertThatThrownBy(() -> paymentService.failSimulatedPayment(tx.getMerchantOrderId(), "ERR", "Fail"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Simulation mode is disabled");

            // Controller-level HTTP protection (must return 404 NOT FOUND)
            ResponseEntity<ApiResponse<String>> confirmResponse =
                    paymentController.confirmPayment(Map.of("merchantOrderId", tx.getMerchantOrderId()));
            assertThat(confirmResponse.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

            ResponseEntity<ApiResponse<String>> failResponse =
                    paymentController.failPayment(Map.of("merchantOrderId", tx.getMerchantOrderId()));
            assertThat(failResponse.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

            // Transaction state in database must be untouched
            PaymentTransaction untouched = transactionRepository
                    .findByMerchantOrderId(tx.getMerchantOrderId()).orElseThrow();
            assertThat(untouched.getStatus()).isEqualTo(PaymentTransaction.TransactionStatus.PENDING);
        } finally {
            // Restore test configuration
            ReflectionTestUtils.setField(phonePeConfig, "simulationEnabled", true);
        }
    }

    // ── Test 3: Customer cannot initiate payment for another customer's order ──

    @Test
    @DisplayName("3. Customer B cannot initiate payment for Customer A's order (IDOR prevention)")
    void customerBCannotPayForCustomerAsOrder() {
        assertThatThrownBy(() -> paymentService.initiatePayment(orderOfA.getId(), customerB))
                .as("CustomerB must not be able to pay for CustomerA's order")
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("does not belong to the authenticated user");
    }

    @Test
    @DisplayName("3b. Customer A can initiate payment for their own order (happy path, sim mode)")
    void customerACanPayForOwnOrder() {
        Map<String, String> result = paymentService.initiatePayment(orderOfA.getId(), customerA);

        assertThat(result).containsKey("merchantOrderId");
        assertThat(result).containsKey("redirectUrl");
        assertThat(result.get("redirectUrl")).contains("simulated=true");

        Optional<PaymentTransaction> tx =
                transactionRepository.findByMerchantOrderId(result.get("merchantOrderId"));
        assertThat(tx).isPresent();
        assertThat(tx.get().getOrder().getId()).isEqualTo(orderOfA.getId());
    }

    // ── Test 4: Customer cannot poll another customer's transaction status ─────

    @Test
    @DisplayName("4. Customer B cannot check status of Customer A's transaction (IDOR prevention)")
    void customerBCannotCheckStatusOfCustomerAsTransaction() {
        PaymentTransaction tx = createPendingTransaction("KF-ALICE-MERCHANT-001");

        assertThatThrownBy(() -> paymentService.checkPaymentStatus(tx.getMerchantOrderId(), customerB))
                .as("CustomerB must not be able to check CustomerA's transaction")
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("does not belong to the authenticated user");
    }

    // ── Test 5: Replayed callback does not repeat side effects (idempotency) ───

    @Test
    @DisplayName("5. Replayed success callback is idempotent — no double side-effects")
    void replayedSuccessCallbackIsIdempotent() {
        PaymentTransaction tx = createPendingTransaction("KF-IDEMPOTENCY-001");

        // First call — sets COMPLETED
        paymentService.confirmSimulatedPayment(tx.getMerchantOrderId(), "PHONEPE_TXN_FIRST");

        PaymentTransaction afterFirst = transactionRepository
                .findByMerchantOrderId(tx.getMerchantOrderId()).orElseThrow();
        assertThat(afterFirst.getStatus()).isEqualTo(PaymentTransaction.TransactionStatus.COMPLETED);
        assertThat(afterFirst.getPhonePeTransactionId()).isEqualTo("PHONEPE_TXN_FIRST");

        // Second call (replay) — must be a no-op
        paymentService.confirmSimulatedPayment(tx.getMerchantOrderId(), "PHONEPE_TXN_REPLAY");

        PaymentTransaction afterReplay = transactionRepository
                .findByMerchantOrderId(tx.getMerchantOrderId()).orElseThrow();
        assertThat(afterReplay.getStatus())
                .as("Status must still be COMPLETED after replay")
                .isEqualTo(PaymentTransaction.TransactionStatus.COMPLETED);
        assertThat(afterReplay.getPhonePeTransactionId())
                .as("Transaction ID must not be overwritten by replay")
                .isEqualTo("PHONEPE_TXN_FIRST");
    }

    @Test
    @DisplayName("5b. Late FAILED callback cannot override an already COMPLETED payment")
    void lateFailedCallbackCannotOverrideCompletedPayment() {
        PaymentTransaction tx = createPendingTransaction("KF-LATE-FAIL-001");

        paymentService.confirmSimulatedPayment(tx.getMerchantOrderId(), "PHONEPE_TXN_SUCCESS");

        // Late failure callback arrives
        paymentService.failSimulatedPayment(tx.getMerchantOrderId(), "LATE_ERROR", "Late failure");

        PaymentTransaction afterLateFailure = transactionRepository
                .findByMerchantOrderId(tx.getMerchantOrderId()).orElseThrow();
        assertThat(afterLateFailure.getStatus())
                .as("COMPLETED payment must not be overridden by a late FAILED callback")
                .isEqualTo(PaymentTransaction.TransactionStatus.COMPLETED);
    }

    @Test
    @DisplayName("5c. When payment fails, reserved stock is restored to products and order is CANCELLED")
    void failedPaymentRestoresReservedStockAndCancelsOrder() {
        Category cat = new Category();
        cat.setName("Pay Test Cat");
        cat.setSlug("pay-cat-" + System.nanoTime());
        cat.setActive(true);
        cat = categoryRepository.save(cat);

        Product prod = new Product();
        prod.setName("Pay Test Item");
        prod.setSlug("pay-prod-" + System.nanoTime());
        prod.setPrice(new BigDecimal("500.00"));
        prod.setCategory(cat);
        prod.setInStock(true);
        prod.setStockQuantity(3); // 5 - 2 initially deducted
        prod.setActive(true);
        prod = productRepository.save(prod);

        // Attach an order item representing the 2 reserved units
        OrderItem item = new OrderItem();
        item.setOrder(orderOfA);
        item.setProduct(prod);
        item.setQuantity(2);
        item.setUnitPrice(prod.getPrice());
        orderOfA.setOrderItems(new java.util.HashSet<>(java.util.List.of(item)));
        orderOfA = orderRepository.save(orderOfA);

        PaymentTransaction tx = createPendingTransaction("KF-FAIL-RESTORE-001");

        // Mark payment failed
        paymentService.failSimulatedPayment(tx.getMerchantOrderId(), "USER_DROPPED", "Customer cancelled on gateway");

        // Transaction must be FAILED
        PaymentTransaction failedTx = transactionRepository.findByMerchantOrderId(tx.getMerchantOrderId()).orElseThrow();
        assertThat(failedTx.getStatus()).isEqualTo(PaymentTransaction.TransactionStatus.FAILED);

        // Order must be CANCELLED and paymentStatus FAILED
        Order updatedOrder = orderRepository.findById(orderOfA.getId()).orElseThrow();
        assertThat(updatedOrder.getStatus()).isEqualTo(Order.OrderStatus.CANCELLED);
        assertThat(updatedOrder.getPaymentStatus()).isEqualTo(Order.PaymentStatus.FAILED);
        assertThat(updatedOrder.getCancelledAt()).isNotNull();

        // Product stock must be restored from 3 back to 5!
        Product restoredProduct = productRepository.findById(prod.getId()).orElseThrow();
        assertThat(restoredProduct.getStockQuantity())
                .as("Stock must be restored on payment failure: 3 + 2 = 5")
                .isEqualTo(5);
    }

    @Test
    @DisplayName("5d. Duplicate failure callback is idempotent and does not double-restore stock")
    void duplicateFailureCallbackIsIdempotent() {
        Category cat = new Category();
        cat.setName("Pay Test Cat 2");
        cat.setSlug("pay-cat2-" + System.nanoTime());
        cat.setActive(true);
        cat = categoryRepository.save(cat);

        Product prod = new Product();
        prod.setName("Pay Test Item 2");
        prod.setSlug("pay-prod2-" + System.nanoTime());
        prod.setPrice(new BigDecimal("500.00"));
        prod.setCategory(cat);
        prod.setInStock(true);
        prod.setStockQuantity(3);
        prod.setActive(true);
        prod = productRepository.save(prod);

        OrderItem item = new OrderItem();
        item.setOrder(orderOfA);
        item.setProduct(prod);
        item.setQuantity(2);
        item.setUnitPrice(prod.getPrice());
        orderOfA.setOrderItems(new java.util.HashSet<>(java.util.List.of(item)));
        orderOfA = orderRepository.save(orderOfA);

        PaymentTransaction tx = createPendingTransaction("KF-DUP-FAIL-001");

        // First failure call
        paymentService.failSimulatedPayment(tx.getMerchantOrderId(), "FAIL_1", "First failure");
        Product afterFirst = productRepository.findById(prod.getId()).orElseThrow();
        assertThat(afterFirst.getStockQuantity()).isEqualTo(5);

        // Second duplicate failure callback — must be a no-op
        paymentService.failSimulatedPayment(tx.getMerchantOrderId(), "FAIL_2", "Duplicate failure");
        Product afterSecond = productRepository.findById(prod.getId()).orElseThrow();
        assertThat(afterSecond.getStockQuantity())
                .as("Duplicate failure must not restore stock a second time (must remain 5)")
                .isEqualTo(5);
    }

    @Test
    @DisplayName("5e. Late COMPLETED callback after FAILED cannot mark order paid")
    void lateCompletedCallbackCannotOverrideFailedPayment() {
        PaymentTransaction tx = createPendingTransaction("KF-LATE-SUCCESS-001");

        // First transition to FAILED
        paymentService.failSimulatedPayment(tx.getMerchantOrderId(), "USER_TIMEOUT", "Timeout");

        // Late success callback arrives
        paymentService.confirmSimulatedPayment(tx.getMerchantOrderId(), "LATE_SUCCESS_TXN");

        PaymentTransaction afterLateSuccess = transactionRepository
                .findByMerchantOrderId(tx.getMerchantOrderId()).orElseThrow();
        assertThat(afterLateSuccess.getStatus())
                .as("Status must remain FAILED when terminal state was already reached")
                .isEqualTo(PaymentTransaction.TransactionStatus.FAILED);

        Order order = orderRepository.findById(orderOfA.getId()).orElseThrow();
        assertThat(order.getStatus())
                .as("Order must remain CANCELLED, not CONFIRMED")
                .isEqualTo(Order.OrderStatus.CANCELLED);
        assertThat(order.getPaymentStatus())
                .as("Payment status must remain FAILED, not PAID")
                .isEqualTo(Order.PaymentStatus.FAILED);
    }

    // ── Test 6: Simulation mode checkout is functional ────────────────────────

    @Test
    @DisplayName("6. Simulation mode checkout completes successfully (dev happy path)")
    void simulationModeCheckoutWorksEndToEnd() {
        Map<String, String> initResult = paymentService.initiatePayment(orderOfA.getId(), customerA);

        String merchantOrderId = initResult.get("merchantOrderId");
        assertThat(merchantOrderId).isNotBlank();
        assertThat(initResult.get("redirectUrl")).contains("simulated=true");

        paymentService.confirmSimulatedPayment(merchantOrderId, "SIM_TXN_ABC");

        PaymentTransaction tx = transactionRepository.findByMerchantOrderId(merchantOrderId).orElseThrow();
        assertThat(tx.getStatus()).isEqualTo(PaymentTransaction.TransactionStatus.COMPLETED);
        assertThat(tx.getPhonePeTransactionId()).isEqualTo("SIM_TXN_ABC");

        Order updatedOrder = orderRepository.findById(orderOfA.getId()).orElseThrow();
        assertThat(updatedOrder.getPaymentStatus()).isEqualTo(Order.PaymentStatus.PAID);
        assertThat(updatedOrder.getStatus()).isEqualTo(Order.OrderStatus.CONFIRMED);
        assertThat(updatedOrder.getPaidAt()).isNotNull();
    }

    // ── Helper ────────────────────────────────────────────────────────────────

    private PaymentTransaction createPendingTransaction(String merchantOrderId) {
        PaymentTransaction tx = new PaymentTransaction();
        tx.setOrder(orderOfA);
        tx.setMerchantOrderId(merchantOrderId);
        tx.setAmount(orderOfA.getTotalAmount());
        tx.setStatus(PaymentTransaction.TransactionStatus.PENDING);
        return transactionRepository.save(tx);
    }
}
