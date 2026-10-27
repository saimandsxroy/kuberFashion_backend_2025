package com.kuberfashion.backend.concurrency;

import com.kuberfashion.backend.controller.OrderController;
import com.kuberfashion.backend.controller.OrderController.CreateOrderRequest;
import com.kuberfashion.backend.dto.ApiResponse;
import com.kuberfashion.backend.dto.CartItemDto;
import com.kuberfashion.backend.dto.ReconciliationSummary;
import com.kuberfashion.backend.entity.*;
import com.kuberfashion.backend.exception.IdempotencyConflictException;
import com.kuberfashion.backend.exception.InsufficientStockException;
import com.kuberfashion.backend.repository.*;
import com.kuberfashion.backend.service.OrderService;
import com.kuberfashion.backend.service.PaymentReconciliationService;
import com.kuberfashion.backend.service.PaymentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;

/**
 * PostgreSQL Concurrency and Failure Correctness Integration Test Suite.
 *
 * Runs against a REAL PostgreSQL 16 container managed by Testcontainers.
 * Validates database-level row locking (SELECT ... FOR UPDATE), table unique constraints,
 * transaction rollbacks, multi-threaded idempotency deduplication, and non-blocking
 * abandoned payment reconciliation.
 */
@SpringBootTest
@Testcontainers
class PostgreSqlConcurrencyIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(PostgreSqlConcurrencyIntegrationTest.class);

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("kuberfashion_concurrency_test")
            .withUsername("testuser")
            .withPassword("testpass");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.jpa.properties.hibernate.dialect", () -> "org.hibernate.dialect.PostgreSQLDialect");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "create-drop");
        registry.add("spring.flyway.enabled", () -> "false");
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "50");
        registry.add("spring.datasource.hikari.minimum-idle", () -> "10");
        registry.add("spring.datasource.hikari.connection-timeout", () -> "30000");
        registry.add("phonepe.simulation-enabled", () -> "true");
        registry.add("phonepe.webhook-username", () -> "test_webhook_user");
        registry.add("phonepe.webhook-password", () -> "test_webhook_password");
        registry.add("jwt.secret", () -> "test-only-jwt-secret-used-in-unit-tests-only-do-not-use-in-prod");
        registry.add("jwt.expiration", () -> "3600000");
        registry.add("cloudflare.r2.enabled", () -> "false");
        registry.add("spring.cache.type", () -> "none");
        registry.add("SKIP_DATA_INITIALIZATION", () -> "true");
        registry.add("spring.jpa.show-sql", () -> "true");
        registry.add("logging.level.org.hibernate.SQL", () -> "DEBUG");
        registry.add("logging.level.org.hibernate.orm.jdbc.bind", () -> "TRACE");
    }

    @Autowired
    private OrderService orderService;

    @Autowired
    private OrderController orderController;

    @Autowired
    private PaymentService paymentService;

    @Autowired
    private PaymentReconciliationService reconciliationService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private CategoryRepository categoryRepository;

    @Autowired
    private AddressRepository addressRepository;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private PaymentTransactionRepository transactionRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private jakarta.persistence.EntityManager entityManager;

    private Category testCategory;

    @BeforeEach
    void setUpDatabase() {
        // Enforce the partial unique index matching Flyway V9
        jdbcTemplate.execute(
                "CREATE UNIQUE INDEX IF NOT EXISTS uk_orders_user_idempotency " +
                        "ON orders (user_id, idempotency_key) " +
                        "WHERE idempotency_key IS NOT NULL;");

        if (testCategory == null || !categoryRepository.existsById(testCategory.getId())) {
            Category cat = new Category();
            cat.setName("Postgres Test Category " + System.currentTimeMillis());
            cat.setSlug("cat-" + System.nanoTime());
            testCategory = categoryRepository.save(cat);
        }
    }

    private User createCustomer(String namePrefix) {
        User user = new User();
        user.setFirstName(namePrefix);
        user.setLastName("Customer");
        user.setPhone(String.format("9%09d", Math.abs(System.nanoTime() % 1_000_000_000L)));
        user.setEmail(namePrefix.toLowerCase() + "-" + System.nanoTime() + "@pgtest.com");
        user.setPassword("$2a$10$dummy.bcrypt.hash.placeholder.for.tests.only");
        user.setRole(User.Role.USER);
        return userRepository.save(user);
    }

    private Address createAddress(User user) {
        Address addr = new Address();
        addr.setUser(user);
        addr.setFirstName(user.getFirstName());
        addr.setLastName(user.getLastName());
        addr.setAddressLine1("123 PostgreSQL Concurrency Lane");
        addr.setCity("Bengaluru");
        addr.setState("Karnataka");
        addr.setPostalCode("560001");
        addr.setCountry("India");
        addr.setPhone(user.getPhone());
        return addressRepository.save(addr);
    }

    private Product createProduct(String name, int initialStock) {
        Product p = new Product();
        p.setName(name);
        p.setSlug("prod-" + System.nanoTime());
        p.setCategory(testCategory);
        p.setPrice(BigDecimal.valueOf(499.00));
        p.setStockQuantity(initialStock);
        p.setInStock(initialStock > 0);
        p.setActive(true);
        p.setSizes(new ArrayList<>(List.of("M", "L")));
        p.setColors(new ArrayList<>(List.of("Navy", "Black")));
        return productRepository.save(p);
    }

    // =========================================================================
    // SCENARIO 1: Multiple simultaneous customers competing for last stock
    // 50 simultaneous attempts to purchase 10 available units must never allocate > 10.
    // =========================================================================
    @Test
    @DisplayName("Scenario 1: 50 concurrent requests competing for 10 units never oversell")
    void testConcurrentStockExhaustion_50ThreadsFor10Units() throws Exception {
        final int initialStock = 10;
        final int concurrentRequests = 50;

        Product product = createProduct("Flash Sale Jacket", initialStock);
        Long productId = product.getId();

        // Pre-create 50 users and addresses so test measures checkout concurrency only
        List<User> customers = new ArrayList<>();
        List<Address> addresses = new ArrayList<>();
        for (int i = 0; i < concurrentRequests; i++) {
            User u = createCustomer("Cust" + i);
            customers.add(u);
            addresses.add(createAddress(u));
        }

        ExecutorService executor = Executors.newFixedThreadPool(concurrentRequests);
        CountDownLatch readyLatch = new CountDownLatch(concurrentRequests);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(concurrentRequests);

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger insufficientStockCount = new AtomicInteger(0);
        AtomicInteger unexpectedErrorCount = new AtomicInteger(0);

        for (int i = 0; i < concurrentRequests; i++) {
            final int index = i;
            executor.submit(() -> {
                readyLatch.countDown();
                try {
                    startLatch.await(); // Synchronize all 50 threads to fire simultaneously

                    CartItemDto item = new CartItemDto();
                    item.setProductId(productId);
                    item.setQuantity(1);

                    orderService.createOrder(
                            customers.get(index).getId(),
                            List.of(item),
                            addresses.get(index).getId(),
                            Order.PaymentMethod.CASH_ON_DELIVERY,
                            null,
                            Order.PurchaseMode.ONLINE);

                    successCount.incrementAndGet();
                } catch (InsufficientStockException e) {
                    insufficientStockCount.incrementAndGet();
                } catch (Exception e) {
                    log.error("Unexpected error in concurrent checkout: {}", e.getMessage(), e);
                    unexpectedErrorCount.incrementAndGet();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        readyLatch.await(10, TimeUnit.SECONDS);
        startLatch.countDown(); // FIRE ALL 50 THREADS SIMULTANEOUSLY

        boolean completed = doneLatch.await(30, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(completed).isTrue();
        log.info("📊 Concurrency Results: success={}, outOfStock={}, errors={}",
                successCount.get(), insufficientStockCount.get(), unexpectedErrorCount.get());

        // Invariants verification
        assertThat(unexpectedErrorCount.get()).isEqualTo(0);
        assertThat(successCount.get()).isEqualTo(initialStock);
        assertThat(insufficientStockCount.get()).isEqualTo(concurrentRequests - initialStock);

        // Database state verification directly in PostgreSQL
        Product finalProduct = productRepository.findById(productId).orElseThrow();
        assertThat(finalProduct.getStockQuantity()).isEqualTo(0);
        assertThat(finalProduct.isInStock()).isFalse();

        // Verify count of actual persisted orders
        Integer productOrders = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM order_items WHERE product_id = ?",
                Integer.class,
                productId);
        assertThat(productOrders).isEqualTo(10);
    }

    // =========================================================================
    // SCENARIO 2: Concurrent requests using the same user's same idempotency key
    // =========================================================================
    @Test
    @DisplayName("Scenario 2: Concurrent requests with same user's same idempotency key create exactly 1 order")
    void testConcurrentRequests_SameUserSameIdempotencyKey() throws Exception {
        User customer = createCustomer("IdemUser");
        Address address = createAddress(customer);
        Product product = createProduct("Idempotent Hoodie", 20);
        Long productId = product.getId();

        String idempotencyKey = "idem-" + UUID.randomUUID();
        int threadCount = 10;

        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch readyLatch = new CountDownLatch(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);

        List<ResponseEntity<ApiResponse<Order>>> responses = Collections.synchronizedList(new ArrayList<>());
        AtomicInteger failureCount = new AtomicInteger(0);

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                readyLatch.countDown();
                try {
                    startLatch.await();

                    CreateOrderRequest req = new CreateOrderRequest();
                    req.setAddressId(address.getId());
                    req.setPaymentMethod("CASH_ON_DELIVERY");
                    req.setIdempotencyKey(idempotencyKey);

                    CartItemDto item = new CartItemDto();
                    item.setProductId(productId);
                    item.setQuantity(1);
                    req.setCartItems(List.of(item));

                    ResponseEntity<ApiResponse<Order>> resp = orderController.createOrder(customer, req);
                    responses.add(resp);
                } catch (Exception e) {
                    log.error("Error in concurrent idempotency request: {}", e.getMessage(), e);
                    failureCount.incrementAndGet();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        readyLatch.await(5, TimeUnit.SECONDS);
        startLatch.countDown(); // Unleash all 10 threads with the identical idempotency key

        boolean completed = doneLatch.await(15, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(completed).isTrue();
        assertThat(failureCount.get()).isEqualTo(0);
        assertThat(responses).hasSize(threadCount);

        // All 10 responses must return HTTP 200 OK
        for (ResponseEntity<ApiResponse<Order>> resp : responses) {
            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(resp.getBody()).isNotNull();
            assertThat(resp.getBody().isSuccess()).isTrue();
        }

        // All 10 responses must reference the EXACT SAME order ID
        Long firstOrderId = responses.get(0).getBody().getData().getId();
        for (ResponseEntity<ApiResponse<Order>> resp : responses) {
            assertThat(resp.getBody().getData().getId()).isEqualTo(firstOrderId);
        }

        // Database verification: Exactly 1 order in PostgreSQL
        Optional<Order> dbOrder = orderRepository.findByIdempotencyKeyAndUserId(idempotencyKey, customer.getId());
        assertThat(dbOrder).isPresent();

        // Product stock deducted EXACTLY once (20 - 1 = 19)
        Product dbProduct = productRepository.findById(productId).orElseThrow();
        assertThat(dbProduct.getStockQuantity()).isEqualTo(19);
    }

    // =========================================================================
    // SCENARIO 3: Idempotency key reuse with different payload (conflict)
    // =========================================================================
    @Test
    @DisplayName("Scenario 3: Reusing idempotency key with conflicting payload is rejected with 409 Conflict")
    void testIdempotencyKeyReuse_ConflictingPayloadRejected() {
        User customer = createCustomer("ConflictUser");
        Address address = createAddress(customer);
        Product productA = createProduct("Product A", 10);
        Product productB = createProduct("Product B", 10);

        String idempotencyKey = "conflict-key-" + UUID.randomUUID();

        // Request 1: Order Product A (qty 1) with CASH_ON_DELIVERY
        CreateOrderRequest req1 = new CreateOrderRequest();
        req1.setAddressId(address.getId());
        req1.setPaymentMethod("CASH_ON_DELIVERY");
        req1.setIdempotencyKey(idempotencyKey);
        CartItemDto itemA = new CartItemDto();
        itemA.setProductId(productA.getId());
        itemA.setQuantity(1);
        req1.setCartItems(List.of(itemA));

        ResponseEntity<ApiResponse<Order>> resp1 = orderController.createOrder(customer, req1);
        assertThat(resp1.getStatusCode()).isEqualTo(HttpStatus.OK);
        Long orderId1 = resp1.getBody().getData().getId();

        // Request 2: Reusing SAME key with different payment method (PHONEPE)
        CreateOrderRequest req2 = new CreateOrderRequest();
        req2.setAddressId(address.getId());
        req2.setPaymentMethod("PHONEPE");
        req2.setIdempotencyKey(idempotencyKey);
        req2.setCartItems(List.of(itemA));

        ResponseEntity<ApiResponse<Order>> resp2 = orderController.createOrder(customer, req2);
        assertThat(resp2.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(resp2.getBody().getMessage()).contains("Idempotency conflict");

        // Request 3: Reusing SAME key with different items (Product B instead of Product A)
        CreateOrderRequest req3 = new CreateOrderRequest();
        req3.setAddressId(address.getId());
        req3.setPaymentMethod("CASH_ON_DELIVERY");
        req3.setIdempotencyKey(idempotencyKey);
        CartItemDto itemB = new CartItemDto();
        itemB.setProductId(productB.getId());
        itemB.setQuantity(1);
        req3.setCartItems(List.of(itemB));

        ResponseEntity<ApiResponse<Order>> resp3 = orderController.createOrder(customer, req3);
        assertThat(resp3.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(resp3.getBody().getMessage()).contains("Idempotency conflict");

        // Database verification: Product B stock is untouched (still 10)
        Product dbProductB = productRepository.findById(productB.getId()).orElseThrow();
        assertThat(dbProductB.getStockQuantity()).isEqualTo(10);

        // Product A stock was deducted only once (10 - 1 = 9)
        Product dbProductA = productRepository.findById(productA.getId()).orElseThrow();
        assertThat(dbProductA.getStockQuantity()).isEqualTo(9);
    }

    // =========================================================================
    // SCENARIO 4: Multi-item order where one product is unavailable (Atomic Rollback)
    // =========================================================================
    @Test
    @DisplayName("Scenario 4: Multi-item order failure rolls back stock deduction completely")
    void testMultiItemOrder_OneUnavailableRollsBackEntireTransaction() {
        User customer = createCustomer("RollbackUser");
        Address address = createAddress(customer);

        Product availableProduct = createProduct("Available Jeans", 5);
        Product outOfStockProduct = createProduct("Soldout Belt", 0);

        CartItemDto item1 = new CartItemDto();
        item1.setProductId(availableProduct.getId());
        item1.setQuantity(2);

        CartItemDto item2 = new CartItemDto();
        item2.setProductId(outOfStockProduct.getId());
        item2.setQuantity(1);

        // Attempt order containing both items
        assertThatThrownBy(() -> orderService.createOrder(
                customer.getId(),
                List.of(item1, item2),
                address.getId(),
                Order.PaymentMethod.CASH_ON_DELIVERY,
                null,
                Order.PurchaseMode.ONLINE))
                .isInstanceOf(InsufficientStockException.class);

        // Database rollback verification in PostgreSQL:
        // Available product stock MUST STILL BE 5 (not 3!). No partial inventory reduction.
        Product dbProd1 = productRepository.findById(availableProduct.getId()).orElseThrow();
        assertThat(dbProd1.getStockQuantity()).isEqualTo(5);
        assertThat(dbProd1.isInStock()).isTrue();

        Product dbProd2 = productRepository.findById(outOfStockProduct.getId()).orElseThrow();
        assertThat(dbProd2.getStockQuantity()).isEqualTo(0);
        assertThat(dbProd2.isInStock()).isFalse();

        // No order was persisted
        List<Order> customerOrders = orderRepository.findByUserIdOrderByCreatedAtDesc(customer.getId());
        assertThat(customerOrders).isEmpty();
    }

    // =========================================================================
    // SCENARIO 5: Concurrent cancellation attempts against the same order
    // =========================================================================
    @Test
    @DisplayName("Scenario 5: Concurrent cancellation attempts restore stock exactly once")
    void testConcurrentOrderCancellation_StockRestoredAtMostOnce() throws Exception {
        User customer = createCustomer("CancelUser");
        Address address = createAddress(customer);
        Product product = createProduct("Summer Shirt", 10);

        // Create order for 4 units (stock becomes 6)
        CartItemDto item = new CartItemDto();
        item.setProductId(product.getId());
        item.setQuantity(4);

        Order order = orderService.createOrder(
                customer.getId(),
                List.of(item),
                address.getId(),
                Order.PaymentMethod.CASH_ON_DELIVERY,
                null,
                Order.PurchaseMode.ONLINE);

        Long orderId = order.getId();
        Product productAfterOrder = productRepository.findById(product.getId()).orElseThrow();
        assertThat(productAfterOrder.getStockQuantity()).isEqualTo(6);

        // 10 concurrent cancellation attempts against the same order
        int cancelThreads = 10;
        ExecutorService executor = Executors.newFixedThreadPool(cancelThreads);
        CountDownLatch readyLatch = new CountDownLatch(cancelThreads);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(cancelThreads);

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger alreadyCancelledCount = new AtomicInteger(0);
        AtomicInteger otherErrorCount = new AtomicInteger(0);

        for (int i = 0; i < cancelThreads; i++) {
            executor.submit(() -> {
                readyLatch.countDown();
                try {
                    startLatch.await();
                    orderService.cancelOrder(orderId, customer.getId());
                    successCount.incrementAndGet();
                } catch (IllegalStateException e) {
                    // "Order is already cancelled"
                    alreadyCancelledCount.incrementAndGet();
                } catch (Exception e) {
                    log.error("Unexpected error in concurrent cancel: {}", e.getMessage(), e);
                    otherErrorCount.incrementAndGet();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        readyLatch.await(5, TimeUnit.SECONDS);
        startLatch.countDown(); // Unleash all 10 cancellation threads simultaneously

        boolean completed = doneLatch.await(15, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(completed).isTrue();
        assertThat(otherErrorCount.get()).isEqualTo(0);

        // Exactly 1 cancellation succeeds; remaining 9 fail fast
        assertThat(successCount.get()).isEqualTo(1);
        assertThat(alreadyCancelledCount.get()).isEqualTo(cancelThreads - 1);

        // Stock in database MUST be restored by 4 units: 6 + 4 = 10. NEVER 6 + (10 * 4) = 46!
        Product productAfterCancel = productRepository.findById(product.getId()).orElseThrow();
        assertThat(productAfterCancel.getStockQuantity()).isEqualTo(10);
        assertThat(productAfterCancel.isInStock()).isTrue();

        // Order status is CANCELLED
        Order dbOrder = orderRepository.findById(orderId).orElseThrow();
        assertThat(dbOrder.getStatus()).isEqualTo(Order.OrderStatus.CANCELLED);
    }

    // =========================================================================
    // SCENARIO 6: Duplicate and out-of-order payment callbacks
    // =========================================================================
    @Test
    @DisplayName("Scenario 6A: Duplicate & out-of-order payment callbacks are safely idempotent")
    void testDuplicateAndOutOfOrderPaymentCallbacks() {
        User customer = createCustomer("CallbackUser");
        Address address = createAddress(customer);
        Product product = createProduct("Winter Coat", 10);

        CartItemDto item = new CartItemDto();
        item.setProductId(product.getId());
        item.setQuantity(2);

        Order order = orderService.createOrder(
                customer.getId(),
                List.of(item),
                address.getId(),
                Order.PaymentMethod.PHONEPE,
                null,
                Order.PurchaseMode.ONLINE);

        Map<String, String> initData = paymentService.initiatePayment(order.getId(), customer);
        String merchantOrderId = initData.get("merchantOrderId");

        // 1. Initial SUCCESS callback
        paymentService.markPaymentSuccess(merchantOrderId, "PHONEPE_GATEWAY_TXN_001");
        Order confirmedOrder = orderRepository.findById(order.getId()).orElseThrow();
        assertThat(confirmedOrder.getStatus()).isEqualTo(Order.OrderStatus.CONFIRMED);
        assertThat(confirmedOrder.getPaymentStatus()).isEqualTo(Order.PaymentStatus.PAID);

        // 2. DUPLICATE SUCCESS callback (replayed webhook)
        paymentService.markPaymentSuccess(merchantOrderId, "PHONEPE_GATEWAY_TXN_001");
        Order stillConfirmed = orderRepository.findById(order.getId()).orElseThrow();
        assertThat(stillConfirmed.getStatus()).isEqualTo(Order.OrderStatus.CONFIRMED);
        assertThat(stillConfirmed.getPaymentStatus()).isEqualTo(Order.PaymentStatus.PAID);

        // 3. OUT-OF-ORDER FAILED callback arriving after SUCCESS
        paymentService.markPaymentFailed(merchantOrderId, "LATE_TIMEOUT", "Late timeout callback");
        Order orderAfterLateFail = orderRepository.findById(order.getId()).orElseThrow();
        // Payment must remain PAID and CONFIRMED — late failure cannot cancel a paid order
        assertThat(orderAfterLateFail.getStatus()).isEqualTo(Order.OrderStatus.CONFIRMED);
        assertThat(orderAfterLateFail.getPaymentStatus()).isEqualTo(Order.PaymentStatus.PAID);

        // Stock in database must NOT be restored (remains 10 - 2 = 8)
        Product dbProduct = productRepository.findById(product.getId()).orElseThrow();
        assertThat(dbProduct.getStockQuantity()).isEqualTo(8);
    }

    // =========================================================================
    // SCENARIO 7: Abandoned PhonePe order recovery & reconciliation
    // =========================================================================
    @Test
    @DisplayName("Scenario 7: Abandoned PhonePe payment recovery releases stock without affecting COD")
    void testAbandonedPhonePeOrderRecovery_DistinguishesCodAndRestoresStockOnce() {
        User customer = createCustomer("AbandonUser");
        Address address = createAddress(customer);
        Product productPhonePe = createProduct("PhonePe Reserved Kurta", 10);
        Product productCod = createProduct("COD Reserved Saree", 10);

        // 1. PhonePe order created, stock deducted by 3 (stock becomes 7)
        CartItemDto phonePeItem = new CartItemDto();
        phonePeItem.setProductId(productPhonePe.getId());
        phonePeItem.setQuantity(3);

        Order phonePeOrder = orderService.createOrder(
                customer.getId(),
                List.of(phonePeItem),
                address.getId(),
                Order.PaymentMethod.PHONEPE,
                null,
                Order.PurchaseMode.ONLINE);

        // Customer abandoned before payment initiation (no PaymentTransaction)
        // Backdate order to 35 minutes ago to simulate expired abandonment
        jdbcTemplate.update("UPDATE orders SET created_at = created_at - INTERVAL '35 minutes' WHERE id = ?",
                phonePeOrder.getId());

        // 2. COD order also created 35 minutes ago, stock deducted by 2 (stock becomes 8)
        CartItemDto codItem = new CartItemDto();
        codItem.setProductId(productCod.getId());
        codItem.setQuantity(2);

        Order codOrder = orderService.createOrder(
                customer.getId(),
                List.of(codItem),
                address.getId(),
                Order.PaymentMethod.CASH_ON_DELIVERY,
                null,
                Order.PurchaseMode.ONLINE);

        jdbcTemplate.update("UPDATE orders SET created_at = created_at - INTERVAL '35 minutes' WHERE id = ?",
                codOrder.getId());

        // 3. Run reconciliation with 15-minute timeout threshold
        ReconciliationSummary summary = reconciliationService.reconcilePendingOrders(15);

        log.info("📋 Reconciliation executed: candidates={}, confirmed={}, expired={}, skipped={}",
                summary.getCandidateCount(), summary.getConfirmedCount(),
                summary.getExpiredCount(), summary.getSkippedCount());

        // Abandoned PhonePe order must be expired and cancelled
        Order dbPhonePeOrder = orderRepository.findById(phonePeOrder.getId()).orElseThrow();
        assertThat(dbPhonePeOrder.getStatus()).isEqualTo(Order.OrderStatus.CANCELLED);
        assertThat(dbPhonePeOrder.getPaymentStatus()).isEqualTo(Order.PaymentStatus.FAILED);

        // Stock for PhonePe product must be restored by 3 units: 7 + 3 = 10
        Product dbPhonePeProduct = productRepository.findById(productPhonePe.getId()).orElseThrow();
        assertThat(dbPhonePeProduct.getStockQuantity()).isEqualTo(10);

        // COD order must be COMPLETELY UNTOUCHED (remains PENDING, stock remains 8)
        Order dbCodOrder = orderRepository.findById(codOrder.getId()).orElseThrow();
        assertThat(dbCodOrder.getStatus()).isEqualTo(Order.OrderStatus.PENDING);
        assertThat(dbCodOrder.getPaymentStatus()).isEqualTo(Order.PaymentStatus.PENDING);

        Product dbCodProduct = productRepository.findById(productCod.getId()).orElseThrow();
        assertThat(dbCodProduct.getStockQuantity()).isEqualTo(8);

        // 4. Repeated reconciliation cycle: must be a no-op (stock restored at most once)
        ReconciliationSummary repeatSummary = reconciliationService.reconcilePendingOrders(15);
        assertThat(repeatSummary.getCandidateCount()).isEqualTo(0);

        Product dbPhonePeProductAfterRepeat = productRepository.findById(productPhonePe.getId()).orElseThrow();
        assertThat(dbPhonePeProductAfterRepeat.getStockQuantity()).isEqualTo(10); // Still 10, not 13!
    }
}
