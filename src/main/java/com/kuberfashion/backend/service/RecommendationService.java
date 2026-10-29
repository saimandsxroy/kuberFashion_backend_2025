package com.kuberfashion.backend.service;

import com.kuberfashion.backend.entity.*;
import com.kuberfashion.backend.repository.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Explainable, multi-factor Personalized Product Recommendation Engine.
 *
 * <h3>Three Recommendation Experiences</h3>
 * <ul>
 *   <li><b>Recommended for You</b> – personalized via wishlist/cart/order signals + vector similarity</li>
 *   <li><b>Similar Products</b> – nearest-neighbor search using stored pgvector embeddings (zero runtime LLM calls)</li>
 *   <li><b>Cart Recommendations</b> – cross-category complements, strictly excluding cart items</li>
 * </ul>
 *
 * <h3>Scoring Weights (configurable, sum = 100)</h3>
 * <pre>
 *  Category affinity  : 30
 *  Vector similarity  : 35
 *  Color affinity     : 15
 *  Catalog popularity : 10  (48/58 products have rating+reviews; omitted per-product where zero)
 *  Offer boost        : 10
 * </pre>
 *
 * <h3>Inventory Correctness</h3>
 * LEGACY: inStock=true AND stockQuantity>0
 * VARIANT: at least one active variant with stockQuantity>0
 *
 * <h3>Data Isolation</h3>
 * Identity derived from Spring Security principal only. No shared cross-user caches.
 *
 * <h3>Recent-Purchase Exclusion</h3>
 * Products purchased within recentPurchaseExclusionDays (default 30) in eligible orders
 * (NOT CANCELLED, NOT RETURNED, paymentStatus != FAILED) are excluded from recommendations.
 */
@Service
public class RecommendationService {

    private static final Logger logger = LoggerFactory.getLogger(RecommendationService.class);

    /** Complementary category slug mappings for cart recommendations */
    private static final Map<String, List<String>> COMPLEMENTARY_CATEGORIES;
    static {
        Map<String, List<String>> m = new HashMap<>();
        m.put("tops",           List.of("bottoms", "jeans", "trousers", "skirts", "shorts"));
        m.put("bottom",         List.of("tops", "tshirts", "shirts", "blouses"));
        m.put("jeans",          List.of("tops", "tshirts", "shirts", "footwear", "bags"));
        m.put("dresses",        List.of("footwear", "bags", "accessories", "jewellery"));
        m.put("ethnic-wear",    List.of("footwear", "bags", "accessories", "jewellery", "dupattas"));
        m.put("kurta",          List.of("footwear", "bags", "accessories", "dupattas", "jeans"));
        m.put("sarees",         List.of("blouse", "jewellery", "footwear", "bags"));
        m.put("western-wear",   List.of("footwear", "bags", "accessories"));
        m.put("footwear",       List.of("bags", "dresses", "jeans", "kurta"));
        m.put("bags",           List.of("footwear", "dresses", "accessories"));
        m.put("lingerie",       List.of("sleepwear", "loungewear"));
        m.put("sleepwear",      List.of("lingerie", "loungewear"));
        COMPLEMENTARY_CATEGORIES = Collections.unmodifiableMap(m);
    }

    private final ProductRepository      productRepository;
    private final WishlistItemRepository wishlistItemRepository;
    private final CartItemRepository     cartItemRepository;
    private final OrderRepository        orderRepository;
    private final ProductVectorService   vectorService;

    private final int wCategory;
    private final int wColor;
    private final int wVector;
    private final int wPopularity;
    private final int wOffer;
    private final int orderHistoryDays;
    private final int recentPurchaseExclusionDays;
    private final int maxGuestCartIds;

