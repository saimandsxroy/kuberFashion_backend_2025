package com.kuberfashion.backend.service;

import com.kuberfashion.backend.dto.CartItemDto;
import com.kuberfashion.backend.entity.Address;
import com.kuberfashion.backend.entity.Order;
import com.kuberfashion.backend.entity.OrderItem;
import com.kuberfashion.backend.entity.Product;
import com.kuberfashion.backend.entity.User;
import com.kuberfashion.backend.exception.IdempotencyConflictException;
import com.kuberfashion.backend.exception.InsufficientStockException;
import com.kuberfashion.backend.exception.ResourceNotFoundException;
import com.kuberfashion.backend.entity.ProductVariant;
import com.kuberfashion.backend.repository.AddressRepository;
import com.kuberfashion.backend.repository.OrderRepository;
import com.kuberfashion.backend.repository.ProductRepository;
import com.kuberfashion.backend.repository.ProductVariantRepository;
import com.kuberfashion.backend.repository.ReferralRelationRepository;
import com.kuberfashion.backend.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

@Service
@Transactional
public class OrderService {

    private static final Logger logger = LoggerFactory.getLogger(OrderService.class);

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private ProductVariantRepository productVariantRepository;

    @Autowired
    private AddressRepository addressRepository;

    @Autowired
    private ReferralService referralService;

    @Autowired
    private ReferralRelationRepository referralRelationRepository;

    // KVision category slug (updated to "kvision")
    private static final String KVISION_CATEGORY_SLUG = "kvision";

    public Order createOrder(Long userId, List<CartItemDto> cartItems, Long addressId,
            Order.PaymentMethod paymentMethod, String referralCode, Order.PurchaseMode purchaseMode) {
        return createOrder(userId, cartItems, addressId, paymentMethod, referralCode, purchaseMode, null);
    }

