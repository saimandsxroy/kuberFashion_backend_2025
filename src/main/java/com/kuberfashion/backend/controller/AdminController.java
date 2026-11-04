package com.kuberfashion.backend.controller;

import com.kuberfashion.backend.dto.ApiResponse;
import com.kuberfashion.backend.dto.UserResponseDto;
import com.kuberfashion.backend.dto.PagedResponse;
import com.kuberfashion.backend.entity.Order;
import com.kuberfashion.backend.entity.User;
import com.kuberfashion.backend.repository.OrderRepository;
import com.kuberfashion.backend.repository.UserRepository;
import com.kuberfashion.backend.service.UserService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/admin")
public class AdminController {

    @Autowired
    private UserService userService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private com.kuberfashion.backend.repository.ProductRepository productRepository;

    // Dashboard Stats
    @GetMapping("/dashboard")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<DashboardStats>> getDashboardStats() {
        DashboardStats stats = new DashboardStats();

        // Total Revenue (sum of completed orders)
        List<Order> completedOrders = orderRepository.findByStatus(Order.OrderStatus.DELIVERED);
        BigDecimal totalRevenue = completedOrders.stream()
                .map(Order::getTotalAmount)
                .filter(java.util.Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        stats.totalRevenue = totalRevenue;

        // Active Users (enabled users)
        long activeUsers = userRepository.countActiveUsers();
        stats.totalUsers = activeUsers;

        // Total Orders
        long totalOrders = orderRepository.count();
        stats.totalOrders = totalOrders;

        // Total Products
        long totalProducts = productRepository.count();
        stats.totalProducts = totalProducts;

        // Pending Orders
        long pendingOrders = orderRepository.countByStatus(Order.OrderStatus.PENDING);
        stats.pendingOrders = pendingOrders;

        // Recent Transactions (latest 5 orders)
        Pageable top5 = PageRequest.of(0, 5, org.springframework.data.domain.Sort.by("createdAt").descending());
        List<Order> recentOrders = orderRepository.findAll(top5).getContent();
        stats.recentTransactions = recentOrders.stream().map(o -> {
            RecentTransaction tx = new RecentTransaction();
            tx.orderId = o.getId();
            tx.orderNumber = o.getOrderNumber();
            tx.amount = o.getTotalAmount();
            tx.status = o.getStatus() != null ? o.getStatus().name() : "PENDING";
            tx.userEmail = o.getUser() != null ? o.getUser().getEmail() : "N/A";
            tx.userName = o.getUser() != null ? o.getUser().getFullName() : "N/A";
            tx.createdAt = o.getCreatedAt();
            return tx;
        }).collect(Collectors.toList());

        return ResponseEntity.ok(ApiResponse.success("Dashboard stats fetched", stats));
    }

    // Dashboard Stats DTO
    public static class DashboardStats {
        public BigDecimal totalRevenue = BigDecimal.ZERO;
        public long totalUsers = 0;
        public long totalOrders = 0;
        public long totalProducts = 0;
        public long pendingOrders = 0;
        public List<RecentTransaction> recentTransactions = new java.util.ArrayList<>();
    }

    public static class RecentTransaction {
        public Long orderId;
        public String orderNumber;
        public BigDecimal amount;
        public String status;
        public String userEmail;
        public String userName;
        public LocalDateTime createdAt;
    }

    // Users
    @GetMapping("/users")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<PagedResponse<UserResponseDto>>> listUsers(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String role,
            @RequestParam(required = false) Boolean enabled,
            @RequestParam(required = false) String q) {
        Pageable pageable = PageRequest.of(page, size);
        User.Role roleEnum = null;
        if (role != null && !role.isBlank()) {
            roleEnum = User.Role.valueOf(role);
        }
        Page<User> p = userRepository.findAllFiltered(roleEnum, enabled, (q == null || q.isBlank()) ? null : q,
                pageable);
        List<UserResponseDto> dtos = p.getContent().stream().map(UserResponseDto::new).collect(Collectors.toList());
        PagedResponse<UserResponseDto> out = new PagedResponse<>(dtos, p.getNumber(), p.getSize(), p.getTotalElements(),
                p.getTotalPages());
        return ResponseEntity.ok(ApiResponse.success("Users fetched", out));
    }

    @GetMapping("/users/stats")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<UserStats>> getUserStats() {
        UserStats stats = new UserStats();
        stats.totalUsers = userRepository.count();
        stats.activeUsers = userRepository.countActiveUsers();
        stats.adminCount = userRepository.countByRole(User.Role.ADMIN);
        return ResponseEntity.ok(ApiResponse.success("User stats fetched", stats));
    }

    public static class UserStats {
        public long totalUsers;
        public long activeUsers;
        public long adminCount;
    }

    @PutMapping("/users/{id}/status")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<String>> updateUserStatus(@PathVariable Long id,
            @RequestBody Map<String, Boolean> body) {
        boolean enabled = body.getOrDefault("enabled", true);
        userService.updateUserStatus(id, enabled);
        return ResponseEntity.ok(ApiResponse.success(enabled ? "User enabled" : "User disabled"));
    }

    @PutMapping("/users/{id}/role")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<String>> updateUserRole(@PathVariable Long id,
            @RequestBody Map<String, String> body) {
        String role = body.get("role");
        userService.updateUserRole(id, User.Role.valueOf(role));
        return ResponseEntity.ok(ApiResponse.success("Role updated"));
    }

    @PostMapping("/users")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<UserResponseDto>> createUser(@RequestBody CreateUserRequest request) {
        // Check if user already exists
        if (userRepository.existsByEmail(request.email)) {
            return ResponseEntity.badRequest().body(ApiResponse.error("User already exists with this email"));
        }
        if (request.phone != null && !request.phone.isBlank() && userRepository.existsByPhone(request.phone)) {
            return ResponseEntity.badRequest().body(ApiResponse.error("User already exists with this phone"));
        }

        // Create new user
        User user = new User();
        user.setFirstName(request.firstName);
        user.setLastName(request.lastName);
        user.setEmail(request.email);
        user.setPhone(request.phone);
        user.setPassword(
                new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder().encode(request.password));
        user.setRole(request.role != null ? User.Role.valueOf(request.role) : User.Role.USER);
        user.setEnabled(true);

        User savedUser = userRepository.save(user);
        return ResponseEntity.ok(ApiResponse.success("User created successfully", new UserResponseDto(savedUser)));
    }

    @DeleteMapping("/users/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<String>> deleteUser(@PathVariable Long id) {
        if (!userRepository.existsById(id)) {
            return ResponseEntity.badRequest().body(ApiResponse.error("User not found"));
        }
        userService.deleteUser(id);
        return ResponseEntity.ok(ApiResponse.success("User deleted successfully"));
    }

    @PutMapping("/users/{id}/reset-password")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<String>> resetUserPassword(@PathVariable Long id,
            @RequestBody ResetPasswordRequest request) {
        if (!userRepository.existsById(id)) {
            return ResponseEntity.badRequest().body(ApiResponse.error("User not found"));
        }
        if (request.newPassword == null || request.newPassword.length() < 6) {
            return ResponseEntity.badRequest().body(ApiResponse.error("Password must be at least 6 characters"));
        }
        if (!request.newPassword.equals(request.confirmPassword)) {
            return ResponseEntity.badRequest().body(ApiResponse.error("Passwords do not match"));
        }

        User user = userRepository.findById(id).orElseThrow();
        user.setPassword(
                new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder().encode(request.newPassword));
        userRepository.save(user);

        return ResponseEntity.ok(ApiResponse.success("Password reset successfully"));
    }

    // DTO for Reset Password
    public static class ResetPasswordRequest {
        public String newPassword;
        public String confirmPassword;
    }

    // DTO for Create User
    public static class CreateUserRequest {
        public String firstName;
        public String lastName;
        public String email;
        public String phone;
        public String password;
        public String role;
    }

    // Orders listing with simple optional filters
    @GetMapping("/orders")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<PagedResponse<OrderAdminResponseDto>>> listOrders(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String email) {
        Pageable pageable = PageRequest.of(page, size);
        Order.OrderStatus st = null;
        if (status != null && !status.isBlank()) {
            st = Order.OrderStatus.valueOf(status);
        }
        Page<Order> p = orderRepository.findAllByFilters(st, (email == null || email.isBlank()) ? null : email,
                pageable);
        List<OrderAdminResponseDto> dtos = p.getContent().stream().map(OrderAdminResponseDto::from)
                .collect(Collectors.toList());
        PagedResponse<OrderAdminResponseDto> out = new PagedResponse<>(dtos, p.getNumber(), p.getSize(),
                p.getTotalElements(), p.getTotalPages());
        return ResponseEntity.ok(ApiResponse.success("Orders fetched", out));
    }

    @PutMapping("/orders/{id}/status")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<String>> updateOrderStatus(
            @PathVariable Long id,
            @RequestBody Map<String, String> body) {
        String status = body.get("status");
        Order order = orderRepository.findById(id).orElseThrow();
        order.setStatus(Order.OrderStatus.valueOf(status));
        order.setUpdatedAt(LocalDateTime.now());
        orderRepository.save(order);
        return ResponseEntity.ok(ApiResponse.success("Order status updated"));
    }

    // DTO for admin order list
    public static class OrderAdminResponseDto {
        public Long id;
        public String orderNumber;
        public String status;
        public BigDecimal totalAmount;
        public LocalDateTime createdAt;
        public String userEmail;

        public static OrderAdminResponseDto from(Order o) {
            OrderAdminResponseDto dto = new OrderAdminResponseDto();
            dto.id = o.getId();
            dto.orderNumber = o.getOrderNumber();
            dto.status = o.getStatus() != null ? o.getStatus().name() : null;
            dto.totalAmount = o.getTotalAmount();
            dto.createdAt = o.getCreatedAt();
            dto.userEmail = o.getUser() != null ? o.getUser().getEmail() : null;
            return dto;
        }
    }

    // Update user coupons (ADD or DEDUCT)
    @PutMapping("/users/{id}/coupons")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<UserResponseDto>> updateUserCoupons(
            @PathVariable Long id,
            @RequestBody UpdateCouponsRequest request) {
        User user = userRepository.findById(id)
                .orElse(null);
        if (user == null) {
            return ResponseEntity.badRequest().body(ApiResponse.error("User not found"));
        }

        int currentCoupons = user.getKuberCoupons();
        int amount = (request.amount != null) ? request.amount : 0;

        if ("ADD".equalsIgnoreCase(request.action)) {
            user.setKuberCoupons(currentCoupons + amount);
        } else if ("DEDUCT".equalsIgnoreCase(request.action)) {
            int newBalance = currentCoupons - amount;
            if (newBalance < 0) {
                return ResponseEntity.badRequest().body(ApiResponse.error("Cannot deduct more than current balance"));
            }
            user.setKuberCoupons(newBalance);
        } else {
            return ResponseEntity.badRequest().body(ApiResponse.error("Invalid action. Use 'ADD' or 'DEDUCT'"));
        }

        userRepository.save(user);
        return ResponseEntity.ok(ApiResponse.success("Coupons updated successfully", new UserResponseDto(user)));
    }

    // DTO for Update Coupons
    public static class UpdateCouponsRequest {
        public String action; // "ADD" or "DEDUCT"
        public Integer amount;
        public String reason; // Optional, for audit purposes
    }
}
