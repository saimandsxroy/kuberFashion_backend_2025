package com.kuberfashion.backend.repository;

import com.kuberfashion.backend.entity.Product;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

@Repository
public interface ProductRepository extends JpaRepository<Product, Long>, JpaSpecificationExecutor<Product> {

       /**
        * Acquire a pessimistic write lock (SELECT ... FOR UPDATE) on the product row.
        * Prevents concurrent checkouts from overselling inventory.
        */
       @Lock(LockModeType.PESSIMISTIC_WRITE)
       @Query("SELECT p FROM Product p WHERE p.id = :id")
       Optional<Product> findByIdWithPessimisticLock(@Param("id") Long id);

       /**
        * Query the inventory tracking mode as a scalar enum without loading the Product
        * entity into the Hibernate first-level cache.
        */
       @Query("SELECT p.inventoryTrackingMode FROM Product p WHERE p.id = :id")
       Optional<Product.InventoryTrackingMode> findInventoryTrackingModeById(@Param("id") Long id);

       // Fetch join to avoid lazy loading issues
       @Query("SELECT DISTINCT p FROM Product p " +
                     "LEFT JOIN FETCH p.category " +
                     "WHERE p.slug = :slug")
       Optional<Product> findBySlug(@Param("slug") String slug);

       // Fetch join for collections to prevent LazyInitializationException
       @Query("SELECT DISTINCT p FROM Product p " +
                     "LEFT JOIN FETCH p.category " +
                     "WHERE p.active = true")
       List<Product> findByActiveTrue();

       @Query("SELECT DISTINCT p FROM Product p " +
                     "LEFT JOIN FETCH p.category " +
                     "WHERE p.featured = true AND p.active = true")
       List<Product> findByFeaturedTrueAndActiveTrue();

       @Query("SELECT DISTINCT p FROM Product p " +
                     "LEFT JOIN FETCH p.category " +
                     "WHERE p.isOffer = true AND p.active = true")
       List<Product> findByIsOfferTrueAndActiveTrue();

       @Query("SELECT DISTINCT p FROM Product p " +
                     "LEFT JOIN FETCH p.category c " +
                     "WHERE c.slug = :categorySlug AND p.active = true")
       List<Product> findByCategorySlugAndActiveTrue(@Param("categorySlug") String categorySlug);

       @Query("SELECT DISTINCT p FROM Product p " +
                     "LEFT JOIN FETCH p.category c " +
                     "WHERE c.slug = :categorySlug AND p.active = true")
       Page<Product> findByCategorySlugAndActiveTrue(@Param("categorySlug") String categorySlug, Pageable pageable);

       @Query("SELECT DISTINCT p FROM Product p " +
                     "LEFT JOIN FETCH p.category " +
                     "WHERE p.active = true AND p.inStock = true")
       List<Product> findAvailableProducts();

       @Query("SELECT DISTINCT p FROM Product p " +
                     "LEFT JOIN FETCH p.category " +
                     "WHERE p.active = true AND " +
                     "(LOWER(p.name) LIKE LOWER(CONCAT('%', :keyword, '%')) OR " +
                     "LOWER(p.description) LIKE LOWER(CONCAT('%', :keyword, '%')))")
       Page<Product> searchProducts(@Param("keyword") String keyword, Pageable pageable);

       @Query("SELECT DISTINCT p FROM Product p " +
                     "LEFT JOIN FETCH p.category c " +
                     "WHERE p.active = true AND c.slug = :categorySlug AND " +
                     "p.price BETWEEN :minPrice AND :maxPrice")
       Page<Product> findByCategoryAndPriceRange(@Param("categorySlug") String categorySlug,
                     @Param("minPrice") BigDecimal minPrice,
                     @Param("maxPrice") BigDecimal maxPrice,
                     Pageable pageable);

       @Query("SELECT DISTINCT p FROM Product p " +
                     "LEFT JOIN FETCH p.category " +
                     "WHERE p.active = true AND " +
                     "p.price BETWEEN :minPrice AND :maxPrice")
       Page<Product> findByPriceRange(@Param("minPrice") BigDecimal minPrice,
                     @Param("maxPrice") BigDecimal maxPrice,
                     Pageable pageable);

       @Query("SELECT DISTINCT p FROM Product p " +
                     "LEFT JOIN FETCH p.category " +
                     "WHERE p.active = true ORDER BY p.rating DESC")
       List<Product> findTopRatedProducts(Pageable pageable);

       @Query("SELECT DISTINCT p FROM Product p " +
                     "LEFT JOIN FETCH p.category " +
                     "WHERE p.active = true ORDER BY p.createdAt DESC")
       List<Product> findNewestProducts(Pageable pageable);

       @Query("SELECT COUNT(p) FROM Product p WHERE p.active = true")
       long countActiveProducts();

       @Query("SELECT COUNT(p) FROM Product p WHERE p.active = true AND p.inStock = true")
       long countAvailableProducts();

       boolean existsBySlug(String slug);

       @Query("SELECT (COUNT(p) > 0) FROM Product p WHERE p.id IN :productIds AND LOWER(p.category.slug) = LOWER(:categorySlug)")
       boolean existsByProductIdsAndCategorySlug(@Param("productIds") List<Long> productIds, @Param("categorySlug") String categorySlug);

       /**
        * Batch-load active products by their IDs, used by RecommendationService to hydrate candidates.
        */
       @Query("SELECT DISTINCT p FROM Product p LEFT JOIN FETCH p.category WHERE p.id IN :ids AND p.active = true")
       List<Product> findActiveByIds(@Param("ids") List<Long> ids);

       /**
        * Fallback catalog candidates: top-rated active products excluding a given ID set.
        * Used when no personalized signals exist (guests, empty history).
        */
       @Query("SELECT DISTINCT p FROM Product p LEFT JOIN FETCH p.category " +
              "WHERE p.active = true " +
              "AND (p.id NOT IN :excludeIds) " +
              "ORDER BY p.rating DESC, p.reviews DESC")
       List<Product> findFallbackCandidates(@Param("excludeIds") List<Long> excludeIds, Pageable pageable);

       /**
        * Relational fallback for similar products when no embedding is available:
        * products in the same category sorted by rating, excluding a given product.
        */
       @Query("SELECT DISTINCT p FROM Product p LEFT JOIN FETCH p.category c " +
              "WHERE p.active = true AND c.id = :categoryId AND p.id != :excludeId " +
              "ORDER BY p.rating DESC, p.reviews DESC")
       List<Product> findByCategoryIdExcluding(@Param("categoryId") Long categoryId,
                     @Param("excludeId") Long excludeId, Pageable pageable);
}
