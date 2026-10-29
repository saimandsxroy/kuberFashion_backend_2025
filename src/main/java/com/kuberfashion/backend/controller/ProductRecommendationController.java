package com.kuberfashion.backend.controller;

import com.kuberfashion.backend.dto.ApiResponse;
import com.kuberfashion.backend.dto.ProductResponseDto;
import com.kuberfashion.backend.entity.Product;
import com.kuberfashion.backend.entity.User;
import com.kuberfashion.backend.service.RecommendationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * REST controller exposing Personalized Product Recommendation endpoints.
 *
 * <p>All endpoints are mounted under {@code /api/products} and are therefore publicly accessible
 * per the existing Spring Security configuration ({@code .requestMatchers("/api/products/**").permitAll()}).
 *
 * <p>Customer identity is derived from the verified Spring Security principal.
 * Client-supplied user IDs are never trusted as proof of ownership.
 *
 * <p>Endpoints:
 * <ul>
 *   <li>{@code GET /api/products/recommendations} – "Recommended for You" (authenticated or guest fallback)</li>
 *   <li>{@code GET /api/products/{id}/similar}    – "Similar Products" for the product detail page</li>
 *   <li>{@code GET /api/products/cart-recommendations} – "You May Also Like" for the cart page</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/products")
@CrossOrigin(origins = {
        "http://localhost:5173", "http://127.0.0.1:5173",
        "https://kuberfashions.in", "https://www.kuberfashions.in"
})
public class ProductRecommendationController {

    private static final Logger logger = LoggerFactory.getLogger(ProductRecommendationController.class);

    /** Maximum limit the caller may request per endpoint invocation. */
    private static final int MAX_LIMIT   = 24;
    /** Default limit returned when the caller omits the limit parameter. */
    private static final int DEFAULT_LIMIT = 6;

    private final RecommendationService recommendationService;

    @Autowired
    public ProductRecommendationController(RecommendationService recommendationService) {
        this.recommendationService = recommendationService;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // A. "Recommended for You"
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Returns personalized product recommendations for the authenticated user.
     * Falls back to top-rated catalog for guests or users with empty history.
     *
     * @param limit number of recommendations (default 6, max 24)
     */
    @GetMapping("/recommendations")
    public ResponseEntity<ApiResponse<List<ProductResponseDto>>> getRecommendations(
            @RequestParam(defaultValue = "6") int limit) {

        int cap = clampLimit(limit);
        Long userId = extractUserId();

        logger.info("GET /api/products/recommendations — userId={}, limit={}", userId, cap);

        List<Product> products = recommendationService.getPersonalizedRecommendations(userId, cap);
        List<ProductResponseDto> dtos = toDto(products);
        return ResponseEntity.ok(ApiResponse.success("Recommendations retrieved successfully", dtos));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // B. "Similar Products"
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Returns products similar to the given product ID for the product detail page.
     * Uses stored pgvector embeddings (no runtime Gemini calls). Falls back to same-category relational matching.
     *
     * @param id    source product ID
     * @param limit number of similar products (default 6, max 24)
     */
    @GetMapping("/{id}/similar")
    public ResponseEntity<ApiResponse<List<ProductResponseDto>>> getSimilarProducts(
            @PathVariable Long id,
            @RequestParam(defaultValue = "6") int limit) {

        int cap = clampLimit(limit);
        logger.info("GET /api/products/{}/similar — limit={}", id, cap);

        List<Product> products = recommendationService.getSimilarProducts(id, cap);
        List<ProductResponseDto> dtos = toDto(products);
        return ResponseEntity.ok(ApiResponse.success("Similar products retrieved successfully", dtos));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // C. "You May Also Like" (Cart Recommendations)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Returns complementary product recommendations for the cart page.
     * For authenticated users, cart is loaded directly from DB (client IDs not trusted).
     * For guests, an optional comma-separated list of product IDs may be provided (validated, max 20).
     *
     * @param limit          number of recommendations (default 6, max 24)
     * @param cartProductIds optional comma-separated product IDs for guest carts
     */
    @GetMapping("/cart-recommendations")
    public ResponseEntity<ApiResponse<List<ProductResponseDto>>> getCartRecommendations(
            @RequestParam(defaultValue = "6") int limit,
            @RequestParam(required = false) String cartProductIds) {

        int cap = clampLimit(limit);
        Long userId = extractUserId();

        // Parse and validate guest cart IDs only when no authenticated user is present
        List<Long> guestCartIds = Collections.emptyList();
        if (userId == null && cartProductIds != null && !cartProductIds.isBlank()) {
            try {
                guestCartIds = Arrays.stream(cartProductIds.split(","))
                        .map(String::trim).filter(s -> !s.isEmpty())
                        .map(Long::parseLong)
                        .filter(id -> id > 0)
                        .distinct()
                        .limit(20)
                        .collect(Collectors.toList());
            } catch (NumberFormatException e) {
                logger.warn("Invalid cartProductIds format in guest request: {}", cartProductIds);
                guestCartIds = Collections.emptyList();
            }
        }

        logger.info("GET /api/products/cart-recommendations — userId={}, guestCartIds={}, limit={}",
                userId, guestCartIds.size(), cap);

        List<Product> products = recommendationService.getCartRecommendations(userId, guestCartIds, cap);
        List<ProductResponseDto> dtos = toDto(products);
        return ResponseEntity.ok(ApiResponse.success("Cart recommendations retrieved successfully", dtos));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Extracts the authenticated user ID from the Spring Security principal.
     * Returns null for unauthenticated (guest) requests.
     * Client-supplied user IDs in request bodies/params are NEVER used.
     */
    private Long extractUserId() {
        try {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth == null || !auth.isAuthenticated()) return null;
            Object principal = auth.getPrincipal();
            if (principal instanceof User user) {
                return user.getId();
            }
            // For anonymous authentication the principal is typically a String "anonymousUser"
            if ("anonymousUser".equals(principal)) return null;
        } catch (Exception e) {
            logger.debug("Could not extract user ID from security context: {}", e.getMessage());
        }
        return null;
    }

    private int clampLimit(int requested) {
        return Math.min(Math.max(requested, 1), MAX_LIMIT);
    }

    private List<ProductResponseDto> toDto(List<Product> products) {
        if (products == null) return Collections.emptyList();
        return products.stream().map(ProductResponseDto::new).collect(Collectors.toList());
    }
}
