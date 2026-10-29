package com.kuberfashion.backend.repository;

import com.kuberfashion.backend.entity.CartItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface CartItemRepository extends JpaRepository<CartItem, Long> {
    
    List<CartItem> findByUserIdOrderByCreatedAtDesc(Long userId);

    @Query("SELECT c FROM CartItem c JOIN FETCH c.product WHERE c.user.id = :userId ORDER BY c.createdAt DESC")
    List<CartItem> findByUserIdWithProduct(@Param("userId") Long userId);
    
    Optional<CartItem> findByIdAndUserId(Long id, Long userId);
    
    @Query("SELECT c FROM CartItem c WHERE c.user.id = :userId AND c.product.id = :productId AND " +
           "(:size IS NULL OR c.selectedSize = :size) AND (:color IS NULL OR c.selectedColor = :color)")
    Optional<CartItem> findByUserIdAndProductIdAndSizeAndColor(
            @Param("userId") Long userId, 
            @Param("productId") Long productId, 
            @Param("size") String size, 
            @Param("color") String color);
    
    long countByUserId(Long userId);
    
    @Modifying
    @Query("DELETE FROM CartItem c WHERE c.user.id = :userId")
    void deleteByUserId(@Param("userId") Long userId);
    
    @Query("SELECT SUM(c.quantity) FROM CartItem c WHERE c.user.id = :userId")
    Long getTotalQuantityByUserId(@Param("userId") Long userId);
}
