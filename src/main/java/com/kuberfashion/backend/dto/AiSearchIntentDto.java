package com.kuberfashion.backend.dto;

import java.math.BigDecimal;

/**
 * Structured search intent extracted from natural-language customer queries.
 */
public class AiSearchIntentDto {

    private String rawQuery;
    private String keywords;
    private String category;
    private String color;
    private String size;
    private BigDecimal minPrice;
    private BigDecimal maxPrice;
    private Boolean inStockOnly = false;
    private String interpretationSummary;
    private boolean aiInterpreted = true;
    private String fallbackReason;

    public AiSearchIntentDto() {
    }

    public AiSearchIntentDto(String rawQuery) {
        this.rawQuery = rawQuery;
    }

    public String getRawQuery() {
        return rawQuery;
    }

    public void setRawQuery(String rawQuery) {
        this.rawQuery = rawQuery;
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

    public String getInterpretationSummary() {
        return interpretationSummary;
    }

    public void setInterpretationSummary(String interpretationSummary) {
        this.interpretationSummary = interpretationSummary;
    }

    public boolean isAiInterpreted() {
        return aiInterpreted;
    }

    public void setAiInterpreted(boolean aiInterpreted) {
        this.aiInterpreted = aiInterpreted;
    }

    public String getFallbackReason() {
        return fallbackReason;
    }

    public void setFallbackReason(String fallbackReason) {
        this.fallbackReason = fallbackReason;
    }
}
