package com.kuberfashion.backend.controller;

import com.kuberfashion.backend.dto.ApiResponse;
import com.kuberfashion.backend.dto.ProductResponseDto;
import com.kuberfashion.backend.entity.User;
import com.kuberfashion.backend.service.WishlistService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/wishlist")
public class WishlistController {
    
    @Autowired
    private WishlistService wishlistService;
    
    // DTO for add to wishlist request
    public static class AddToWishlistRequest {
        private Long productId;
        
        public Long getProductId() { return productId; }
        public void setProductId(Long productId) { this.productId = productId; }
    }
    
    @GetMapping
    public ResponseEntity<ApiResponse<List<ProductResponseDto>>> getUserWishlist(@AuthenticationPrincipal User user) {
        if (user == null) {
            return ResponseEntity.status(401)
                .body(ApiResponse.error("User not authenticated"));
        }
        List<ProductResponseDto> wishlistItems = wishlistService.getUserWishlist(user.getId());
        return ResponseEntity.ok(ApiResponse.success("Wishlist retrieved successfully", wishlistItems));
    }
    
    @PostMapping("/add/{productId}")
    public ResponseEntity<ApiResponse<ProductResponseDto>> addToWishlist(
            @AuthenticationPrincipal User user,
            @PathVariable Long productId) {
        if (user == null) {
            return ResponseEntity.status(401)
                .body(ApiResponse.error("User not authenticated"));
        }
        ProductResponseDto product = wishlistService.addToWishlist(user.getId(), productId);
        return ResponseEntity.ok(ApiResponse.success("Product added to wishlist", product));
    }
    
    @PostMapping("/add")
    public ResponseEntity<ApiResponse<ProductResponseDto>> addToWishlistWithBody(
            @AuthenticationPrincipal User user,
            @RequestBody AddToWishlistRequest request) {
        if (user == null) {
            return ResponseEntity.status(401)
                .body(ApiResponse.error("User not authenticated"));
        }
        ProductResponseDto product = wishlistService.addToWishlist(user.getId(), request.getProductId());
        return ResponseEntity.ok(ApiResponse.success("Product added to wishlist", product));
    }
    
    @DeleteMapping("/remove/{productId}")
    public ResponseEntity<ApiResponse<String>> removeFromWishlist(
            @AuthenticationPrincipal User user,
            @PathVariable Long productId) {
        if (user == null) {
            return ResponseEntity.status(401)
                .body(ApiResponse.error("User not authenticated"));
        }
        wishlistService.removeFromWishlist(user.getId(), productId);
        return ResponseEntity.ok(ApiResponse.success("Product removed from wishlist"));
    }
    
    @DeleteMapping("/clear")
    public ResponseEntity<ApiResponse<String>> clearWishlist(@AuthenticationPrincipal User user) {
        if (user == null) {
            return ResponseEntity.status(401)
                .body(ApiResponse.error("User not authenticated"));
        }
        wishlistService.clearWishlist(user.getId());
        return ResponseEntity.ok(ApiResponse.success("Wishlist cleared successfully"));
    }
    
    @GetMapping("/check/{productId}")
    public ResponseEntity<ApiResponse<Boolean>> isInWishlist(
            @AuthenticationPrincipal User user,
            @PathVariable Long productId) {
        if (user == null) {
            return ResponseEntity.status(401)
                .body(ApiResponse.error("User not authenticated"));
        }
        boolean isInWishlist = wishlistService.isInWishlist(user.getId(), productId);
        return ResponseEntity.ok(ApiResponse.success("Wishlist status checked", isInWishlist));
    }
    
    @GetMapping("/count")
    public ResponseEntity<ApiResponse<Long>> getWishlistCount(@AuthenticationPrincipal User user) {
        if (user == null) {
            return ResponseEntity.status(401)
                .body(ApiResponse.error("User not authenticated"));
        }
        long count = wishlistService.getWishlistCount(user.getId());
        return ResponseEntity.ok(ApiResponse.success("Wishlist count retrieved", count));
    }
}
