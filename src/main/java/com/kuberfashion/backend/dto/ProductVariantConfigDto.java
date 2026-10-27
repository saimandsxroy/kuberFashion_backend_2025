package com.kuberfashion.backend.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public class ProductVariantConfigDto {

    @NotBlank(message = "Size is required")
    @Size(max = 50, message = "Size must not exceed 50 characters")
    private String size;

    @NotBlank(message = "Color is required")
    @Size(max = 50, message = "Color must not exceed 50 characters")
    private String color;

    @Size(max = 100, message = "SKU must not exceed 100 characters")
    private String sku;

    @NotNull(message = "Stock quantity is required")
    @Min(value = 0, message = "Stock quantity must be non-negative")
    private Integer stockQuantity = 0;

    private Boolean active = true;

    public ProductVariantConfigDto() {
    }

    public ProductVariantConfigDto(String size, String color, Integer stockQuantity, String sku) {
        this(size, color, stockQuantity, sku, true);
    }

    public ProductVariantConfigDto(String size, String color, Integer stockQuantity, String sku, Boolean active) {
        this.size = size;
        this.color = color;
        this.stockQuantity = stockQuantity;
        this.sku = sku;
        this.active = active != null ? active : true;
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

    public Boolean getActive() {
        return active;
    }

    public void setActive(Boolean active) {
        this.active = active;
    }
}