    @Autowired
    public RecommendationService(
            ProductRepository productRepository,
            WishlistItemRepository wishlistItemRepository,
            CartItemRepository cartItemRepository,
            OrderRepository orderRepository,
            ProductVectorService vectorService,
            @Value("${recommendations.weight.category:30}") int wCategory,
            @Value("${recommendations.weight.color:15}") int wColor,
            @Value("${recommendations.weight.vector:35}") int wVector,
            @Value("${recommendations.weight.popularity:10}") int wPopularity,
            @Value("${recommendations.weight.offer:10}") int wOffer,
            @Value("${recommendations.order-history-days:90}") int orderHistoryDays,
            @Value("${recommendations.recent-purchase-exclusion-days:30}") int recentPurchaseExclusionDays,
            @Value("${recommendations.max-guest-cart-ids:20}") int maxGuestCartIds) {
        this.productRepository           = productRepository;
        this.wishlistItemRepository      = wishlistItemRepository;
        this.cartItemRepository          = cartItemRepository;
        this.orderRepository             = orderRepository;
        this.vectorService               = vectorService;
        this.wCategory                   = wCategory;
        this.wColor                      = wColor;
        this.wVector                     = wVector;
        this.wPopularity                 = wPopularity;
        this.wOffer                      = wOffer;
        this.orderHistoryDays            = orderHistoryDays;
        this.recentPurchaseExclusionDays = recentPurchaseExclusionDays;
        this.maxGuestCartIds             = maxGuestCartIds;
    }

    // =========================================================================
    // A. RECOMMENDED FOR YOU
    // =========================================================================

    public List<Product> getPersonalizedRecommendations(Long userId, int limit) {
        int cap = Math.min(Math.max(limit, 1), 24);
        if (userId == null) {
            logger.debug("Guest request — serving catalog fallback");
            return getFallbackCatalogRecommendations(cap, Collections.emptySet());
        }
        try {
            return buildPersonalizedRecommendations(userId, cap);
        } catch (Exception e) {
            logger.error("Personalization failed for user {} — fallback: {}", userId, e.getMessage());
            return getFallbackCatalogRecommendations(cap, Collections.emptySet());
        }
    }

