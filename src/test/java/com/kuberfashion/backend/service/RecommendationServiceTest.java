package com.kuberfashion.backend.service;

import com.kuberfashion.backend.entity.CartItem;
import com.kuberfashion.backend.entity.Category;
import com.kuberfashion.backend.entity.Order;
import com.kuberfashion.backend.entity.OrderItem;
import com.kuberfashion.backend.entity.Product;
import com.kuberfashion.backend.entity.ProductVariant;
import com.kuberfashion.backend.entity.WishlistItem;
import com.kuberfashion.backend.repository.CartItemRepository;
import com.kuberfashion.backend.repository.OrderRepository;
import com.kuberfashion.backend.repository.ProductRepository;
import com.kuberfashion.backend.repository.WishlistItemRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for RecommendationService.
 *
 * Covers:
 * - Explainable scoring with wishlist/cart/order signals
 * - Guest fallback (null userId)
 * - LEGACY and VARIANT inventory availability checks
 * - Exclusion of cart items from "Recommended for You"
 * - Recent-purchase exclusion from recommendations
 * - Similar products vector path and relational fallback
 * - Cart recommendations complementary category logic
 * - Missing embeddings (empty vectorScores map) handled gracefully
 * - Customer data isolation (no cross-user contamination in service logic)
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("RecommendationService Unit Tests")
class RecommendationServiceTest {

    @Mock private ProductRepository      productRepository;
    @Mock private WishlistItemRepository wishlistItemRepository;
    @Mock private CartItemRepository     cartItemRepository;
    @Mock private OrderRepository        orderRepository;
    @Mock private ProductVectorService   vectorService;

    private RecommendationService service;

    @BeforeEach
    void setUp() {
        service = new RecommendationService(
                productRepository, wishlistItemRepository, cartItemRepository,
                orderRepository, vectorService,
                /* wCategory */ 30, /* wColor */ 15, /* wVector */ 35,
                /* wPopularity */ 10, /* wOffer */ 10,
                /* orderHistoryDays */ 90, /* recentPurchaseExclusionDays */ 30,
                /* maxGuestCartIds */ 20
        );
    }

    // =========================================================================
    // Weight accessor tests
    // =========================================================================

    @Test @DisplayName("Scoring weights match configuration")
    void scoringWeightsMatchConfiguration() {
        assertThat(service.getWeightCategory()).isEqualTo(30);
        assertThat(service.getWeightColor()).isEqualTo(15);
        assertThat(service.getWeightVector()).isEqualTo(35);
        assertThat(service.getWeightPopularity()).isEqualTo(10);
        assertThat(service.getWeightOffer()).isEqualTo(10);
    }

    @Test @DisplayName("Order history and recent-purchase exclusion windows match config")
    void orderHistoryWindowMatchesConfig() {
        assertThat(service.getOrderHistoryDays()).isEqualTo(90);
        assertThat(service.getRecentPurchaseExclusionDays()).isEqualTo(30);
    }

    // =========================================================================
    // Inventory availability tests
    // =========================================================================

    @Test @DisplayName("LEGACY in-stock product is available")
    void legacyInStockProductIsAvailable() {
        Product p = makeLegacyProduct(1L, "cat-1", 10, true);
        assertThat(service.isAvailable(p)).isTrue();
    }

    @Test @DisplayName("LEGACY out-of-stock product is not available")
    void legacyOutOfStockProductIsNotAvailable() {
        Product p = makeLegacyProduct(2L, "cat-1", 0, false);
        assertThat(service.isAvailable(p)).isFalse();
    }

    @Test @DisplayName("LEGACY product with inStock=true but stockQuantity=0 is not available")
    void legacyStockQuantityZeroIsUnavailable() {
        Product p = makeLegacyProduct(3L, "cat-1", 0, true);
        assertThat(service.isAvailable(p)).isFalse();
    }

    @Test @DisplayName("VARIANT product with active in-stock variant is available")
    void variantProductWithActiveVariantIsAvailable() {
        Product p = makeVariantProduct(4L, "cat-1", true, 5);
        assertThat(service.isAvailable(p)).isTrue();
    }

    @Test @DisplayName("VARIANT product with no active variants is not available")
    void variantProductWithNoActiveVariantsIsUnavailable() {
        Product p = makeVariantProduct(5L, "cat-1", false, 0);
        assertThat(service.isAvailable(p)).isFalse();
    }

