package com.kuberfashion.backend.controller;

import com.kuberfashion.backend.dto.ApiResponse;
import com.kuberfashion.backend.dto.CartItemDto;
import com.kuberfashion.backend.entity.Order;
import com.kuberfashion.backend.entity.User;
import com.kuberfashion.backend.service.OrderService;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/orders")
@CrossOrigin(origins = { "http://localhost:5173", "http://127.0.0.1:5173", "https://kuberfashions.in",
        "https://www.kuberfashions.in" })
public class OrderController {

    @Autowired
    private OrderService orderService;

    @PostMapping("/create")
    public ResponseEntity<ApiResponse<Order>> createOrder(
            @AuthenticationPrincipal User user,
            @Valid @RequestBody CreateOrderRequest request) {
        try {
            if (user == null) {
                return ResponseEntity.status(401)
                        .body(ApiResponse.error("Authentication required. Please log in."));
            }
            if (request.getCartItems() == null || request.getCartItems().isEmpty()) {
                return ResponseEntity.badRequest()
                        .body(ApiResponse.error("Cart is empty. Please add items before checkout."));
            }
            if (request.getAddressId() == null) {
                return ResponseEntity.badRequest()
                        .body(ApiResponse.error("Please select a shipping address."));
            }

            // Determine purchaseMode - default to ONLINE if not specified
            Order.PurchaseMode purchaseMode = Order.PurchaseMode.ONLINE;
            if (request.getPurchaseMode() != null && !request.getPurchaseMode().isEmpty()) {
                purchaseMode = Order.PurchaseMode.valueOf(request.getPurchaseMode().toUpperCase());
            }

            Order order = orderService.createOrder(
                    user.getId(),
                    request.getCartItems(),
                    request.getAddressId(),
                    Order.PaymentMethod.valueOf(request.getPaymentMethod().toUpperCase()),
                    request.getReferralCode(),
                    purchaseMode,
                    request.getIdempotencyKey());
            return ResponseEntity.ok(ApiResponse.success("Order created successfully", order));
        } catch (com.kuberfashion.backend.exception.IdempotencyConflictException e) {
            return ResponseEntity.status(org.springframework.http.HttpStatus.CONFLICT)
                    .body(ApiResponse.error(e.getMessage()));
        } catch (com.kuberfashion.backend.exception.InsufficientStockException |
                 com.kuberfashion.backend.exception.ResourceNotFoundException |
                 IllegalArgumentException e) {
            return ResponseEntity.badRequest()
                    .body(ApiResponse.error(e.getMessage()));
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            if (request.getIdempotencyKey() != null && !request.getIdempotencyKey().trim().isEmpty()) {
                Order existing = orderService.getOrderByIdempotencyKeyAndUserId(request.getIdempotencyKey().trim(), user.getId())
                        .orElse(null);
                if (existing != null) {
                    try {
                        orderService.validateIdempotentPayload(
                                existing,
                                request.getCartItems(),
                                Order.PaymentMethod.valueOf(request.getPaymentMethod().toUpperCase()));
                        return ResponseEntity.ok(ApiResponse.success("Order created successfully", existing));
                    } catch (com.kuberfashion.backend.exception.IdempotencyConflictException conflictEx) {
                        return ResponseEntity.status(org.springframework.http.HttpStatus.CONFLICT)
                                .body(ApiResponse.error(conflictEx.getMessage()));
                    }
                }
            }
            return ResponseEntity.status(org.springframework.http.HttpStatus.CONFLICT)
                    .body(ApiResponse.error("Order creation conflict: duplicate request"));
        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.internalServerError()
                    .body(ApiResponse.error("Order creation failed: " + e.getMessage()));
        }
    }

    @GetMapping("/{orderId}")
    public ResponseEntity<ApiResponse<Order>> getOrder(
            @AuthenticationPrincipal User user,
            @PathVariable Long orderId) {
        Order order = orderService.getOrderByIdAndUserId(orderId, user.getId())
                .orElseThrow(() -> new RuntimeException("Order not found"));
        return ResponseEntity.ok(ApiResponse.success("Order retrieved successfully", order));
    }