    private List<Product> buildPersonalizedRecommendations(Long userId, int limit) {
        List<WishlistItem> wishlistItems = wishlistItemRepository.findByUserIdWithProduct(userId);
        List<CartItem>     cartItems     = cartItemRepository.findByUserIdWithProduct(userId);
        List<Order>        eligibleOrders = orderRepository.findEligibleOrdersByUserId(
                userId, PageRequest.of(0, 20));

        Map<Long, Integer>   categoryFreq = new HashMap<>();
        Map<String, Integer> colorFreq    = new HashMap<>();
        Set<Long>            cartProductIds       = new HashSet<>();
        Set<Long>            interactedProductIds = new HashSet<>();
        Set<Long>            recentlyPurchasedIds = new HashSet<>();

        LocalDateTime recentCutoff  = LocalDateTime.now().minusDays(recentPurchaseExclusionDays);
        LocalDateTime historyCutoff = LocalDateTime.now().minusDays(orderHistoryDays);

        for (WishlistItem w : wishlistItems) {
            if (w.getProduct() == null) continue;
            Product p = w.getProduct();
            interactedProductIds.add(p.getId());
            if (p.getCategoryId() != null) categoryFreq.merge(p.getCategoryId(), 2, Integer::sum);
            if (p.getColors() != null) p.getColors().forEach(c -> colorFreq.merge(c.trim().toUpperCase(), 2, Integer::sum));
        }
        for (CartItem c : cartItems) {
            if (c.getProduct() == null) continue;
            Product p = c.getProduct();
            cartProductIds.add(p.getId());
            interactedProductIds.add(p.getId());
            if (p.getCategoryId() != null) categoryFreq.merge(p.getCategoryId(), 3, Integer::sum);
            if (p.getColors() != null) p.getColors().forEach(col -> colorFreq.merge(col.trim().toUpperCase(), 3, Integer::sum));
            if (c.getSelectedColor() != null) colorFreq.merge(c.getSelectedColor().trim().toUpperCase(), 2, Integer::sum);
        }
        for (Order order : eligibleOrders) {
            if (order.getOrderItems() == null) continue;
            for (OrderItem oi : order.getOrderItems()) {
                if (oi.getProduct() == null) continue;
                Product p = oi.getProduct();
                interactedProductIds.add(p.getId());
                if (order.getCreatedAt() != null && order.getCreatedAt().isAfter(recentCutoff)) {
                    recentlyPurchasedIds.add(p.getId());
                }
                if (order.getCreatedAt() != null && order.getCreatedAt().isAfter(historyCutoff)) {
                    if (p.getCategoryId() != null) categoryFreq.merge(p.getCategoryId(), 1, Integer::sum);
                    if (p.getColors() != null) p.getColors().forEach(col -> colorFreq.merge(col.trim().toUpperCase(), 1, Integer::sum));
                }
            }
        }

        Set<Long> excludeIds = new HashSet<>();
        excludeIds.addAll(cartProductIds);
        excludeIds.addAll(recentlyPurchasedIds);

        List<Long> topCategoryIds = categoryFreq.entrySet().stream()
                .sorted(Map.Entry.<Long, Integer>comparingByValue().reversed())
                .limit(3).map(Map.Entry::getKey).collect(Collectors.toList());

        Set<String> preferredColors = colorFreq.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .limit(5).map(Map.Entry::getKey).collect(Collectors.toSet());

        // Vector similarity: use most recently wishlisted product as anchor (zero API calls)
        Map<Long, Double> vectorScores = new HashMap<>();
        if (vectorService.isVectorSearchActive() && !interactedProductIds.isEmpty()) {
            Long anchor = wishlistItems.isEmpty()
                    ? (cartItems.isEmpty() ? null : cartItems.get(0).getProduct().getId())
                    : wishlistItems.get(0).getProduct().getId();
            if (anchor != null) vectorScores = vectorService.findSimilarProductsByStoredEmbedding(anchor, 50);
        }

        Set<Long> fullExcludeIds = new HashSet<>(excludeIds);
        fullExcludeIds.addAll(interactedProductIds);
        List<Long> excludeList = fullExcludeIds.isEmpty() ? List.of(-1L) : new ArrayList<>(fullExcludeIds);

        List<Product> candidates = productRepository.findFallbackCandidates(excludeList, PageRequest.of(0, 80));

        final Map<Long, Double> finalVectorScores = vectorScores;
        Map<Long, Double> scores = new HashMap<>();

        for (Product p : candidates) {
            if (!isAvailable(p)) continue;
            double score = 0.0;

            if (!topCategoryIds.isEmpty() && p.getCategoryId() != null) {
                int idx = topCategoryIds.indexOf(p.getCategoryId());
                if (idx == 0)      score += wCategory * 1.0;
                else if (idx == 1) score += wCategory * 0.6;
                else if (idx == 2) score += wCategory * 0.3;
            }

            if (p.getColors() != null && !preferredColors.isEmpty()) {
                long matches = p.getColors().stream().filter(c -> preferredColors.contains(c.trim().toUpperCase())).count();
                if (matches > 0) score += wColor * Math.min(1.0, matches / 2.0);
            }

            Double vecSim = finalVectorScores.get(p.getId());
            if (vecSim != null && vecSim > 0) score += wVector * Math.min(1.0, vecSim);

            if (p.getRating() != null && p.getRating().doubleValue() > 0) {
                double normalizedRating = p.getRating().doubleValue() / 5.0;
                double reviewBoost = (p.getReviews() != null && p.getReviews() > 0)
                        ? Math.min(1.0, Math.log10(p.getReviews() + 1) / 3.0) : 0.0;
                score += wPopularity * (normalizedRating * 0.7 + reviewBoost * 0.3);
            }

            if (Boolean.TRUE.equals(p.isOffer())) score += wOffer;
            scores.put(p.getId(), score);
        }

        if (scores.isEmpty()) return getFallbackCatalogRecommendations(limit, fullExcludeIds);

        Map<Long, Product> productMap = candidates.stream()
                .collect(Collectors.toMap(Product::getId, p -> p, (a, b) -> a));
        List<Product> result = scores.entrySet().stream()
                .sorted(Map.Entry.<Long, Double>comparingByValue().reversed())
                .limit(limit).map(e -> productMap.get(e.getKey())).filter(Objects::nonNull)
                .collect(Collectors.toList());

        logger.info("Personalized for user {}: {} results (cats:{} colors:{} vecs:{})",
                userId, result.size(), topCategoryIds.size(), preferredColors.size(), finalVectorScores.size());
        return result;
    }

