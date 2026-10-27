package com.kuberfashion.backend.concurrency;

import com.kuberfashion.backend.controller.OrderController;
import com.kuberfashion.backend.controller.OrderController.CreateOrderRequest;
import com.kuberfashion.backend.dto.ApiResponse;
import com.kuberfashion.backend.dto.CartItemDto;
import com.kuberfashion.backend.dto.ReconciliationSummary;
import com.kuberfashion.backend.entity.*;
import com.kuberfashion.backend.exception.InsufficientStockException;
import com.kuberfashion.backend.repository.*;
import com.kuberfashion.backend.service.OrderService;
import com.kuberfashion.backend.service.PaymentReconciliationService;
import com.kuberfashion.backend.service.PaymentService;
import com.kuberfashion.backend.service.ProductVariantService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Milestone 4: Variant-Level Inventory PostgreSQL Concurrency and Failure Correctness.
 *
 * Verifies all 12 Milestone 4 requirements against a real PostgreSQL 16 Testcontainer:
 * 1. Purchases deduct correct size/color variant stock.
 * 2. Purchasing one variant leaves other variants unaffected.
 * 3. Invalid or unconfigured variants are rejected.
 * 4. 50 concurrent customers competing for 10 units cannot oversell.
 * 5. Concurrent purchases of different variants proceed correctly.
 * 6. Multi-item transaction rollback leaves all variants unchanged on failure.
 * 7. Idempotency key reuse prevents double deduction.
 * 8. Concurrent cancellations restore variant stock at most once.
 * 9. Duplicate payment-failure callbacks restore variant stock at most once.
 * 10. Abandoned payment reconciliation handles stock safely.
 * 11. Legacy products retain existing stock behavior.
 * 12. PostgreSQL database constraints reject invalid/negative inventory states.
 */
