package com.kuberfashion.backend.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.kuberfashion.backend.exception.InsufficientStockException;
import jakarta.persistence.*;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;

/**
 * Represents an individual sellable variant of a product, identified by its
 * unique combination of product, size, and color.
 *
 * Each variant tracks its own discrete stock quantity independently of other variants
 * and independently of the legacy aggregate product stock.
 */
@Entity
@Table(name = "product_variants", uniqueConstraints = {
        @UniqueConstraint(name = "uq_product_variants_prod_size_color", columnNames = { "product_id", "size", "color" })
})
@org.hibernate.annotations.Check(constraints = "stock_quantity >= 0")
public class ProductVariant {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotNull(message = "Product is required")
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "product_id", nullable = false)
    @JsonIgnore
    private Product product;

    @NotBlank(message = "Variant size is required")
    @Size(max = 50, message = "Variant size must not exceed 50 characters")
    @Column(nullable = false, length = 50)
    private String size;

    @NotBlank(message = "Variant color is required")
    @Size(max = 50, message = "Variant color must not exceed 50 characters")
    @Column(nullable = false, length = 50)
    private String color;

    @Size(max = 100, message = "SKU must not exceed 100 characters")
    @Column(length = 100)
    private String sku;

    @NotNull(message = "Stock quantity is required")
    @Min(value = 0, message = "Stock quantity must be non-negative")
    @Column(name = "stock_quantity", nullable = false)
    private Integer stockQuantity = 0;

    @Column(name = "is_active", nullable = false)
    private boolean active = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    public ProductVariant() {
    }

    public ProductVariant(Product product, String size, String color, Integer stockQuantity, String sku) {
        this(product, size, color, stockQuantity, sku, true);
    }

    public ProductVariant(Product product, String size, String color, Integer stockQuantity, String sku, boolean active) {
        this.product = product;
        this.size = normalizeSize(size);
        this.color = normalizeColor(color);
        this.stockQuantity = stockQuantity != null ? stockQuantity : 0;
        this.sku = sku;
        this.active = active;
    }

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
        this.size = normalizeSize(this.size);
        this.color = normalizeColor(this.color);
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
        this.size = normalizeSize(this.size);
        this.color = normalizeColor(this.color);
    }

    // ── INVENTORY STATE MUTATIONS ─────────────────────────────────────────────

    /**
     * Atomically deducts inventory within an active transaction.
     * Throws {@link InsufficientStockException} if requested quantity exceeds available stock.
     */
    public void deductStock(int quantity) {
        if (quantity <= 0) {
            throw new IllegalArgumentException("Deduct quantity must be greater than zero");
        }
        if (this.stockQuantity < quantity) {
            throw new InsufficientStockException(String.format(
                    "Insufficient stock for variant size '%s' color '%s' of product '%s'. Available: %d, requested: %d",
                    this.size, this.color,
                    this.product != null ? this.product.getName() : "Product ID " + (this.product != null ? this.product.getId() : "null"),
                    this.stockQuantity, quantity));
        }
        this.stockQuantity -= quantity;
    }

    /**
     * Restores inventory upon cancellation or payment failure.
     */
    public void restoreStock(int quantity) {
        if (quantity <= 0) {
            throw new IllegalArgumentException("Restore quantity must be greater than zero");
        }
        this.stockQuantity += quantity;
    }

    // ── NORMALIZATION UTILITIES ───────────────────────────────────────────────

    public static String normalizeSize(String size) {
        return size == null ? "" : size.trim().toUpperCase();
    }

    public static String normalizeColor(String color) {
        return color == null ? "" : color.trim().toUpperCase();
    }

    // ── GETTERS AND SETTERS ───────────────────────────────────────────────────

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Product getProduct() {
        return product;
    }

    public void setProduct(Product product) {
        this.product = product;
    }

    public String getSize() {
        return size;
    }

    public void setSize(String size) {
        this.size = normalizeSize(size);
    }

    public String getColor() {
        return color;
    }

    public void setColor(String color) {
        this.color = normalizeColor(color);
    }

    public String getSku() {
        return sku;
    }

    public void setSku(String sku) {
        this.sku = sku;
    }

    public Integer getStockQuantity() {
        return stockQuantity;
    }

    public void setStockQuantity(Integer stockQuantity) {
        this.stockQuantity = stockQuantity != null ? stockQuantity : 0;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
