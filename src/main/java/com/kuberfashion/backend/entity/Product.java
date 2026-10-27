package com.kuberfashion.backend.entity;

import jakarta.persistence.*;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import com.fasterxml.jackson.annotation.JsonIgnore;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

@Entity
@Table(name = "products")
public class Product {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotBlank(message = "Product name is required")
    @Size(max = 200, message = "Product name must not exceed 200 characters")
    @Column(nullable = false, length = 200)
    private String name;

    @NotBlank(message = "Product slug is required")
    @Size(max = 200, message = "Product slug must not exceed 200 characters")
    @Column(unique = true, nullable = false, length = 200)
    private String slug;

    @NotNull(message = "Price is required")
    @DecimalMin(value = "0.0", inclusive = false, message = "Price must be greater than 0")
    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal price;

    @DecimalMin(value = "0.0", inclusive = false, message = "Original price must be greater than 0")
    @Column(name = "original_price", precision = 10, scale = 2)
    private BigDecimal originalPrice;

    @Column(nullable = false)
    private Integer discount = 0;

    @NotNull(message = "Category is required")
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "category_id", nullable = false)
    private Category category;

    @Column(length = 500)
    private String image;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "product_images", joinColumns = @JoinColumn(name = "product_id"))
    @Column(name = "image_url", length = 500)
    private List<String> images;

    @Column(nullable = false, precision = 2, scale = 1)
    private BigDecimal rating = BigDecimal.ZERO;

    @Column(nullable = false)
    private Integer reviews = 0;

    @Size(max = 1000, message = "Description must not exceed 1000 characters")
    @Column(length = 1000)
    private String description;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "product_sizes", joinColumns = @JoinColumn(name = "product_id"))
    @Column(name = "size", length = 10)
    private List<String> sizes;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "product_colors", joinColumns = @JoinColumn(name = "product_id"))
    @Column(name = "color_name", length = 50)
    private List<String> colors;

    @Column(name = "in_stock", nullable = false)
    private boolean inStock = true;

    @Column(nullable = false)
    private boolean featured = false;

    @Column(name = "stock_quantity", nullable = false)
    private Integer stockQuantity = 0;

    public enum InventoryTrackingMode {
        LEGACY,
        VARIANT
    }

    @Enumerated(EnumType.STRING)
    @Column(name = "inventory_tracking_mode", nullable = false)
    private InventoryTrackingMode inventoryTrackingMode = InventoryTrackingMode.LEGACY;

    @OneToMany(mappedBy = "product", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @JsonIgnore
    private List<ProductVariant> variants = new java.util.ArrayList<>();

    @Column(name = "is_active", nullable = true, columnDefinition = "boolean default true")
    private Boolean active = true;

    @Column(name = "is_offer", nullable = true, columnDefinition = "boolean default false")
    private Boolean isOffer = false;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @OneToMany(mappedBy = "product", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    @JsonIgnore
    private Set<OrderItem> orderItems;

    @OneToMany(mappedBy = "product", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    @JsonIgnore
    private Set<WishlistItem> wishlistItems;

    // Constructors
    public Product() {
    }

    public Product(String name, String slug, BigDecimal price, Category category, String description) {
        this.name = name;
        this.slug = slug;
        this.price = price;
        this.category = category;
        this.description = description;
    }

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    // Getters and Setters
    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getSlug() {
        return slug;
    }

    public void setSlug(String slug) {
        this.slug = slug;
    }

    public BigDecimal getPrice() {
        return price;
    }

    public void setPrice(BigDecimal price) {
        this.price = price;
    }

    public BigDecimal getOriginalPrice() {
        return originalPrice;
    }

    public void setOriginalPrice(BigDecimal originalPrice) {
        this.originalPrice = originalPrice;
    }

    public Integer getDiscount() {
        return discount;
    }

    public void setDiscount(Integer discount) {
        this.discount = discount;
    }

    public Category getCategory() {
        return category;
    }

    public void setCategory(Category category) {
        this.category = category;
    }

    public String getImage() {
        return image;
    }

    public void setImage(String image) {
        this.image = image;
    }

    public List<String> getImages() {
        return images;
    }

    public void setImages(List<String> images) {
        this.images = images;
    }

    public BigDecimal getRating() {
        return rating;
    }

    public void setRating(BigDecimal rating) {
        this.rating = rating;
    }

    public Integer getReviews() {
        return reviews;
    }

    public void setReviews(Integer reviews) {
        this.reviews = reviews;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public List<String> getSizes() {
        return sizes;
    }

    public void setSizes(List<String> sizes) {
        this.sizes = sizes;
    }

    public List<String> getColors() {
        return colors;
    }

    public void setColors(List<String> colors) {
        this.colors = colors;
    }

    public boolean isInStock() {
        return inStock;
    }

    public void setInStock(boolean inStock) {
        this.inStock = inStock;
    }

    public boolean isFeatured() {
        return featured;
    }

    public void setFeatured(boolean featured) {
        this.featured = featured;
    }

    public Integer getStockQuantity() {
        return stockQuantity;
    }

    public void setStockQuantity(Integer stockQuantity) {
        this.stockQuantity = stockQuantity;
        if (stockQuantity != null && stockQuantity <= 0) {
            this.inStock = false;
        }
    }

    public void deductStock(int quantity) {
        if (quantity <= 0) {
            throw new IllegalArgumentException("Deduct quantity must be greater than zero");
        }
        int current = this.stockQuantity != null ? this.stockQuantity : 0;
        if (!this.inStock || current < quantity) {
            throw new com.kuberfashion.backend.exception.InsufficientStockException(
                    "Insufficient stock for product '" + this.name + "'. Available: " + current + ", requested: " + quantity);
        }
        this.stockQuantity = current - quantity;
        if (this.stockQuantity == 0) {
            this.inStock = false;
        }
    }

    public void restoreStock(int quantity) {
        if (quantity <= 0) {
            return;
        }
        int current = this.stockQuantity != null ? this.stockQuantity : 0;
        this.stockQuantity = current + quantity;
        if (this.stockQuantity > 0) {
            this.inStock = true;
        }
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    public Boolean isOffer() {
        return isOffer != null ? isOffer : false;
    }

    public void setOffer(Boolean isOffer) {
        this.isOffer = isOffer != null ? isOffer : false;
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

    public Set<OrderItem> getOrderItems() {
        return orderItems;
    }

    public void setOrderItems(Set<OrderItem> orderItems) {
        this.orderItems = orderItems;
    }

    public Set<WishlistItem> getWishlistItems() {
        return wishlistItems;
    }

    public void setWishlistItems(Set<WishlistItem> wishlistItems) {
        this.wishlistItems = wishlistItems;
    }

    public InventoryTrackingMode getInventoryTrackingMode() {
        return inventoryTrackingMode;
    }

    public void setInventoryTrackingMode(InventoryTrackingMode inventoryTrackingMode) {
        this.inventoryTrackingMode = inventoryTrackingMode != null ? inventoryTrackingMode : InventoryTrackingMode.LEGACY;
    }

    public boolean isVariantTracking() {
        return this.inventoryTrackingMode == InventoryTrackingMode.VARIANT;
    }

    public List<ProductVariant> getVariants() {
        return variants;
    }

    public void setVariants(List<ProductVariant> variants) {
        this.variants = variants != null ? variants : new java.util.ArrayList<>();
    }

    // Helper methods
    public String getCategorySlug() {
        return category != null ? category.getSlug() : null;
    }

    public Long getCategoryId() {
        return category != null ? category.getId() : null;
    }
}
