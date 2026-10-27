package com.kuberfashion.backend.repository;

import com.kuberfashion.backend.entity.ProductVariant;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ProductVariantRepository extends JpaRepository<ProductVariant, Long> {

    List<ProductVariant> findByProductId(Long productId);

    List<ProductVariant> findByProductIdAndActiveTrue(Long productId);

    Optional<ProductVariant> findByProductIdAndSizeAndColor(Long productId, String size, String color);

    /**
     * Acquires a pessimistic write lock (SELECT ... FOR UPDATE) on the variant row.
     * Prevents race conditions and overselling when concurrent transactions purchase the same variant.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT pv FROM ProductVariant pv WHERE pv.id = :id")
    Optional<ProductVariant> findByIdWithPessimisticLock(@Param("id") Long id);

    /**
     * Acquires a pessimistic write lock on the variant identified by product, size, and color.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT pv FROM ProductVariant pv WHERE pv.product.id = :productId AND UPPER(TRIM(pv.size)) = UPPER(TRIM(:size)) AND UPPER(TRIM(pv.color)) = UPPER(TRIM(:color))")
    Optional<ProductVariant> findByProductIdAndSizeAndColorWithPessimisticLock(
            @Param("productId") Long productId,
            @Param("size") String size,
            @Param("color") String color);

    long countByProductId(Long productId);

    long countByProductIdAndActiveTrue(Long productId);

    boolean existsByProductIdAndSizeAndColor(Long productId, String size, String color);
}