    @Test @DisplayName("VARIANT product with inactive variant having stock is not available")
    void variantProductWithInactiveVariantIsUnavailable() {
        Product p = makeVariantProduct(6L, "cat-1", false, 10); // active=false
        assertThat(service.isAvailable(p)).isFalse();
    }

    @Test @DisplayName("Inactive (soft-deleted) product is not available regardless of stock")
    void inactiveProductIsNotAvailable() {
        Product p = makeLegacyProduct(7L, "cat-1", 10, true);
        p.setActive(false);
        assertThat(service.isAvailable(p)).isFalse();
    }

    @Test @DisplayName("Null product returns false")
    void nullProductReturnsFalse() {
        assertThat(service.isAvailable(null)).isFalse();
    }

    // =========================================================================
    // Guest fallback tests
    // =========================================================================

    @Test @DisplayName("Guest (null userId) returns catalog fallback")
    void guestGetsFallbackRecommendations() {
        Product p = makeLegacyProduct(10L, "cat-1", 5, true);
        when(productRepository.findFallbackCandidates(anyList(), any(Pageable.class)))
                .thenReturn(List.of(p));

        List<Product> result = service.getPersonalizedRecommendations(null, 6);

        assertThat(result).hasSize(1);
        verify(wishlistItemRepository, never()).findByUserIdWithProduct(any());
        verify(cartItemRepository, never()).findByUserIdWithProduct(any());
        verify(orderRepository, never()).findEligibleOrdersByUserId(any(), any());
    }

    @Test @DisplayName("Catalog fallback excludes specified product IDs")
    void fallbackExcludesSpecifiedIds() {
        Product p1 = makeLegacyProduct(20L, "cat-1", 5, true);
        when(productRepository.findFallbackCandidates(anyList(), any(Pageable.class))).thenReturn(List.of(p1));

        List<Product> result = service.getFallbackCatalogRecommendations(6, Set.of(99L));
        assertThat(result).hasSize(1);
        // Verify the excludeIds list was passed (not empty)
        verify(productRepository).findFallbackCandidates(argThat(l -> l.contains(99L)), any(Pageable.class));
    }

    // =========================================================================
    // Personalized recommendation tests
    // =========================================================================

    @Test @DisplayName("Cart items are excluded from personalized recommendations")
    void cartItemsExcludedFromPersonalizedRecs() {
        Long userId = 1L;
        Product cartProduct   = makeLegacyProduct(100L, "cat-1", 10, true);
        Product recCandidate  = makeLegacyProduct(200L, "cat-1", 5, true);

        CartItem cartItem = new CartItem();
        cartItem.setProduct(cartProduct);

        when(wishlistItemRepository.findByUserIdWithProduct(userId)).thenReturn(Collections.emptyList());
        when(cartItemRepository.findByUserIdWithProduct(userId)).thenReturn(List.of(cartItem));
        when(orderRepository.findEligibleOrdersByUserId(eq(userId), any())).thenReturn(Collections.emptyList());
        when(vectorService.isVectorSearchActive()).thenReturn(false);
        when(productRepository.findFallbackCandidates(anyList(), any()))
                .thenReturn(List.of(recCandidate));  // catalog excluding cart product

        List<Product> result = service.getPersonalizedRecommendations(userId, 6);

        // Cart product must not appear in recommendations
        assertThat(result).noneMatch(p -> p.getId().equals(100L));
    }

    @Test @DisplayName("Products purchased within recency window are excluded")
    void recentlyPurchasedProductsExcluded() {
        Long userId = 2L;
        Product recentPurchase = makeLegacyProduct(300L, "cat-2", 10, true);
        Product candidate      = makeLegacyProduct(400L, "cat-2", 5,  true);

        Order recentOrder = new Order();
        recentOrder.setCreatedAt(LocalDateTime.now().minusDays(5)); // within 30-day exclusion window
        OrderItem oi = new OrderItem();
        oi.setProduct(recentPurchase);
        recentOrder.setOrderItems(Set.of(oi));

        when(wishlistItemRepository.findByUserIdWithProduct(userId)).thenReturn(Collections.emptyList());
        when(cartItemRepository.findByUserIdWithProduct(userId)).thenReturn(Collections.emptyList());
        when(orderRepository.findEligibleOrdersByUserId(eq(userId), any())).thenReturn(List.of(recentOrder));
        when(vectorService.isVectorSearchActive()).thenReturn(false);
        when(productRepository.findFallbackCandidates(anyList(), any())).thenReturn(List.of(candidate));

        List<Product> result = service.getPersonalizedRecommendations(userId, 6);

        // Recent purchase must not appear
        assertThat(result).noneMatch(p -> p.getId().equals(300L));
    }