    public Order createOrder(Long userId, List<CartItemDto> cartItems, Long addressId,
            Order.PaymentMethod paymentMethod, String referralCode, Order.PurchaseMode purchaseMode,
            String idempotencyKey) {

        // ── IDEMPOTENCY GUARD ─────────────────────────────────────────────────
        // If an idempotencyKey is provided and an order already exists for this user,
        // return the existing order without repeating stock deduction or creating duplicates.
        if (idempotencyKey != null && !idempotencyKey.trim().isEmpty()) {
            Optional<Order> existingOrder = orderRepository.findByIdempotencyKeyAndUserId(idempotencyKey.trim(), userId);
            if (existingOrder.isPresent()) {
                Order existing = existingOrder.get();
                // ── PAYLOAD CONFLICT CHECK ─────────────────────────────────────
                // Reusing an idempotency key with different items or payment method
                // is an ambiguous, conflicting client state and must be rejected.
                validateIdempotentPayload(existing, cartItems, paymentMethod);

                logger.info("ℹ️ Returning existing order {} for idempotency key '{}'",
                        existing.getOrderNumber(), idempotencyKey);
                return existing;
            }
        }

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found with id: " + userId));

        // Fetch the shipping address
        Address address = addressRepository.findByIdAndUserId(addressId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("Address not found with id: " + addressId));

        // Check if cart contains KVision (Footwear) products
        // Avoid pre-loading Product entities into Hibernate L1 cache before the pessimistic write lock is acquired.
        List<Long> productIds = cartItems != null
                ? cartItems.stream().map(CartItemDto::getProductId).filter(Objects::nonNull).toList()
                : List.of();
        boolean hasKvisionProduct = !productIds.isEmpty() &&
                productRepository.existsByProductIdsAndCategorySlug(productIds, KVISION_CATEGORY_SLUG);

        // KVision membership logic
        if (hasKvisionProduct) {
            // Check if user is already a member (has a parent in referral tree)
            boolean isMember = referralRelationRepository.findByUserId(userId).isPresent();

            if (isMember) {
                // Member can buy KVision products again without referral code
                // No referral processing needed - they're already in the network
                logger.info("✅ KVision member {} purchasing additional KVision products", user.getPhone());
            } else {
                // Non-member must provide referral code (Genesis or Member code)
                if (referralCode == null || referralCode.trim().isEmpty()) {
                    throw new IllegalArgumentException(
                            "Referral code is required to purchase KVision products. Please enter a valid referral code to join KVision.");
                }

                // Only validate referral code (don't process yet - will be done after payment)
                referralService.validateReferralCodeForUser(referralCode, user);
                // Store the referral code on the order for processing after payment
            }
        }

        Order order = new Order();
        order.setUser(user);
        order.setOrderNumber(generateOrderNumber());
        order.setIdempotencyKey(idempotencyKey != null && !idempotencyKey.trim().isEmpty() ? idempotencyKey.trim() : null);
        order.setStatus(Order.OrderStatus.PENDING);
        order.setPaymentMethod(paymentMethod);
        order.setReferralCode(referralCode); // Store for processing after payment
        order.setPurchaseMode(purchaseMode != null ? purchaseMode : Order.PurchaseMode.ONLINE);

        // Set payment status based on payment method
        if (paymentMethod == Order.PaymentMethod.CASH_ON_DELIVERY) {
            order.setPaymentStatus(Order.PaymentStatus.PENDING);
        } else {
            order.setPaymentStatus(Order.PaymentStatus.PENDING);
        }

        // Set shipping address fields from Address entity
        order.setShippingFirstName(address.getFirstName());
        order.setShippingLastName(address.getLastName());
        order.setShippingAddressLine1(address.getAddressLine1());
        order.setShippingAddressLine2(address.getAddressLine2());
        order.setShippingCity(address.getCity());
        order.setShippingState(address.getState());
        order.setShippingPostalCode(address.getPostalCode());
        order.setShippingCountry(address.getCountry());
        order.setShippingPhone(address.getPhone());
        order.setShippingAddress(address.getFullAddress());
        order.setBillingAddress(address.getFullAddress());

        // Initialize orderItems set
        order.setOrderItems(new HashSet<>());

        BigDecimal subtotal = BigDecimal.ZERO;
        int totalItems = 0;

        // ── LOCK ORDERING (DEADLOCK PREVENTION) ────────────────────────────────
        // Sort cart items deterministically: by productId ascending, then normalized size and color.
        // Enforcing a strict lock hierarchy eliminates circular-wait deadlocks when concurrent
        // transactions check out overlapping sets of products or variants.
        List<CartItemDto> sortedCartItems = new ArrayList<>(cartItems);
        sortedCartItems.sort(Comparator
                .comparing(CartItemDto::getProductId)
                .thenComparing(item -> ProductVariant.normalizeSize(item.getSelectedSize()))
                .thenComparing(item -> ProductVariant.normalizeColor(item.getSelectedColor())));

        for (CartItemDto cartItem : sortedCartItems) {
            Product.InventoryTrackingMode trackingMode = productRepository
                    .findInventoryTrackingModeById(cartItem.getProductId())
                    .orElseThrow(() -> new ResourceNotFoundException(
                            "Product not found with id: " + cartItem.getProductId()));

            OrderItem orderItem = new OrderItem();
            orderItem.setOrder(order);
            orderItem.setQuantity(cartItem.getQuantity());

            Product product;
            if (trackingMode == Product.InventoryTrackingMode.VARIANT) {
                String normSize = ProductVariant.normalizeSize(cartItem.getSelectedSize());
                String normColor = ProductVariant.normalizeColor(cartItem.getSelectedColor());

                if (normSize.isEmpty() || normColor.isEmpty()) {
                    throw new IllegalArgumentException(String.format(
                            "Size and color are required for variant-tracked product id %d", cartItem.getProductId()));
                }

                // ── PESSIMISTIC WRITE LOCK ON VARIANT ROW ──────────────────────
                // SELECT … FOR UPDATE on the specific variant row.
                // Concurrent purchases of DIFFERENT variants of the same product proceed concurrently.
                ProductVariant variant = productVariantRepository
                        .findByProductIdAndSizeAndColorWithPessimisticLock(cartItem.getProductId(), normSize, normColor)
                        .orElseThrow(() -> new IllegalArgumentException(String.format(
                                "Unconfigured or invalid variant: size '%s', color '%s' is not sold for product id %d",
                                cartItem.getSelectedSize(), cartItem.getSelectedColor(), cartItem.getProductId())));

                if (!variant.isActive()) {
                    throw new InsufficientStockException(String.format(
                            "Variant size '%s', color '%s' is currently inactive", normSize, normColor));
                }

                variant.deductStock(cartItem.getQuantity());
                productVariantRepository.save(variant);

                product = variant.getProduct();
                logger.info("📦 Deducted {} units from variant size '{}' color '{}' of product '{}' (variantId={}). Remaining variant stock: {}",
                        cartItem.getQuantity(), variant.getSize(), variant.getColor(), product.getName(), variant.getId(), variant.getStockQuantity());

                orderItem.setProduct(product);
                orderItem.setVariant(variant);
                orderItem.setUnitPrice(product.getPrice());
                orderItem.setSelectedSize(normSize);
                orderItem.setSelectedColor(normColor);
            } else {
                // ── LEGACY INVENTORY TRACKING ──────────────────────────────────
                // Acquire pessimistic write lock directly as the first read of Product in this persistence context,
                // preventing stale L1 cache reads.
                Product lockedProduct = productRepository.findByIdWithPessimisticLock(cartItem.getProductId())
                        .orElseThrow(() -> new ResourceNotFoundException(
                                "Product not found with id: " + cartItem.getProductId()));

                lockedProduct.deductStock(cartItem.getQuantity());
                productRepository.save(lockedProduct);
                logger.info("📦 Deducted {} units from legacy product '{}' (id={}). Remaining stock: {}",
                        cartItem.getQuantity(), lockedProduct.getName(), lockedProduct.getId(), lockedProduct.getStockQuantity());

                product = lockedProduct;
                orderItem.setProduct(product);
                orderItem.setUnitPrice(product.getPrice());
                orderItem.setSelectedSize(cartItem.getSelectedSize());
                orderItem.setSelectedColor(cartItem.getSelectedColor());
            }

            BigDecimal itemSubtotal = product.getPrice().multiply(BigDecimal.valueOf(cartItem.getQuantity()));
            subtotal = subtotal.add(itemSubtotal);
            totalItems += cartItem.getQuantity();

            order.getOrderItems().add(orderItem);
        }

        // Calculate shipping (tiered pricing) - OFFLINE purchases have zero shipping
        BigDecimal shippingCost;
        if (purchaseMode == Order.PurchaseMode.OFFLINE) {
            shippingCost = BigDecimal.ZERO; // No shipping for shop pickup
        } else if (subtotal.compareTo(BigDecimal.valueOf(999)) > 0) {
            shippingCost = BigDecimal.valueOf(49); // Orders > ₹999
        } else if (subtotal.compareTo(BigDecimal.valueOf(500)) >= 0) {
            shippingCost = BigDecimal.valueOf(59); // Orders ₹500 - ₹999
        } else {
            shippingCost = BigDecimal.valueOf(79); // Orders < ₹500
        }

        // No tax (GST removed as per user request)
        BigDecimal tax = BigDecimal.ZERO;

        order.setSubtotal(subtotal);
        order.setShippingAmount(shippingCost);
        order.setTaxAmount(tax);
        order.setTotalAmount(subtotal.add(shippingCost).add(tax));
        order.setTotalItems(totalItems);

        return orderRepository.save(order);
    }