    // =========================================================================
    // B. SIMILAR PRODUCTS
    // =========================================================================

    public List<Product> getSimilarProducts(Long productId, int limit) {
        int cap = Math.min(Math.max(limit, 1), 24);
        if (productId == null) return Collections.emptyList();
        try {
            Map<Long, Double> vectorCandidates = vectorService.findSimilarProductsByStoredEmbedding(productId, cap * 3);
            if (!vectorCandidates.isEmpty()) {
                List<Product> candidates = productRepository.findActiveByIds(new ArrayList<>(vectorCandidates.keySet()));
                List<Product> result = candidates.stream()
                        .filter(this::isAvailable)
                        .filter(p -> !p.getId().equals(productId))
                        .sorted((a, b) -> Double.compare(
                                vectorCandidates.getOrDefault(b.getId(), 0.0),
                                vectorCandidates.getOrDefault(a.getId(), 0.0)))
                        .limit(cap)
                        .collect(Collectors.toList());
                result.forEach(p -> p.setSimilarityScore(vectorCandidates.getOrDefault(p.getId(), 0.0)));
                if (!result.isEmpty()) {
                    logger.info("Vector similar products for {}: {} results", productId, result.size());
                    return result;
                }
            }
            return getSimilarProductsFallback(productId, cap);
        } catch (Exception e) {
            logger.error("Similar products failed for {}: {} — fallback", productId, e.getMessage());
            return getSimilarProductsFallback(productId, cap);
        }
    }

    private List<Product> getSimilarProductsFallback(Long productId, int limit) {
        try {
            Optional<Product> src = productRepository.findById(productId);
            if (src.isEmpty() || src.get().getCategoryId() == null) {
                return getFallbackCatalogRecommendations(limit, Set.of(productId));
            }
            List<Product> sameCat = productRepository.findByCategoryIdExcluding(
                    src.get().getCategoryId(), productId, PageRequest.of(0, limit * 2));
            return sameCat.stream().filter(this::isAvailable).limit(limit).collect(Collectors.toList());
        } catch (Exception e) {
            logger.error("Fallback similar products also failed for {}: {}", productId, e.getMessage());
            return Collections.emptyList();
        }
    }

    // =========================================================================
    // C. CART RECOMMENDATIONS
    // =========================================================================

