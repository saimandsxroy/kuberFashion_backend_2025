package com.kuberfashion.backend.repository;

import com.kuberfashion.backend.entity.Order;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface OrderRepository extends JpaRepository<Order, Long> {

       Optional<Order> findByOrderNumber(String orderNumber);

       @Query("SELECT DISTINCT o FROM Order o LEFT JOIN FETCH o.orderItems oi LEFT JOIN FETCH oi.product LEFT JOIN FETCH oi.variant WHERE o.idempotencyKey = :idempotencyKey AND o.user.id = :userId")
       Optional<Order> findByIdempotencyKeyAndUserId(@Param("idempotencyKey") String idempotencyKey, @Param("userId") Long userId);

       @Query("SELECT DISTINCT o FROM Order o LEFT JOIN FETCH o.orderItems oi LEFT JOIN FETCH oi.product LEFT JOIN FETCH oi.variant WHERE o.id = :orderId")
       Optional<Order> findByIdWithOrderItemsAndProduct(@Param("orderId") Long orderId);

       @Query("SELECT o FROM Order o WHERE o.user.id = :userId ORDER BY o.createdAt DESC")
       List<Order> findByUserIdOrderByCreatedAtDesc(@Param("userId") Long userId);

       @Query("SELECT o FROM Order o WHERE o.user.id = :userId ORDER BY o.createdAt DESC")
       Page<Order> findByUserIdOrderByCreatedAtDesc(@Param("userId") Long userId, Pageable pageable);

       @Query("SELECT o FROM Order o WHERE o.id = :orderId AND o.user.id = :userId")
       Optional<Order> findByIdAndUserId(@Param("orderId") Long orderId, @Param("userId") Long userId);

       @Lock(LockModeType.PESSIMISTIC_WRITE)
       @Query("SELECT o FROM Order o WHERE o.id = :orderId AND o.user.id = :userId")
       Optional<Order> findByIdAndUserIdWithPessimisticLock(@Param("orderId") Long orderId, @Param("userId") Long userId);

       Page<Order> findAllByOrderByCreatedAtDesc(Pageable pageable);

       List<Order> findByStatusOrderByCreatedAtDesc(Order.OrderStatus status);

       @Query("SELECT o FROM Order o WHERE o.user.id = :userId AND o.status = :status ORDER BY o.createdAt DESC")
       List<Order> findByUserIdAndStatus(@Param("userId") Long userId, @Param("status") Order.OrderStatus status);

       @Query("SELECT o FROM Order o WHERE o.status = :status ORDER BY o.createdAt DESC")
       List<Order> findByStatus(@Param("status") Order.OrderStatus status);

       @Query("SELECT DISTINCT o FROM Order o LEFT JOIN FETCH o.orderItems oi LEFT JOIN FETCH oi.product LEFT JOIN FETCH oi.variant WHERE o.paymentMethod = :paymentMethod AND o.status = :status AND o.paymentStatus = :paymentStatus AND o.createdAt < :cutoff ORDER BY o.createdAt ASC")
       List<Order> findPendingOrdersForReconciliation(
               @Param("paymentMethod") Order.PaymentMethod paymentMethod,
               @Param("status") Order.OrderStatus status,
               @Param("paymentStatus") Order.PaymentStatus paymentStatus,
               @Param("cutoff") LocalDateTime cutoff);

       @Query("SELECT SUM(o.totalAmount) FROM Order o WHERE o.paymentStatus = 'PAID'")
       BigDecimal getTotalRevenue();

       @Query("SELECT SUM(o.totalAmount) FROM Order o WHERE o.paymentStatus = 'PAID' AND " +
                     "o.createdAt BETWEEN :startDate AND :endDate")
       BigDecimal getRevenueByDateRange(@Param("startDate") LocalDateTime startDate,
                     @Param("endDate") LocalDateTime endDate);

       @Query("SELECT COUNT(o) FROM Order o WHERE o.createdAt BETWEEN :startDate AND :endDate")
       long countOrdersByDateRange(@Param("startDate") LocalDateTime startDate,
                     @Param("endDate") LocalDateTime endDate);

       @Query("SELECT COUNT(o) FROM Order o WHERE o.status = :status")
       long countByStatus(@Param("status") Order.OrderStatus status);

       @Query("SELECT o FROM Order o LEFT JOIN FETCH o.user " +
                     "WHERE (:status IS NULL OR o.status = :status) " +
                     "AND (:email IS NULL OR :email = '' OR LOWER(o.user.email) LIKE LOWER(CONCAT('%', :email, '%'))) "
                     +
                     "ORDER BY o.createdAt DESC")
       Page<Order> findAllByFilters(@Param("status") Order.OrderStatus status,
                     @Param("email") String email,
                     Pageable pageable);
}
