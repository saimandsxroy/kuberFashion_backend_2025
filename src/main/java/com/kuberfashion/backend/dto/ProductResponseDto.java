package com.kuberfashion.backend.dto;

import com.kuberfashion.backend.entity.Product;
import com.kuberfashion.backend.entity.ProductVariant;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

public class ProductResponseDto {

    private Long id;
    private String name;
    private String slug;
    private BigDecimal price;
    private BigDecimal originalPrice;
    private Integer discount;
    private String category;
    private Long categoryId;
    private String image;
    private List<String> images;
    private BigDecimal rating;
    private Integer reviews;
    private String description;
    private List<String> sizes;
    private List<String> colors;
    private boolean inStock;
    private boolean featured;
    private boolean isOffer;
    private Integer stockQuantity;
    private String inventoryTrackingMode;
    private List<ProductVariantDto> variants;
    private LocalDateTime createdAt;
    private Double similarityScore;

    // Constructors
    public ProductResponseDto() {
    }

    public ProductResponseDto(Product product) {
        if (product == null) {
            return;
        }

        this.id = product.getId();
        this.name = product.getName();
        this.slug = product.getSlug();
        this.price = product.getPrice();
        this.originalPrice = product.getOriginalPrice();
        this.discount = product.getDiscount();

        // Handle potential null category
        try {
            this.category = product.getCategorySlug();
            this.categoryId = product.getCategoryId();
        } catch (Exception e) {
            this.category = "uncategorized";
            this.categoryId = null;
        }

        this.image = product.getImage();
        this.images = product.getImages();
        this.rating = product.getRating() != null ? product.getRating() : BigDecimal.ZERO;
        this.reviews = product.getReviews() != null ? product.getReviews() : 0;
        this.description = product.getDescription();
        this.sizes = product.getSizes();
        this.colors = product.getColors();
        this.inStock = product.isInStock();
        this.featured = product.isFeatured();
        this.isOffer = product.isOffer();
        this.inventoryTrackingMode = product.getInventoryTrackingMode() != null
                ? product.getInventoryTrackingMode().name()
                : "LEGACY";
        if (product.getVariants() != null && !product.getVariants().isEmpty()) {
            this.variants = product.getVariants().stream()
                    .map(ProductVariantDto::new)
                    .toList();
            if (product.isVariantTracking()) {
                // For variant tracking products, aggregate stock is sum of variant stocks
                this.stockQuantity = product.getVariants().stream()
                        .filter(ProductVariant::isActive)
                        .mapToInt(ProductVariant::getStockQuantity)
                        .sum();
                this.inStock = this.stockQuantity > 0;
            } else {
                this.stockQuantity = product.getStockQuantity() != null ? product.getStockQuantity() : 0;
            }
        } else {
            this.variants = java.util.Collections.emptyList();
            this.stockQuantity = product.getStockQuantity() != null ? product.getStockQuantity() : 0;
        }
        this.createdAt = product.getCreatedAt();
        // Copy transient similarity score if populated by vector/recommendation service
        this.similarityScore = product.getSimilarityScore();
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

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public Long getCategoryId() {
        return categoryId;
    }

    public void setCategoryId(Long categoryId) {
        this.categoryId = categoryId;
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

    public boolean isOffer() {
        return isOffer;
    }

    public void setOffer(boolean offer) {
        isOffer = offer;
    }

    public Integer getStockQuantity() {
        return stockQuantity;
    }

    public void setStockQuantity(Integer stockQuantity) {
        this.stockQuantity = stockQuantity;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public String getInventoryTrackingMode() {
        return inventoryTrackingMode;
    }

    public void setInventoryTrackingMode(String inventoryTrackingMode) {
        this.inventoryTrackingMode = inventoryTrackingMode;
    }

    public List<ProductVariantDto> getVariants() {
        return variants;
    }

    public void setVariants(List<ProductVariantDto> variants) {
        this.variants = variants;
    }

    public Double getSimilarityScore() {
        return similarityScore;
    }

    public void setSimilarityScore(Double similarityScore) {
        this.similarityScore = similarityScore;
    }
}