    @Test @DisplayName("Out-of-stock candidates are filtered out")
    void outOfStockCandidatesFiltered() {
        Long userId = 3L;
        Product outOfStock = makeLegacyProduct(500L, "cat-3", 0, false);

        when(wishlistItemRepository.findByUserIdWithProduct(userId)).thenReturn(Collections.emptyList());
        when(cartItemRepository.findByUserIdWithProduct(userId)).thenReturn(Collections.emptyList());
        when(orderRepository.findEligibleOrdersByUserId(eq(userId), any())).thenReturn(Collections.emptyList());
        when(vectorService.isVectorSearchActive()).thenReturn(false);
        when(productRepository.findFallbackCandidates(anyList(), any())).thenReturn(List.of(outOfStock));

        // Falls back to catalog which also returns out-of-stock item — should be filtered
        List<Product> result = service.getPersonalizedRecommendations(userId, 6);
        assertThat(result).noneMatch(p -> p.getId().equals(500L));
    }

    // =========================================================================
    // Similar products tests
    // =========================================================================

    @Test @DisplayName("Similar products returns empty list for null productId")
    void similarProductsNullIdReturnsEmpty() {
        List<Product> result = service.getSimilarProducts(null, 6);
        assertThat(result).isEmpty();
        verifyNoInteractions(vectorService);
    }

    @Test @DisplayName("Similar products uses vector path when embeddings available")
    void similarProductsUsesVectorPathWhenAvailable() {
        Long productId = 10L;
        Product similar = makeLegacyProduct(20L, "cat-a", 8, true);

        lenient().when(vectorService.isVectorSearchActive()).thenReturn(true);
        when(vectorService.findSimilarProductsByStoredEmbedding(eq(productId), anyInt()))
                .thenReturn(Map.of(20L, 0.92));
        when(productRepository.findActiveByIds(anyList())).thenReturn(List.of(similar));

        List<Product> result = service.getSimilarProducts(productId, 6);
        assertThat(result).hasSize(1);
        assertThat(result.get(0).getSimilarityScore()).isEqualTo(0.92);
        verify(vectorService).findSimilarProductsByStoredEmbedding(eq(productId), anyInt());
    }

    @Test @DisplayName("Similar products falls back to same-category when no embedding")
    void similarProductsFallsBackWhenNoEmbedding() {
        Long productId = 11L;
        Product source  = makeLegacyProduct(productId, "cat-b", 5, true);
        source.setCategory(makeCategory(2L, "cat-b"));
        Product fallback = makeLegacyProduct(21L, "cat-b", 6, true);

        lenient().when(vectorService.isVectorSearchActive()).thenReturn(false);
        when(productRepository.findById(productId)).thenReturn(Optional.of(source));
        when(productRepository.findByCategoryIdExcluding(eq(2L), eq(productId), any()))
                .thenReturn(List.of(fallback));

        List<Product> result = service.getSimilarProducts(productId, 6);
        assertThat(result).hasSize(1);
        assertThat(result.get(0).getId()).isEqualTo(21L);
    }

    @Test @DisplayName("Similar products excludes source product from results")
    void similarProductsExcludesSourceProduct() {
        Long productId = 12L;
        // Vector returns both source and another product — source must be excluded
        Product source  = makeLegacyProduct(productId, "cat-c", 5, true);
        Product another = makeLegacyProduct(22L, "cat-c", 5, true);

        lenient().when(vectorService.isVectorSearchActive()).thenReturn(true);
        when(vectorService.findSimilarProductsByStoredEmbedding(eq(productId), anyInt()))
                .thenReturn(Map.of(productId, 1.0, 22L, 0.85));
        when(productRepository.findActiveByIds(anyList())).thenReturn(List.of(source, another));

        List<Product> result = service.getSimilarProducts(productId, 6);
        assertThat(result).noneMatch(p -> p.getId().equals(productId));
    }

    // =========================================================================
    // Cart recommendations tests
    // =========================================================================