    @GetMapping("/my-orders")
    public ResponseEntity<ApiResponse<Page<Order>>> getUserOrders(
            @AuthenticationPrincipal User user,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {
        Pageable pageable = PageRequest.of(page, size);
        Page<Order> orders = orderService.getUserOrders(user.getId(), pageable);
        return ResponseEntity.ok(ApiResponse.success("Orders retrieved successfully", orders));
    }

    @PutMapping("/{orderId}/cancel")
    public ResponseEntity<ApiResponse<String>> cancelOrder(
            @AuthenticationPrincipal User user,
            @PathVariable Long orderId) {
        orderService.cancelOrder(orderId, user.getId());
        return ResponseEntity.ok(ApiResponse.success("Order cancelled successfully"));
    }

    // Admin endpoints
    @GetMapping("/admin/all")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<Page<Order>>> getAllOrders(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {
        Pageable pageable = PageRequest.of(page, size);
        Page<Order> orders = orderService.getAllOrders(pageable);
        return ResponseEntity.ok(ApiResponse.success("All orders retrieved successfully", orders));
    }

    @GetMapping("/admin/status/{status}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<List<Order>>> getOrdersByStatus(@PathVariable String status) {
        Order.OrderStatus orderStatus = Order.OrderStatus.valueOf(status.toUpperCase());
        List<Order> orders = orderService.getOrdersByStatus(orderStatus);
        return ResponseEntity.ok(ApiResponse.success("Orders by status retrieved successfully", orders));
    }

    @PutMapping("/admin/{orderId}/status")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<Order>> updateOrderStatus(
            @PathVariable Long orderId,
            @RequestBody Map<String, String> request) {
        Order.OrderStatus status = Order.OrderStatus.valueOf(request.get("status").toUpperCase());
        Order order = orderService.updateOrderStatus(orderId, status);
        return ResponseEntity.ok(ApiResponse.success("Order status updated successfully", order));
    }

    @PutMapping("/admin/{orderId}/payment-status")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<Order>> updatePaymentStatus(
            @PathVariable Long orderId,
            @RequestBody Map<String, String> request) {
        Order.PaymentStatus paymentStatus = Order.PaymentStatus.valueOf(request.get("paymentStatus").toUpperCase());
        Order order = orderService.updatePaymentStatus(orderId, paymentStatus);
        return ResponseEntity.ok(ApiResponse.success("Payment status updated successfully", order));
    }

    // DTO for create order request
    public static class CreateOrderRequest {
        private List<CartItemDto> cartItems;
        private Long addressId; // Link to saved address
        private String paymentMethod; // PHONEPE or CASH_ON_DELIVERY
        private String referralCode; // For KVision membership

        public CreateOrderRequest() {}

        public CreateOrderRequest(List<CartItemDto> cartItems, Long addressId, String paymentMethod,
                                  String referralCode, String purchaseMode, String idempotencyKey) {
            this.cartItems = cartItems;
            this.addressId = addressId;
            this.paymentMethod = paymentMethod;
            this.referralCode = referralCode;
            this.purchaseMode = purchaseMode;
            this.idempotencyKey = idempotencyKey;
        }

        // Getters and setters
        public List<CartItemDto> getCartItems() {
            return cartItems;
        }

        public void setCartItems(List<CartItemDto> cartItems) {
            this.cartItems = cartItems;
        }

        public Long getAddressId() {
            return addressId;
        }

        public void setAddressId(Long addressId) {
            this.addressId = addressId;
        }

        public String getPaymentMethod() {
            return paymentMethod;
        }

        public void setPaymentMethod(String paymentMethod) {
            this.paymentMethod = paymentMethod;
        }

        public String getReferralCode() {
            return referralCode;
        }

        public void setReferralCode(String referralCode) {
            this.referralCode = referralCode;
        }

        private String purchaseMode; // ONLINE or OFFLINE for KVision products
        private String idempotencyKey;

        public String getPurchaseMode() {
            return purchaseMode;
        }

        public void setPurchaseMode(String purchaseMode) {
            this.purchaseMode = purchaseMode;
        }

        public String getIdempotencyKey() {
            return idempotencyKey;
        }

        public void setIdempotencyKey(String idempotencyKey) {
            this.idempotencyKey = idempotencyKey;
        }
    }
}
