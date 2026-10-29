package com.kuberfashion.backend.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Integration tests for {@link ProductRecommendationController}.
 *
 * Verifies:
 * - All three endpoints are publicly accessible (no 401/403 for unauthenticated requests)
 * - Response format matches the standard ApiResponse<List<ProductResponseDto>> envelope
 * - Limit parameter is respected (capped to max 24)
 * - Invalid productId returns non-500 response
 * - Guest cart-recommendations with invalid cartProductIds format does not crash
 * - Product data in responses never includes null IDs
 *
 * Note: Personalization logic (user-specific signals) is tested in RecommendationServiceTest (unit).
 * These tests focus on HTTP behavior, authorization, and response format correctness.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
@DisplayName("ProductRecommendationController Integration Tests")
class ProductRecommendationControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    // =========================================================================
    // A. /api/products/recommendations
    // =========================================================================

    @Test
    @DisplayName("1. GET /api/products/recommendations is publicly accessible (no auth required)")
    void recommendationsIsPubliclyAccessible() throws Exception {
        mockMvc.perform(get("/api/products/recommendations"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").isArray());
    }

    @Test
    @DisplayName("2. GET /api/products/recommendations returns at most the requested limit")
    void recommendationsRespectsLimit() throws Exception {
        mockMvc.perform(get("/api/products/recommendations").param("limit", "3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(lessThanOrEqualTo(3)));
    }

    @Test
    @DisplayName("3. GET /api/products/recommendations with limit > 24 is capped to 24")
    void recommendationsLimitCappedAt24() throws Exception {
        mockMvc.perform(get("/api/products/recommendations").param("limit", "100"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(lessThanOrEqualTo(24)));
    }

    @Test
    @DisplayName("4. Recommendation response products have non-null IDs")
    void recommendationProductsHaveNonNullIds() throws Exception {
        mockMvc.perform(get("/api/products/recommendations").param("limit", "6"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[*].id").value(everyItem(notNullValue())));
    }

    // =========================================================================
    // B. /api/products/{id}/similar
    // =========================================================================

    @Test
    @DisplayName("5. GET /api/products/1/similar is publicly accessible")
    void similarProductsIsPubliclyAccessible() throws Exception {
        mockMvc.perform(get("/api/products/1/similar"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").isArray());
    }

    @Test
    @DisplayName("6. Similar products for non-existent product returns empty list (not 500)")
    void similarProductsForNonExistentProductReturnsEmptyList() throws Exception {
        mockMvc.perform(get("/api/products/999999/similar"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").isArray());
    }

    @Test
    @DisplayName("7. Similar products respects limit parameter")
    void similarProductsRespectsLimit() throws Exception {
        mockMvc.perform(get("/api/products/1/similar").param("limit", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(lessThanOrEqualTo(2)));
    }

    @Test
    @DisplayName("8. Similar products does not include source product in results")
    void similarProductsDoesNotIncludeSourceProduct() throws Exception {
        // Product with ID 1 must not appear in its own similar products list
        mockMvc.perform(get("/api/products/1/similar").param("limit", "24"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[*].id").value(everyItem(not(1))));
    }

    // =========================================================================
    // C. /api/products/cart-recommendations
    // =========================================================================

    @Test
    @DisplayName("9. GET /api/products/cart-recommendations is publicly accessible")
    void cartRecommendationsIsPubliclyAccessible() throws Exception {
        mockMvc.perform(get("/api/products/cart-recommendations"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").isArray());
    }

    @Test
    @DisplayName("10. Cart recommendations with invalid cartProductIds does not throw 500")
    void cartRecommendationsWithInvalidIdsDoesNotCrash() throws Exception {
        mockMvc.perform(get("/api/products/cart-recommendations")
                        .param("cartProductIds", "not,a,number"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").isArray());
    }

    @Test
    @DisplayName("11. Cart recommendations with valid guest cart IDs returns products excluding those IDs")
    void cartRecommendationsWithGuestCartIdsExcludesCartProducts() throws Exception {
        // If product ID 1 is in the guest cart, it must not appear in recommendations
        mockMvc.perform(get("/api/products/cart-recommendations")
                        .param("cartProductIds", "1")
                        .param("limit", "24"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[*].id").value(everyItem(not(1))));
    }

    @Test
    @DisplayName("12. Cart recommendations limit is capped at 24")
    void cartRecommendationsLimitCapped() throws Exception {
        mockMvc.perform(get("/api/products/cart-recommendations").param("limit", "50"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(lessThanOrEqualTo(24)));
    }

    @Test
    @DisplayName("13. Negative limit is handled gracefully (treated as 1)")
    void negativeOrZeroLimitHandledGracefully() throws Exception {
        mockMvc.perform(get("/api/products/recommendations").param("limit", "-5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(lessThanOrEqualTo(1)));
    }
}