    public List<Product> getCartRecommendations(Long userId, List<Long> guestCartProductIds, int limit) {
        int cap = Math.min(Math.max(limit, 1), 24);
        Set<Long>    cartProductIds    = new HashSet<>();
        Set<String>  cartCategorySlugs = new HashSet<>();

        try {
            if (userId != null) {
                // Authenticated: always load from DB — never trust client IDs
                for (CartItem item : cartItemRepository.findByUserIdWithProduct(userId)) {
                    if (item.getProduct() != null) {
                        cartProductIds.add(item.getProduct().getId());
                        String slug = item.getProduct().getCategorySlug();
                        if (slug != null) cartCategorySlugs.add(slug.toLowerCase());
                    }
                }
            } else if (guestCartProductIds != null && !guestCartProductIds.isEmpty()) {
                List<Long> sanitized = guestCartProductIds.stream()
                        .filter(Objects::nonNull).filter(id -> id > 0).distinct()
                        .limit(maxGuestCartIds).collect(Collectors.toList());
                if (!sanitized.isEmpty()) {
                    for (Product p : productRepository.findActiveByIds(sanitized)) {
                        cartProductIds.add(p.getId());
                        String slug = p.getCategorySlug();
                        if (slug != null) cartCategorySlugs.add(slug.toLowerCase());
                    }
                }
            }

            if (cartProductIds.isEmpty()) return getFallbackCatalogRecommendations(cap, Collections.emptySet());

            Set<String> complementarySlugs = new HashSet<>();
            for (String slug : cartCategorySlugs) {
                complementarySlugs.addAll(COMPLEMENTARY_CATEGORIES.getOrDefault(slug, Collections.emptyList()));
            }
            complementarySlugs.removeAll(cartCategorySlugs);

            List<Product> candidates;
            if (!complementarySlugs.isEmpty()) {
                candidates = new ArrayList<>();
                for (String slug : complementarySlugs) {
                    candidates.addAll(productRepository.findByCategorySlugAndActiveTrue(slug));
                }
            } else {
                candidates = productRepository.findFallbackCandidates(new ArrayList<>(cartProductIds), PageRequest.of(0, cap * 3));
            }

            List<Product> result = candidates.stream()
                    .filter(p -> !cartProductIds.contains(p.getId()))
                    .filter(this::isAvailable).distinct()
                    .sorted(Comparator.comparingDouble(
                            (Product p) -> p.getRating() != null ? p.getRating().doubleValue() : 0.0).reversed())
                    .limit(cap).collect(Collectors.toList());

            logger.info("Cart recs for user {}: {} results (cats:{} complements:{})",
                    userId, result.size(), cartCategorySlugs, complementarySlugs);
            return result;
        } catch (Exception e) {
            logger.error("Cart recs failed for user {}: {} — fallback", userId, e.getMessage());
            return getFallbackCatalogRecommendations(cap, cartProductIds);
        }
    }

    // =========================================================================
    // Shared Utilities
    // =========================================================================

    /** Top-rated catalog fallback (for guests and empty-history users) */
    public List<Product> getFallbackCatalogRecommendations(int limit, Set<Long> excludeIds) {
        List<Long> excludeList = (excludeIds == null || excludeIds.isEmpty())
                ? List.of(-1L) : new ArrayList<>(excludeIds);
        return productRepository.findFallbackCandidates(excludeList, PageRequest.of(0, limit))
                .stream().filter(this::isAvailable).limit(limit).collect(Collectors.toList());
    }

    /**
     * Dual-inventory availability check.
     * LEGACY: inStock=true AND stockQuantity>0
     * VARIANT: at least one active variant with stockQuantity>0
     */
    public boolean isAvailable(Product p) {
        if (p == null) return false;
        if (Boolean.FALSE.equals(p.isActive())) return false;
        if (p.isVariantTracking()) {
            if (p.getVariants() == null || p.getVariants().isEmpty()) return false;
            return p.getVariants().stream()
                    .anyMatch(v -> v.isActive() && v.getStockQuantity() != null && v.getStockQuantity() > 0);
        }
        return p.isInStock() && p.getStockQuantity() != null && p.getStockQuantity() > 0;
    }

    // Accessors for testing
    public int getWeightCategory()              { return wCategory; }
    public int getWeightColor()                 { return wColor; }
    public int getWeightVector()                { return wVector; }
    public int getWeightPopularity()            { return wPopularity; }
    public int getWeightOffer()                 { return wOffer; }
    public int getOrderHistoryDays()            { return orderHistoryDays; }
    public int getRecentPurchaseExclusionDays() { return recentPurchaseExclusionDays; }
}
