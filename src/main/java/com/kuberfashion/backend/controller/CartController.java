package com.kuberfashion.backend.controller;

import com.kuberfashion.backend.dto.ApiResponse;
import com.kuberfashion.backend.dto.CartItemDto;
import com.kuberfashion.backend.entity.User;
import com.kuberfashion.backend.service.CartService;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/cart")
public class CartController {
    
    @Autowired
    private CartService cartService;
    
    @GetMapping
    public ResponseEntity<ApiResponse<List<CartItemDto>>> getCart(@AuthenticationPrincipal User user) {
        if (user == null) {
            return ResponseEntity.status(401)
                .body(ApiResponse.error("User not authenticated"));
        }
        List<CartItemDto> cartItems = cartService.getCartItems(user.getId());
        return ResponseEntity.ok(ApiResponse.success("Cart retrieved successfully", cartItems));
    }
    
    @PostMapping("/add")
    public ResponseEntity<ApiResponse<CartItemDto>> addToCart(
            @AuthenticationPrincipal User user,
            @Valid @RequestBody AddToCartRequest request) {
        if (user == null) {
            return ResponseEntity.status(401)
                .body(ApiResponse.error("User not authenticated"));
        }
        CartItemDto cartItem = cartService.addToCart(user.getId(), request.getProductId(), 
                request.getQuantity(), request.getSize(), request.getColor());
        return ResponseEntity.ok(ApiResponse.success("Item added to cart", cartItem));
    }
    
    @PutMapping("/items/{itemId}")
    public ResponseEntity<ApiResponse<CartItemDto>> updateCartItem(
            @AuthenticationPrincipal User user,
            @PathVariable Long itemId,
            @Valid @RequestBody UpdateCartItemRequest request) {
        if (user == null) {
            return ResponseEntity.status(401)
                .body(ApiResponse.error("User not authenticated"));
        }
        CartItemDto cartItem = cartService.updateCartItem(user.getId(), itemId, request.getQuantity());
        return ResponseEntity.ok(ApiResponse.success("Cart item updated", cartItem));
    }
    
    @DeleteMapping("/items/{itemId}")
    public ResponseEntity<ApiResponse<String>> removeFromCart(
            @AuthenticationPrincipal User user,
            @PathVariable Long itemId) {
        if (user == null) {
            return ResponseEntity.status(401)
                .body(ApiResponse.error("User not authenticated"));
        }
        cartService.removeFromCart(user.getId(), itemId);
        return ResponseEntity.ok(ApiResponse.success("Item removed from cart"));
    }
    
    @DeleteMapping
    public ResponseEntity<ApiResponse<String>> clearCart(@AuthenticationPrincipal User user) {
        if (user == null) {
            return ResponseEntity.status(401)
                .body(ApiResponse.error("User not authenticated"));
        }
        cartService.clearCart(user.getId());
        return ResponseEntity.ok(ApiResponse.success("Cart cleared successfully"));
    }
    
    @GetMapping("/count")
    public ResponseEntity<ApiResponse<Long>> getCartCount(@AuthenticationPrincipal User user) {
        if (user == null) {
            return ResponseEntity.status(401)
                .body(ApiResponse.error("User not authenticated"));
        }
        long count = cartService.getCartCount(user.getId());
        return ResponseEntity.ok(ApiResponse.success("Cart count retrieved", count));
    }
    
    // DTOs for requests
    public static class AddToCartRequest {
        private Long productId;
        private Integer quantity;
        private String size;
        private String color;
        
        // Getters and setters
        public Long getProductId() { return productId; }
        public void setProductId(Long productId) { this.productId = productId; }
        
        public Integer getQuantity() { return quantity; }
        public void setQuantity(Integer quantity) { this.quantity = quantity; }
        
        public String getSize() { return size; }
        public void setSize(String size) { this.size = size; }
        
        public String getColor() { return color; }
        public void setColor(String color) { this.color = color; }
    }
    
    public static class UpdateCartItemRequest {
        private Integer quantity;
        
        public Integer getQuantity() { return quantity; }
        public void setQuantity(Integer quantity) { this.quantity = quantity; }
    }
}
