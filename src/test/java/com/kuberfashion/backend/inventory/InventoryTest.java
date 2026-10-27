package com.kuberfashion.backend.inventory;

import com.kuberfashion.backend.dto.CartItemDto;
import com.kuberfashion.backend.entity.Address;
import com.kuberfashion.backend.entity.Category;
import com.kuberfashion.backend.entity.Order;
import com.kuberfashion.backend.entity.Product;
import com.kuberfashion.backend.entity.User;
import com.kuberfashion.backend.exception.InsufficientStockException;
import com.kuberfashion.backend.repository.*;
import com.kuberfashion.backend.service.OrderService;
// Use explicit JUnit imports to avoid org.junit.jupiter.api.Order shadowing the entity
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;


import static org.assertj.core.api.Assertions.*;

/**
 * Inventory correctness regression tests.
 *
 * <h3>What is tested</h3>
 * <ol>
 *   <li>An out-of-stock product cannot be ordered (InsufficientStockException).</li>
 *   <li>Ordering reduces stockQuantity by the ordered quantity.</li>
 *   <li>Ordering more than available stock throws InsufficientStockException.</li>
 *   <li>Cancelling an order restores stock to the product.</li>
 *   <li>Two sequential orders for the same 1-unit product: first succeeds, second fails.</li>
 *   <li>Product.inStock flag is automatically cleared when stockQuantity reaches zero.</li>
 * </ol>
 *
 * <h3>Concepts illustrated</h3>
 * <ul>
 *   <li><b>Pessimistic locking</b>: {@code findByIdWithPessimisticLock} issues
 *       {@code SELECT … FOR UPDATE}, serialising concurrent stock reads into a consistent
 *       per-product queue. Without this, two simultaneous transactions could both read
 *       stockQuantity=1 and both succeed, creating an oversell.</li>
 *   <li><b>Atomic deduction inside a @Transactional boundary</b>: if any item in the cart
 *       fails the stock check, the entire transaction rolls back — no partial inventory
 *       reduction occurs for the other items in the same order.</li>
 *   <li><b>Stock restoration on cancel</b>: when an order is cancelled, each
 *       {@code OrderItem} quantity is added back to the product so the stock can be
 *       resold. This matches what a real warehouse system would do ("unbook" the units).</li>
 * </ul>
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
@TestMethodOrder(MethodOrderer.DisplayName.class)
class InventoryTest {

    @Autowired
    private OrderService orderService;

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
    private jakarta.persistence.EntityManager entityManager;

    // ── Fixtures ──────────────────────────────────────────────────────────────

    private User customer;
    private Category category;
    private Address address;
    private Product product;

    @BeforeEach
    void setUp() {
        // Create customer
        customer = new User();
        customer.setFirstName("Carol");
        customer.setLastName("C");
        customer.setPhone(String.format("9%09d", Math.abs(System.nanoTime() % 1_000_000_000L)));
        customer.setEmail("carol-" + System.nanoTime() + "@inventorytest.com");
        customer.setPassword("$2a$10$dummy.bcrypt.hash.placeholder.for.tests.only");
        customer.setRole(User.Role.USER);
        customer = userRepository.save(customer);

        // Create category (not kvision so no referral logic is triggered)
        category = new Category();
        category.setName("Test Apparel");
        category.setSlug("test-apparel-inv-" + System.nanoTime());
        category.setActive(true);
        category.setProductCount(1);
        category = categoryRepository.save(category);

        // Create shipping address
        address = new Address(customer, "Carol", "C", "9000000099",
                "123 Test Street", "Mumbai", "Maharashtra", "400001");
        address = addressRepository.save(address);

        // Create product with 5 units of stock
        product = new Product();
        product.setName("Test T-Shirt");
        product.setSlug("test-tshirt-inv-" + System.nanoTime());
        product.setPrice(new BigDecimal("499.00"));
        product.setCategory(category);
        product.setInStock(true);
        product.setStockQuantity(5);
        product.setActive(true);
        product = productRepository.save(product);
    }

    // ── Test 1: Out-of-stock product cannot be ordered ────────────────────────

    /**
     * An out-of-stock product (inStock=false, stockQuantity=0) must throw
     * InsufficientStockException, rolling back the entire order.
     *
     * <p><b>Concept</b>: the deductStock() method on the Product entity checks both
     * the inStock flag AND the numeric quantity. The inStock flag can be false even
     * when stockQuantity is positive (manually disabled by admin), so both conditions
     * must be checked.
     */
    @Test
    @DisplayName("1. Out-of-stock product cannot be ordered")
    void outOfStockProductCannotBeOrdered() {
        product.setInStock(false);
        product.setStockQuantity(0);
        product = productRepository.save(product);

        CartItemDto item = cartItem(product.getId(), 1);

        assertThatThrownBy(() -> orderService.createOrder(
                customer.getId(), List.of(item), address.getId(),
                Order.PaymentMethod.CASH_ON_DELIVERY, null, Order.PurchaseMode.ONLINE))
                .isInstanceOf(InsufficientStockException.class)
                .hasMessageContaining("Insufficient stock");

        // Stock should still be 0 after the failed attempt
        Product unchanged = productRepository.findById(product.getId()).orElseThrow();
        assertThat(unchanged.getStockQuantity()).isZero();
    }

    // ── Test 2: Successful order reduces stock ─────────────────────────────────

    /**
     * Ordering 3 units from a 5-unit product must reduce stockQuantity to 2.
     *
     * <p>This test confirms the "happy path" of the pessimistic-lock + deductStock flow.
     * The product row is locked (SELECT…FOR UPDATE), stock is validated, 3 units are
     * deducted, and the updated entity is saved within the same transaction.
     */
    @Test
    @DisplayName("2. Ordering reduces stockQuantity by the ordered quantity")
    void orderReducesStock() {
        CartItemDto item = cartItem(product.getId(), 3);

        orderService.createOrder(customer.getId(), List.of(item), address.getId(),
                Order.PaymentMethod.CASH_ON_DELIVERY, null, Order.PurchaseMode.ONLINE);

        Product updated = productRepository.findById(product.getId()).orElseThrow();
        assertThat(updated.getStockQuantity())
                .as("stockQuantity must be 5 - 3 = 2")
                .isEqualTo(2);
        assertThat(updated.isInStock())
                .as("Product still has stock remaining and should still be marked in-stock")
                .isTrue();
    }

    // ── Test 3: Ordering more than available stock fails ──────────────────────

    /**
     * Requesting more units than available must throw InsufficientStockException.
     * Critically, no stock must be deducted — the inventory stays at 5.
     *
     * <p><b>Interview point</b>: because the entire createOrder() method is annotated
     * {@code @Transactional}, the exception causes a full rollback. If the product
     * is the third item in the cart and items 1 and 2 already deducted their stock,
     * those deductions also roll back — preventing partial inventory corruption.
     */
    @Test
    @DisplayName("3. Ordering more than available stock throws InsufficientStockException")
    void orderingMoreThanAvailableStockFails() {
        CartItemDto item = cartItem(product.getId(), 10); // only 5 available

        assertThatThrownBy(() -> orderService.createOrder(
                customer.getId(), List.of(item), address.getId(),
                Order.PaymentMethod.CASH_ON_DELIVERY, null, Order.PurchaseMode.ONLINE))
                .isInstanceOf(InsufficientStockException.class)
                .hasMessageContaining("requested: 10");

        // No stock was deducted — still 5
        Product unchanged = productRepository.findById(product.getId()).orElseThrow();
        assertThat(unchanged.getStockQuantity())
                .as("Failed order must not modify stock (transactional rollback)")
                .isEqualTo(5);
    }

    // ── Test 4: Cancellation restores stock ───────────────────────────────────

    /**
     * When a PENDING order is cancelled, each item's quantity must be restored to the
     * product's stock so the units can be sold again.
     *
     * <p><b>Why this matters</b>: without restoration, every cancellation permanently
     * removes units from sellable inventory. In a small-catalogue fashion business this
     * would make items appear out of stock when they are actually physically available.
     */
    @Test
    @DisplayName("4. Cancelling an order restores stock to the product")
    void cancellingOrderRestoresStock() {
        CartItemDto item = cartItem(product.getId(), 3);

        Order order = orderService.createOrder(customer.getId(), List.of(item), address.getId(),
                Order.PaymentMethod.CASH_ON_DELIVERY, null, Order.PurchaseMode.ONLINE);

        Product afterOrder = productRepository.findById(product.getId()).orElseThrow();
        assertThat(afterOrder.getStockQuantity()).isEqualTo(2); // 5 - 3

        orderService.cancelOrder(order.getId(), customer.getId());

        Product afterCancel = productRepository.findById(product.getId()).orElseThrow();
        assertThat(afterCancel.getStockQuantity())
                .as("Stock must be restored after cancellation: 2 + 3 = 5")
                .isEqualTo(5);
        assertThat(afterCancel.isInStock())
                .as("inStock flag must be true after stock restoration")
                .isTrue();
    }

    // ── Test 5: Sequential orders exhaust single-unit stock ───────────────────

    /**
     * Simulates two sequential orders trying to buy the last unit of a product.
     * The first succeeds and leaves stockQuantity=0. The second must fail.
     *
     * <p><b>This is the core oversell scenario.</b> In production, concurrency is handled
     * by the pessimistic write lock (SELECT … FOR UPDATE). In this sequential test, we
     * verify the data model and service logic independently of transaction isolation —
     * the second order reads stockQuantity=0 and the InsufficientStockException fires.
     */
    @Test
    @DisplayName("5. Sequential orders: first takes the last unit, second fails with InsufficientStock")
    void sequentialOrdersExhaustSingleUnitStock() {
        // Set product to 1 unit
        product.setStockQuantity(1);
        productRepository.save(product);

        // First order: buys the last unit
        CartItemDto item = cartItem(product.getId(), 1);
        orderService.createOrder(customer.getId(), List.of(item), address.getId(),
                Order.PaymentMethod.CASH_ON_DELIVERY, null, Order.PurchaseMode.ONLINE);

        Product afterFirst = productRepository.findById(product.getId()).orElseThrow();
        assertThat(afterFirst.getStockQuantity()).isZero();
        assertThat(afterFirst.isInStock()).isFalse();

        // Second order: must be rejected
        User customer2 = new User();
        customer2.setFirstName("Dave");
        customer2.setLastName("D");
        customer2.setPhone("9000000098");
        customer2.setEmail("dave@inventorytest.com");
        customer2.setPassword("$2a$10$dummy.bcrypt.hash.placeholder.for.tests.only");
        customer2.setRole(User.Role.USER);
        customer2 = userRepository.save(customer2);

        Address address2 = new Address(customer2, "Dave", "D", "9000000098",
                "456 Test Road", "Pune", "Maharashtra", "411001");
        address2 = addressRepository.save(address2);

        final User finalCustomer2 = customer2;
        final Address finalAddress2 = address2;

        assertThatThrownBy(() -> orderService.createOrder(
                finalCustomer2.getId(), List.of(cartItem(product.getId(), 1)),
                finalAddress2.getId(), Order.PaymentMethod.CASH_ON_DELIVERY, null, Order.PurchaseMode.ONLINE))
                .isInstanceOf(InsufficientStockException.class)
                .hasMessageContaining("Insufficient stock");
    }

    // ── Test 6: inStock flag clears when stock hits zero ──────────────────────

    /**
     * Buying all units of a product must automatically set inStock=false on the product.
     *
     * <p><b>Concept</b>: the inStock flag is the denormalised "is this product available?"
     * flag used by the product listing queries. It must be kept in sync with stockQuantity
     * so the UI does not show "Add to Cart" on a product that is actually sold out.
     * {@code Product.deductStock()} handles this automatically.
     */
    @Test
    @DisplayName("6. Buying all remaining stock sets inStock=false automatically")
    void buyingAllStockClearsInStockFlag() {
        CartItemDto item = cartItem(product.getId(), 5); // exactly 5 units available

        orderService.createOrder(customer.getId(), List.of(item), address.getId(),
                Order.PaymentMethod.CASH_ON_DELIVERY, null, Order.PurchaseMode.ONLINE);

        Product depleted = productRepository.findById(product.getId()).orElseThrow();
        assertThat(depleted.getStockQuantity())
                .as("All 5 units ordered — stockQuantity must be 0")
                .isZero();
        assertThat(depleted.isInStock())
                .as("inStock flag must be false when stock reaches 0")
                .isFalse();
    }

    // ── Test 7: Repeated cancellation cannot restore stock twice ────────────

    /**
     * Cancelling an already-cancelled order must be rejected with IllegalStateException,
     * and MUST NOT restore stock a second time.
     */
    @Test
    @DisplayName("7. Cancelling an already-cancelled order throws IllegalStateException and does not double restore")
    void repeatedCancellationThrowsExceptionAndDoesNotDoubleRestore() {
        CartItemDto item = cartItem(product.getId(), 3);

        Order order = orderService.createOrder(customer.getId(), List.of(item), address.getId(),
                Order.PaymentMethod.CASH_ON_DELIVERY, null, Order.PurchaseMode.ONLINE);

        // First cancel
        orderService.cancelOrder(order.getId(), customer.getId());

        Product afterFirstCancel = productRepository.findById(product.getId()).orElseThrow();
        assertThat(afterFirstCancel.getStockQuantity())
                .as("Stock restored once to 5")
                .isEqualTo(5);

        // Second cancel attempt must throw IllegalStateException
        assertThatThrownBy(() -> orderService.cancelOrder(order.getId(), customer.getId()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already cancelled");

        // Stock must STILL be 5, not 8!
        Product afterSecondCancel = productRepository.findById(product.getId()).orElseThrow();
        assertThat(afterSecondCancel.getStockQuantity())
                .as("Stock must not be restored twice (must remain 5)")
                .isEqualTo(5);
    }

    // ── Test 8: Multi-item atomic rollback ────────────────────────────────────

    /**
     * In a multi-item order where item 1 has sufficient stock but item 2 has insufficient stock,
     * the entire transaction must roll back. Item 1's stock must NOT be partially deducted.
     *
     * <p><b>Interview concept</b>: by running this test with {@code Propagation.NOT_SUPPORTED},
     * {@code orderService.createOrder()} starts and manages its own independent database
     * transaction. When InsufficientStockException is thrown, Spring's transaction manager
     * issues an immediate ROLLBACK to the JDBC connection. This verifies real database
     * rollback semantics rather than relying on test-level rollback.
     */
    @Test
    @org.springframework.transaction.annotation.Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    @DisplayName("8. Multi-item order with one unavailable item rolls back all stock deductions atomically")
    void multiItemOrderRollsBackAtomically() {
        Product product2 = new Product();
        product2.setName("Test Jeans");
        product2.setSlug("test-jeans-inv-" + System.nanoTime());
        product2.setPrice(new BigDecimal("999.00"));
        product2.setCategory(category);
        product2.setInStock(false);
        product2.setStockQuantity(0);
        product2.setActive(true);
        product2 = productRepository.save(product2);

        CartItemDto item1 = cartItem(product.getId(), 2); // 5 available -> would succeed
        CartItemDto item2 = cartItem(product2.getId(), 1); // 0 available -> will fail

        assertThatThrownBy(() -> orderService.createOrder(
                customer.getId(), List.of(item1, item2), address.getId(),
                Order.PaymentMethod.CASH_ON_DELIVERY, null, Order.PurchaseMode.ONLINE))
                .isInstanceOf(InsufficientStockException.class);

        // Clear the L1 session cache so the reload queries the underlying database table
        entityManager.clear();

        // Product 1's stock in database must remain 5, not 3!
        Product product1After = productRepository.findById(product.getId()).orElseThrow();
        assertThat(product1After.getStockQuantity())
                .as("Product 1 stock must not be partially deducted")
                .isEqualTo(5);
    }

    // ── Test 9: Idempotent order creation ─────────────────────────────────────

    /**
     * Repeated submission of createOrder with the same idempotencyKey must return
     * the existing order without creating a duplicate or deducting stock twice.
     */
    @Test
    @DisplayName("9. Repeated createOrder with same idempotencyKey returns existing order and does not double-deduct stock")
    void repeatedOrderCreationWithIdempotencyKeyIsIdempotent() {
        String idempotencyKey = "IDEM-TEST-" + System.nanoTime();
        CartItemDto item = cartItem(product.getId(), 2);

        Order firstOrder = orderService.createOrder(
                customer.getId(), List.of(item), address.getId(),
                Order.PaymentMethod.CASH_ON_DELIVERY, null, Order.PurchaseMode.ONLINE, idempotencyKey);

        Product afterFirst = productRepository.findById(product.getId()).orElseThrow();
        assertThat(afterFirst.getStockQuantity()).isEqualTo(3); // 5 - 2

        // Second call with the same idempotency key
        Order secondOrder = orderService.createOrder(
                customer.getId(), List.of(item), address.getId(),
                Order.PaymentMethod.CASH_ON_DELIVERY, null, Order.PurchaseMode.ONLINE, idempotencyKey);

        assertThat(secondOrder.getId())
                .as("Must return the same order")
                .isEqualTo(firstOrder.getId());
        assertThat(secondOrder.getOrderNumber()).isEqualTo(firstOrder.getOrderNumber());

        Product afterSecond = productRepository.findById(product.getId()).orElseThrow();
        assertThat(afterSecond.getStockQuantity())
                .as("Stock must still be 3 (not 1)")
                .isEqualTo(3);
    }

    // ── Helper ────────────────────────────────────────────────────────────────

    private CartItemDto cartItem(Long productId, int quantity) {
        CartItemDto dto = new CartItemDto();
        dto.setProductId(productId);
        dto.setQuantity(quantity);
        return dto;
    }
}
