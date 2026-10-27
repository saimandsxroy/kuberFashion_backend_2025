package com.kuberfashion.backend.dto;

import com.kuberfashion.backend.entity.ProductVariant;

import java.time.LocalDateTime;

public class ProductVariantDto {

    private Long id;
    private Long productId;
    private String size;
    private String color;
    private String sku;
    private Integer stockQuantity;
    private boolean active;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public ProductVariantDto() {
    }

    public ProductVariantDto(ProductVariant variant) {
        if (variant == null) {
            return;
        }
        this.id = variant.getId();
        this.productId = variant.getProduct() != null ? variant.getProduct().getId() : null;
        this.size = variant.getSize();
        this.color = variant.getColor();
        this.sku = variant.getSku();
        this.stockQuantity = variant.getStockQuantity();
        this.active = variant.isActive();
        this.createdAt = variant.getCreatedAt();
        this.updatedAt = variant.getUpdatedAt();
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getProductId() {
        return productId;
    }

    public void setProductId(Long productId) {
        this.productId = productId;
    }

    public String getSize() {
        return size;
    }

    public void setSize(String size) {
        this.size = size;
    }

    public String getColor() {
        return color;
    }

    public void setColor(String color) {
        this.color = color;
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
        this.stockQuantity = stockQuantity;
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