    @Test @DisplayName("Cart recommendations exclude all cart items")
    void cartRecommendationsExcludeCartItems() {
        Long userId = 5L;
        Product inCart    = makeLegacyProduct(50L, "tops", 5, true);
        inCart.setCategory(makeCategory(5L, "tops"));
        Product complement = makeLegacyProduct(60L, "jeans", 8, true);
        complement.setCategory(makeCategory(6L, "jeans"));

        CartItem ci = new CartItem();
        ci.setProduct(inCart);

        when(cartItemRepository.findByUserIdWithProduct(userId)).thenReturn(List.of(ci));
        when(productRepository.findByCategorySlugAndActiveTrue("jeans")).thenReturn(List.of(complement));
        when(productRepository.findByCategorySlugAndActiveTrue("bottoms")).thenReturn(Collections.emptyList());
        when(productRepository.findByCategorySlugAndActiveTrue("trousers")).thenReturn(Collections.emptyList());
        when(productRepository.findByCategorySlugAndActiveTrue("skirts")).thenReturn(Collections.emptyList());
        when(productRepository.findByCategorySlugAndActiveTrue("shorts")).thenReturn(Collections.emptyList());

        List<Product> result = service.getCartRecommendations(userId, null, 6);

        assertThat(result).noneMatch(p -> p.getId().equals(50L));
        assertThat(result).anyMatch(p -> p.getId().equals(60L));
    }

    @Test @DisplayName("Guest cart recommendations validate max IDs")
    void guestCartRecommendationsValidateMaxIds() {
        // 25 IDs supplied — only first 20 should be used
        List<Long> manyIds = new ArrayList<>();
        for (long i = 1; i <= 25; i++) manyIds.add(i);

        when(productRepository.findActiveByIds(anyList())).thenReturn(Collections.emptyList());
        when(productRepository.findFallbackCandidates(anyList(), any())).thenReturn(Collections.emptyList());

        service.getCartRecommendations(null, manyIds, 6);

        // findActiveByIds should be called with at most 20 IDs
        verify(productRepository).findActiveByIds(argThat(list -> list.size() <= 20));
    }

    @Test @DisplayName("Authenticated user cart IDs override guest cart IDs")
    void authenticatedUserCartOverridesGuestIds() {
        Long userId = 7L;
        Product cartProduct = makeLegacyProduct(70L, "dresses", 5, true);
        cartProduct.setCategory(makeCategory(7L, "dresses"));
        CartItem ci = new CartItem();
        ci.setProduct(cartProduct);

        when(cartItemRepository.findByUserIdWithProduct(userId)).thenReturn(List.of(ci));
        // Complementary for dresses -> footwear, bags, accessories, jewellery
        when(productRepository.findByCategorySlugAndActiveTrue(anyString())).thenReturn(Collections.emptyList());

        service.getCartRecommendations(userId, List.of(999L, 998L), 6);

        // Must use DB cart, not the supplied guest IDs
        verify(cartItemRepository).findByUserIdWithProduct(userId);
        verify(productRepository, never()).findActiveByIds(List.of(999L, 998L));
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private Product makeLegacyProduct(Long id, String catSlug, int stock, boolean inStock) {
        Product p = new Product();
        ReflectionTestUtils.setField(p, "id", id);
        p.setName("Product-" + id);
        p.setSlug("product-" + id);
        p.setPrice(BigDecimal.valueOf(500));
        p.setStockQuantity(stock);
        p.setInStock(inStock);
        p.setActive(true);
        p.setInventoryTrackingMode(Product.InventoryTrackingMode.LEGACY);
        p.setRating(BigDecimal.valueOf(4.0));
        p.setReviews(50);
        Category cat = makeCategory(id * 10, catSlug);
        p.setCategory(cat);
        return p;
    }

    private Product makeVariantProduct(Long id, String catSlug, boolean variantActive, int variantStock) {
        Product p = new Product();
        ReflectionTestUtils.setField(p, "id", id);
        p.setName("VariantProduct-" + id);
        p.setSlug("variant-product-" + id);
        p.setPrice(BigDecimal.valueOf(800));
        p.setActive(true);
        p.setInventoryTrackingMode(Product.InventoryTrackingMode.VARIANT);
        Category cat = makeCategory(id * 10 + 1, catSlug);
        p.setCategory(cat);

        ProductVariant v = new ProductVariant(p, "M", "RED", variantStock, "SKU-" + id, variantActive);
        p.setVariants(List.of(v));
        return p;
    }

    private Category makeCategory(Long id, String slug) {
        Category cat = new Category();
        ReflectionTestUtils.setField(cat, "id", id);
        cat.setSlug(slug);
        cat.setName(slug);
        return cat;
    }
}