@SpringBootTest
@Testcontainers
class VariantInventoryPostgreSqlIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(VariantInventoryPostgreSqlIntegrationTest.class);

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("kuberfashion_variant_test")
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
        registry.add("spring.jpa.show-sql", () -> "false");
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
    private ProductVariantService productVariantService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private ProductVariantRepository productVariantRepository;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private AddressRepository addressRepository;

    @Autowired
    private CategoryRepository categoryRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private User testUser;
    private Address testAddress;
    private Category apparelCategory;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("DELETE FROM payment_transactions");
        jdbcTemplate.execute("DELETE FROM order_items");
        jdbcTemplate.execute("DELETE FROM orders");
        jdbcTemplate.execute("DELETE FROM cart_items");
        jdbcTemplate.execute("DELETE FROM wishlist_items");
        jdbcTemplate.execute("DELETE FROM product_variants");
        jdbcTemplate.execute("DELETE FROM product_colors");
        jdbcTemplate.execute("DELETE FROM product_sizes");
        jdbcTemplate.execute("DELETE FROM product_images");
        jdbcTemplate.execute("DELETE FROM products");
        jdbcTemplate.execute("DELETE FROM addresses");
        jdbcTemplate.execute("DELETE FROM users");
        jdbcTemplate.execute("DELETE FROM categories");

        apparelCategory = new Category();
        apparelCategory.setName("Apparel");
        apparelCategory.setSlug("apparel");
        apparelCategory = categoryRepository.save(apparelCategory);

        testUser = new User();
        testUser.setEmail("variant.customer@kuberfashion.com");
        testUser.setPhone("9876543210");
        testUser.setPassword("hashedpass");
        testUser.setFirstName("Variant");
        testUser.setLastName("Tester");
        testUser.setRole(User.Role.USER);
        testUser = userRepository.save(testUser);

        testAddress = new Address();
        testAddress.setUser(testUser);
        testAddress.setFirstName("Variant");
        testAddress.setLastName("Tester");
        testAddress.setPhone("9876543210");
        testAddress.setAddressLine1("123 Silk Road");
        testAddress.setCity("Jaipur");
        testAddress.setState("Rajasthan");
        testAddress.setPostalCode("302001");
        testAddress.setCountry("India");
        testAddress.setDefault(true);
        testAddress = addressRepository.save(testAddress);
    }

    private Product createProduct(String name, int legacyStock, Product.InventoryTrackingMode mode) {
        Product product = new Product();
        product.setName(name);
        product.setSlug(name.toLowerCase().replaceAll("[^a-z0-9]+", "-") + "-" + UUID.randomUUID().toString().substring(0, 6));
        product.setPrice(BigDecimal.valueOf(999.00));
        product.setStockQuantity(legacyStock);
        product.setInStock(legacyStock > 0);
        product.setActive(true);
        product.setCategory(apparelCategory);
        product.setInventoryTrackingMode(mode);
        return productRepository.save(product);
    }

    private ProductVariant addVariant(Product product, String size, String color, int stock, String sku) {
        ProductVariant variant = new ProductVariant(product, size, color, stock, sku, true);
        return productVariantRepository.save(variant);
    }

    // ── 1. Purchases deduct correct size/color variant stock ─────────────────
    @Test
    @DisplayName("1. Purchases deduct the correct size/color variant's stock")
    void testVariantPurchaseDeductsCorrectStock() {
        Product product = createProduct("Designer Kurti", 0, Product.InventoryTrackingMode.VARIANT);
        ProductVariant variant = addVariant(product, "M", "Navy Blue", 20, "SKU-KURTI-M-BLU");

        CartItemDto cartItem = new CartItemDto(product.getId(), 3, "M", "Navy Blue");
        Order order = orderService.createOrder(
                testUser.getId(),
                List.of(cartItem),
                testAddress.getId(),
                Order.PaymentMethod.PHONEPE,
                null,
                Order.PurchaseMode.ONLINE,
                "idem-test-1"
        );

        assertThat(order).isNotNull();
        assertThat(order.getOrderItems()).hasSize(1);
        OrderItem item = order.getOrderItems().iterator().next();
        assertThat(item.getVariant()).isNotNull();
        assertThat(item.getVariant().getId()).isEqualTo(variant.getId());
        assertThat(item.getSelectedSize()).isEqualTo("M");
        assertThat(item.getSelectedColor()).isEqualTo("NAVY BLUE");

        ProductVariant updatedVariant = productVariantRepository.findById(variant.getId()).orElseThrow();
        assertThat(updatedVariant.getStockQuantity()).isEqualTo(17);
    }

    // ── 2. Purchasing one variant leaves other variants unaffected ───────────
    @Test
    @DisplayName("2. Purchasing one variant does not change another variant's stock")
    void testPurchasingOneVariantDoesNotAffectOtherVariants() {
        Product product = createProduct("Embroidered Lehenga", 0, Product.InventoryTrackingMode.VARIANT);
        ProductVariant varRed = addVariant(product, "M", "Red", 10, "SKU-LEH-M-RED");
        ProductVariant varBlue = addVariant(product, "L", "Blue", 15, "SKU-LEH-L-BLU");

        CartItemDto cartItem = new CartItemDto(product.getId(), 4, "M", "Red");
        orderService.createOrder(
                testUser.getId(),
                List.of(cartItem),
                testAddress.getId(),
                Order.PaymentMethod.PHONEPE,
                null,
                Order.PurchaseMode.ONLINE,
                "idem-test-2"
        );

        ProductVariant updatedRed = productVariantRepository.findById(varRed.getId()).orElseThrow();
        ProductVariant updatedBlue = productVariantRepository.findById(varBlue.getId()).orElseThrow();

        assertThat(updatedRed.getStockQuantity()).isEqualTo(6);
        assertThat(updatedBlue.getStockQuantity()).isEqualTo(15); // Unchanged!
    }

    // ── 3. Invalid or unconfigured variants are rejected ─────────────────────
    @Test
    @DisplayName("3. Invalid or unconfigured variants are rejected")
    void testUnconfiguredVariantIsRejected() {
        Product product = createProduct("Anarkali Suit", 0, Product.InventoryTrackingMode.VARIANT);
        addVariant(product, "S", "Gold", 10, "SKU-SUIT-S-GLD");

        CartItemDto unconfigured = new CartItemDto(product.getId(), 1, "XL", "Green");

        assertThatThrownBy(() -> orderService.createOrder(
                testUser.getId(),
                List.of(unconfigured),
                testAddress.getId(),
                Order.PaymentMethod.PHONEPE,
                null,
                Order.PurchaseMode.ONLINE,
                "idem-test-3"
        )).isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("Unconfigured or invalid variant");
    }

    // ── 4. Concurrent customers competing for last units cannot oversell ─────
    @Test
    @DisplayName("4. Concurrent customers competing for the last units cannot oversell (50 threads for 10 units)")
    void testConcurrentVariantOversellingProtection_50ThreadsFor10Units() throws Exception {
        Product product = createProduct("Limited Edition Sherwani", 0, Product.InventoryTrackingMode.VARIANT);
        ProductVariant variant = addVariant(product, "L", "Maroon", 10, "SKU-SHER-L-MRN");

        int threadCount = 50;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch endGate = new CountDownLatch(threadCount);

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger insufficientStockCount = new AtomicInteger(0);
        List<Throwable> unexpectedErrors = Collections.synchronizedList(new ArrayList<>());

        for (int i = 0; i < threadCount; i++) {
            final int index = i;
            executor.submit(() -> {
                try {
                    startGate.await();
                    CartItemDto item = new CartItemDto(product.getId(), 1, "L", "Maroon");
                    orderService.createOrder(
                            testUser.getId(),
                            List.of(item),
                            testAddress.getId(),
                            Order.PaymentMethod.PHONEPE,
                            null,
                            Order.PurchaseMode.ONLINE,
                            "idem-variant-conc-" + index
                    );
                    successCount.incrementAndGet();
                } catch (InsufficientStockException ise) {
                    insufficientStockCount.incrementAndGet();
                } catch (Throwable t) {
                    unexpectedErrors.add(t);
                } finally {
                    endGate.countDown();
                }
            });
        }

        startGate.countDown();
        boolean completed = endGate.await(30, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(completed).isTrue();
        assertThat(unexpectedErrors).isEmpty();
        assertThat(successCount.get()).isEqualTo(10);
        assertThat(insufficientStockCount.get()).isEqualTo(40);

        ProductVariant finalVariant = productVariantRepository.findById(variant.getId()).orElseThrow();
        assertThat(finalVariant.getStockQuantity()).isEqualTo(0);
    }

    // ── 5. Concurrent purchases of different variants remain correct ─────────
    @Test
    @DisplayName("5. Concurrent purchases of different variants remain correct")
    void testConcurrentPurchasesOfDifferentVariants() throws Exception {
        Product product = createProduct("Bandhgala Suit", 0, Product.InventoryTrackingMode.VARIANT);
        ProductVariant varM = addVariant(product, "M", "Black", 10, "SKU-BG-M-BLK");
        ProductVariant varL = addVariant(product, "L", "Black", 10, "SKU-BG-L-BLK");

        int threadCount = 40; // 20 for M, 20 for L
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch endGate = new CountDownLatch(threadCount);

        AtomicInteger successM = new AtomicInteger(0);
        AtomicInteger successL = new AtomicInteger(0);
        AtomicInteger rejectedM = new AtomicInteger(0);
        AtomicInteger rejectedL = new AtomicInteger(0);

        for (int i = 0; i < threadCount; i++) {
            final int index = i;
            final boolean buyM = (i % 2 == 0);
            executor.submit(() -> {
                try {
                    startGate.await();
                    CartItemDto item = new CartItemDto(product.getId(), 1, buyM ? "M" : "L", "Black");
                    orderService.createOrder(
                            testUser.getId(),
                            List.of(item),
                            testAddress.getId(),
                            Order.PaymentMethod.PHONEPE,
                            null,
                            Order.PurchaseMode.ONLINE,
                            "idem-diff-var-" + index
                    );
                    if (buyM) successM.incrementAndGet();
                    else successL.incrementAndGet();
                } catch (InsufficientStockException ise) {
                    if (buyM) rejectedM.incrementAndGet();
                    else rejectedL.incrementAndGet();
                } catch (Exception e) {
                    log.error("Unexpected error in concurrent variant test", e);
                } finally {
                    endGate.countDown();
                }
            });
        }

        startGate.countDown();
        boolean completed = endGate.await(30, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(completed).isTrue();
        assertThat(successM.get()).isEqualTo(10);
        assertThat(successL.get()).isEqualTo(10);
        assertThat(rejectedM.get()).isEqualTo(10);
        assertThat(rejectedL.get()).isEqualTo(10);

        ProductVariant finalM = productVariantRepository.findById(varM.getId()).orElseThrow();
        ProductVariant finalL = productVariantRepository.findById(varL.getId()).orElseThrow();
        assertThat(finalM.getStockQuantity()).isEqualTo(0);
        assertThat(finalL.getStockQuantity()).isEqualTo(0);
    }

    // ── 6. Multi-item transaction failure rolls back all deductions ──────────
    @Test
    @DisplayName("6. Multi-item transaction failure rolls back all variant deductions")
    void testMultiItemRollbackOnVariantFailure() {
        Product p1 = createProduct("Silk Shirt", 0, Product.InventoryTrackingMode.VARIANT);
        ProductVariant v1 = addVariant(p1, "40", "White", 10, "SKU-SHT-40-WHT");

        Product p2 = createProduct("Linen Trousers", 0, Product.InventoryTrackingMode.VARIANT);
        ProductVariant v2 = addVariant(p2, "32", "Beige", 1, "SKU-TRS-32-BGE"); // Only 1 unit in stock

        CartItemDto item1 = new CartItemDto(p1.getId(), 2, "40", "White");
        CartItemDto item2 = new CartItemDto(p2.getId(), 5, "32", "Beige"); // Requests 5 units -> fail!

        assertThatThrownBy(() -> orderService.createOrder(
                testUser.getId(),
                List.of(item1, item2),
                testAddress.getId(),
                Order.PaymentMethod.PHONEPE,
                null,
                Order.PurchaseMode.ONLINE,
                "idem-multi-fail-1"
        )).isInstanceOf(InsufficientStockException.class);

        // Verify v1 deduction was rolled back
        ProductVariant reloadedV1 = productVariantRepository.findById(v1.getId()).orElseThrow();
        assertThat(reloadedV1.getStockQuantity()).isEqualTo(10);

        ProductVariant reloadedV2 = productVariantRepository.findById(v2.getId()).orElseThrow();
        assertThat(reloadedV2.getStockQuantity()).isEqualTo(1);
    }

    // ── 7. Duplicate order submissions with same idempotency key do not deduct twice ──
    @Test
    @DisplayName("7. Duplicate order submissions with same idempotency key do not deduct stock twice")
    void testDuplicateIdempotencySubmissionDoesNotDeductTwice() {
        Product product = createProduct("Pashmina Shawl", 0, Product.InventoryTrackingMode.VARIANT);
        ProductVariant variant = addVariant(product, "Free", "Ivory", 10, "SKU-SHAWL-IVY");

        CartItemDto cartItem = new CartItemDto(product.getId(), 2, "Free", "Ivory");
        CreateOrderRequest request = new CreateOrderRequest(
                List.of(cartItem),
                testAddress.getId(),
                "PHONEPE",
                null,
                "ONLINE",
                "idempotent-key-variant-7"
        );

        ResponseEntity<ApiResponse<Order>> response1 = orderController.createOrder(testUser, request);
        assertThat(response1.getStatusCode()).isEqualTo(HttpStatus.OK);

        ProductVariant midVariant = productVariantRepository.findById(variant.getId()).orElseThrow();
        assertThat(midVariant.getStockQuantity()).isEqualTo(8);

        // Second submission with identical key
        ResponseEntity<ApiResponse<Order>> response2 = orderController.createOrder(testUser, request);
        assertThat(response2.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response2.getBody().getData().getId()).isEqualTo(response1.getBody().getData().getId());

        // Stock must still be 8, NOT 6
        ProductVariant finalVariant = productVariantRepository.findById(variant.getId()).orElseThrow();
        assertThat(finalVariant.getStockQuantity()).isEqualTo(8);
    }

    // ── 8. Concurrent cancellations restore inventory at most once ───────────
    @Test
    @DisplayName("8. Concurrent cancellations restore variant inventory at most once")
    void testConcurrentCancellationsRestoreVariantInventoryOnce() throws Exception {
        Product product = createProduct("Nehru Jacket", 0, Product.InventoryTrackingMode.VARIANT);
        ProductVariant variant = addVariant(product, "42", "Grey", 10, "SKU-NJ-42-GRY");

        CartItemDto cartItem = new CartItemDto(product.getId(), 3, "42", "Grey");
        Order order = orderService.createOrder(
                testUser.getId(),
                List.of(cartItem),
                testAddress.getId(),
                Order.PaymentMethod.PHONEPE,
                null,
                Order.PurchaseMode.ONLINE,
                "idem-cancel-test"
        );

        ProductVariant afterOrder = productVariantRepository.findById(variant.getId()).orElseThrow();
        assertThat(afterOrder.getStockQuantity()).isEqualTo(7);

        int threadCount = 10;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch endGate = new CountDownLatch(threadCount);

        AtomicInteger successCount = new AtomicInteger(0);

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    startGate.await();
                    orderService.cancelOrder(order.getId(), testUser.getId());
                    successCount.incrementAndGet();
                } catch (Exception e) {
                    // Subsequent cancellations are expected to throw IllegalStateException
                } finally {
                    endGate.countDown();
                }
            });
        }

        startGate.countDown();
        endGate.await(10, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(successCount.get()).isEqualTo(1);

        ProductVariant finalVariant = productVariantRepository.findById(variant.getId()).orElseThrow();
        assertThat(finalVariant.getStockQuantity()).isEqualTo(10); // Restored exactly once (7 + 3 = 10)
    }

    // ── 9. Duplicate payment-failure notifications cannot restore stock twice ─
    @Test
    @DisplayName("9. Duplicate payment-failure notifications cannot restore stock twice")
    void testDuplicatePaymentFailureRestoresVariantStockOnce() {
        Product product = createProduct("Banarasi Dupatta", 0, Product.InventoryTrackingMode.VARIANT);
        ProductVariant variant = addVariant(product, "Free", "Gold", 10, "SKU-DUP-GLD");

        CartItemDto cartItem = new CartItemDto(product.getId(), 2, "Free", "Gold");
        Order order = orderService.createOrder(
                testUser.getId(),
                List.of(cartItem),
                testAddress.getId(),
                Order.PaymentMethod.PHONEPE,
                null,
                Order.PurchaseMode.ONLINE,
                "idem-fail-test"
        );

        ProductVariant afterOrder = productVariantRepository.findById(variant.getId()).orElseThrow();
        assertThat(afterOrder.getStockQuantity()).isEqualTo(8);

        Map<String, String> initData = paymentService.initiatePayment(order.getId(), testUser);
        String merchantOrderId = initData.get("merchantOrderId");

        // First failure callback
        paymentService.markPaymentFailed(merchantOrderId, "FAILED_CODE", "Payment failed by customer");
        ProductVariant afterFirstFail = productVariantRepository.findById(variant.getId()).orElseThrow();
        assertThat(afterFirstFail.getStockQuantity()).isEqualTo(10);

        // Duplicate second failure callback
        paymentService.markPaymentFailed(merchantOrderId, "FAILED_CODE", "Payment failed by customer (retry)");
        ProductVariant afterSecondFail = productVariantRepository.findById(variant.getId()).orElseThrow();
        assertThat(afterSecondFail.getStockQuantity()).isEqualTo(10); // NOT 12
    }

    // ── 10. Abandoned-payment reconciliation does not release stock while payment is uncertain ──
    @Test
    @DisplayName("10. Abandoned-payment reconciliation handles stock safely and restores on confirmed expiry")
    void testAbandonedPaymentReconciliationForVariants() {
        Product product = createProduct("Velvet Sherwani", 0, Product.InventoryTrackingMode.VARIANT);
        ProductVariant variant = addVariant(product, "XL", "Navy", 10, "SKU-SHER-XL-NVY");

        CartItemDto cartItem = new CartItemDto(product.getId(), 2, "XL", "Navy");
        Order order = orderService.createOrder(
                testUser.getId(),
                List.of(cartItem),
                testAddress.getId(),
                Order.PaymentMethod.PHONEPE,
                null,
                Order.PurchaseMode.ONLINE,
                "idem-recon-test"
        );

        // Fresh order (created now) should NOT be reconciled as expired
        ReconciliationSummary dryRun = reconciliationService.reconcilePendingOrders(15);
        assertThat(dryRun.getExpiredCount()).isEqualTo(0);

        ProductVariant afterDryRun = productVariantRepository.findById(variant.getId()).orElseThrow();
        assertThat(afterDryRun.getStockQuantity()).isEqualTo(8);

        // Simulate order aging past the cutoff (e.g. 35 minutes ago)
        jdbcTemplate.update("UPDATE orders SET created_at = created_at - INTERVAL '35 minutes' WHERE id = ?",
                order.getId());

        ReconciliationSummary summary = reconciliationService.reconcilePendingOrders(15);
        assertThat(summary.getExpiredCount()).isGreaterThanOrEqualTo(1);

        ProductVariant afterRecon = productVariantRepository.findById(variant.getId()).orElseThrow();
        assertThat(afterRecon.getStockQuantity()).isEqualTo(10); // Restored exactly once!
    }

    // ── 11. Legacy-mode products retain their intended existing behavior ─────
    @Test
    @DisplayName("11. Legacy-mode products retain their intended existing behavior")
    void testLegacyModeRetainsExistingBehavior() {
        Product legacyProduct = createProduct("Traditional Saree", 25, Product.InventoryTrackingMode.LEGACY);

        CartItemDto cartItem = new CartItemDto(legacyProduct.getId(), 3, "Free", "Crimson");
        Order order = orderService.createOrder(
                testUser.getId(),
                List.of(cartItem),
                testAddress.getId(),
                Order.PaymentMethod.PHONEPE,
                null,
                Order.PurchaseMode.ONLINE,
                "idem-legacy-test"
        );

        assertThat(order).isNotNull();
        OrderItem item = order.getOrderItems().iterator().next();
        assertThat(item.getVariant()).isNull(); // Legacy item does not have a variant row

        Product reloaded = productRepository.findById(legacyProduct.getId()).orElseThrow();
        assertThat(reloaded.getStockQuantity()).isEqualTo(22);
    }

    // ── 12. Database constraints reject invalid inventory states ─────────────
    @Test
    @DisplayName("12. Database constraints reject negative stock and duplicate variants")
    void testDatabaseConstraintsRejectInvalidStates() {
        Product product = createProduct("Kundan Necklace", 0, Product.InventoryTrackingMode.VARIANT);
        ProductVariant variant = addVariant(product, "Free", "Gold", 5, "SKU-KUN-GLD");

        // Attempting to set stock to -5 violates CHECK constraint
        assertThatThrownBy(() -> {
            jdbcTemplate.update("UPDATE product_variants SET stock_quantity = -5 WHERE id = ?", variant.getId());
        }).isInstanceOf(DataIntegrityViolationException.class);

        // Attempting to insert duplicate variant for same product + size + color violates UNIQUE constraint
        assertThatThrownBy(() -> {
            jdbcTemplate.update("INSERT INTO product_variants (product_id, size, color, stock_quantity, is_active, created_at) " +
                    "VALUES (?, 'FREE', 'GOLD', 10, true, NOW())", product.getId());
        }).isInstanceOf(DataIntegrityViolationException.class);
    }
}
