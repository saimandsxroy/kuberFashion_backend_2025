package com.kuberfashion.backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * Request payload for AI-powered natural-language product search,
 * supporting optional manual chip overrides by the customer.
 */
public class AiSearchRequestDto {

    @NotBlank(message = "Search query cannot be blank")
    @Size(max = 200, message = "Search query must not exceed 200 characters")
    private String query;

    // Optional manual filter overrides if the customer edits chips in the UI
    private String keywords;
    private String category;
    private String color;
    private String size;
    private BigDecimal minPrice;
    private BigDecimal maxPrice;
    private Boolean inStockOnly;

    public AiSearchRequestDto() {
    }

    public AiSearchRequestDto(String query) {
        this.query = query;
    }

    public String getQuery() {
        return query;
    }

    public void setQuery(String query) {
        this.query = query;
    }

    public String getKeywords() {
        return keywords;
    }

    public void setKeywords(String keywords) {
        this.keywords = keywords;
    }

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public String getColor() {
        return color;
    }

    public void setColor(String color) {
        this.color = color;
    }

    public String getSize() {
        return size;
    }

    public void setSize(String size) {
        this.size = size;
    }

    public BigDecimal getMinPrice() {
        return minPrice;
    }

    public void setMinPrice(BigDecimal minPrice) {
        this.minPrice = minPrice;
    }

    public BigDecimal getMaxPrice() {
        return maxPrice;
    }

    public void setMaxPrice(BigDecimal maxPrice) {
        this.maxPrice = maxPrice;
    }

    public Boolean getInStockOnly() {
        return inStockOnly;
    }

    public void setInStockOnly(Boolean inStockOnly) {
        this.inStockOnly = inStockOnly;
    }
}