    public Optional<Order> getOrderById(Long orderId) {
        return orderRepository.findById(orderId);
    }

    public Optional<Order> getOrderByIdAndUserId(Long orderId, Long userId) {
        return orderRepository.findByIdAndUserId(orderId, userId);
    }

    public Page<Order> getUserOrders(Long userId, Pageable pageable) {
        return orderRepository.findByUserIdOrderByCreatedAtDesc(userId, pageable);
    }

    public Page<Order> getAllOrders(Pageable pageable) {
        return orderRepository.findAllByOrderByCreatedAtDesc(pageable);
    }

    public List<Order> getOrdersByStatus(Order.OrderStatus status) {
        return orderRepository.findByStatusOrderByCreatedAtDesc(status);
    }

    public Order updateOrderStatus(Long orderId, Order.OrderStatus status) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order not found with id: " + orderId));

        order.setStatus(status);
        order.setUpdatedAt(LocalDateTime.now());

        if (status == Order.OrderStatus.SHIPPED) {
            order.setShippedAt(LocalDateTime.now());
        } else if (status == Order.OrderStatus.DELIVERED) {
            order.setDeliveredAt(LocalDateTime.now());
        }

        return orderRepository.save(order);
    }

    public Order updatePaymentStatus(Long orderId, Order.PaymentStatus paymentStatus) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order not found with id: " + orderId));

        order.setPaymentStatus(paymentStatus);
        order.setUpdatedAt(LocalDateTime.now());

        if (paymentStatus == Order.PaymentStatus.PAID) {
            order.setPaidAt(LocalDateTime.now());
            // Auto-confirm order when payment is successful
            if (order.getStatus() == Order.OrderStatus.PENDING) {
                order.setStatus(Order.OrderStatus.CONFIRMED);
            }
        }

        return orderRepository.save(order);
    }

    public void cancelOrder(Long orderId, Long userId) {
        // Acquire pessimistic write lock on the order row to serialize concurrent cancellations
        Order order = orderRepository.findByIdAndUserIdWithPessimisticLock(orderId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("Order not found with id: " + orderId));

        // ── IDEMPOTENCY / TERMINAL STATE GUARD ────────────────────────────────
        // An order can only transition to CANCELLED once. Repeated cancellation calls
        // must fail fast to prevent restoring stock multiple times.
        if (order.getStatus() == Order.OrderStatus.CANCELLED) {
            throw new IllegalStateException("Order is already cancelled");
        }

        if (order.getStatus() == Order.OrderStatus.SHIPPED ||
                order.getStatus() == Order.OrderStatus.DELIVERED) {
            throw new IllegalStateException("Cannot cancel order that has been shipped or delivered");
        }

        // ── RESTORE STOCK ON CANCELLATION ─────────────────────────────────────
        // Return reserved units to each variant or product so they can be sold again.
        if (order.getOrderItems() != null) {
            for (com.kuberfashion.backend.entity.OrderItem item : order.getOrderItems()) {
                restoreStockForOrderItem(item);
            }
        }

        order.setStatus(Order.OrderStatus.CANCELLED);
        order.setCancelledAt(LocalDateTime.now());
        order.setUpdatedAt(LocalDateTime.now());

        // ── PAYMENT / REFUND DISTINCTION ──────────────────────────────────────
        // Cancellation is distinct from payment refund. Never mark paymentStatus as
        // REFUNDED unless an actual refund transaction has been verified.
        if (order.getPaymentStatus() == Order.PaymentStatus.PAID) {
            logger.warn("⚠️ Order {} cancelled while PAID. Payment status remains PAID — refund must be processed separately through PhonePe gateway before updating payment status.",
                    order.getOrderNumber());
        }

        orderRepository.save(order);
    }

    public BigDecimal getTotalRevenue() {
        return orderRepository.getTotalRevenue();
    }

    public BigDecimal getRevenueByDateRange(LocalDateTime startDate, LocalDateTime endDate) {
        return orderRepository.getRevenueByDateRange(startDate, endDate);
    }

    public Long getTotalOrdersCount() {
        return orderRepository.count();
    }

    public Long getOrdersCountByStatus(Order.OrderStatus status) {
        return orderRepository.countByStatus(status);
    }

    public Optional<Order> getOrderByIdempotencyKeyAndUserId(String idempotencyKey, Long userId) {
        return orderRepository.findByIdempotencyKeyAndUserId(idempotencyKey, userId);
    }

    public void restoreProductStock(Long productId, int quantity) {
        Product product = productRepository.findByIdWithPessimisticLock(productId).orElse(null);
        if (product != null) {
            product.restoreStock(quantity);
            productRepository.save(product);
            logger.info("♻️ Restored {} units to legacy product '{}' (id={})", quantity, product.getName(), product.getId());
        }
    }

    /**
     * Centralized stock restoration for an order item.
     * Routes inventory restoration to either the specific ProductVariant or the legacy Product.
     */
    public void restoreStockForOrderItem(OrderItem item) {
        if (item == null) {
            return;
        }
        if (item.getVariant() != null) {
            ProductVariant variant = productVariantRepository.findByIdWithPessimisticLock(
                    item.getVariant().getId()).orElse(null);
            if (variant != null) {
                variant.restoreStock(item.getQuantity());
                productVariantRepository.save(variant);
                logger.info("♻️ Restored {} units to variant size '{}' color '{}' of product '{}' (variantId={})",
                        item.getQuantity(), variant.getSize(), variant.getColor(),
                        item.getProduct() != null ? item.getProduct().getName() : "N/A", variant.getId());
                return;
            }
        }
        if (item.getProduct() != null) {
            restoreProductStock(item.getProduct().getId(), item.getQuantity());
        }
    }

    public void validateIdempotentPayload(Order existing, List<CartItemDto> cartItems, Order.PaymentMethod paymentMethod) {
        if (existing.getPaymentMethod() != paymentMethod) {
            throw new IdempotencyConflictException(
                    "Idempotency conflict: key was previously used with payment method "
                            + existing.getPaymentMethod() + ", but current request specifies " + paymentMethod);
        }

        if (existing.getOrderItems() != null && cartItems != null) {
            if (existing.getOrderItems().size() != cartItems.size()) {
                throw new IdempotencyConflictException(
                        "Idempotency conflict: key was previously used with "
                                + existing.getOrderItems().size() + " item(s), but current request has " + cartItems.size());
            }

            for (CartItemDto requestedItem : cartItems) {
                boolean foundMatch = existing.getOrderItems().stream()
                        .anyMatch(orderItem -> orderItem.getProduct() != null
                                && orderItem.getProduct().getId().equals(requestedItem.getProductId())
                                && orderItem.getQuantity().equals(requestedItem.getQuantity())
                                && Objects.equals(ProductVariant.normalizeSize(orderItem.getSelectedSize()), ProductVariant.normalizeSize(requestedItem.getSelectedSize()))
                                && Objects.equals(ProductVariant.normalizeColor(orderItem.getSelectedColor()), ProductVariant.normalizeColor(requestedItem.getSelectedColor())));
                if (!foundMatch) {
                    throw new IdempotencyConflictException(
                            "Idempotency conflict: key was previously used for different product items, variants, or quantities");
                }
            }
        }
    }

    private String generateOrderNumber() {
        // Generate order number with timestamp and random component
        long timestamp = System.currentTimeMillis();
        int random = (int) (Math.random() * 1000);
        return "KF" + timestamp + String.format("%03d", random);
    }
}
