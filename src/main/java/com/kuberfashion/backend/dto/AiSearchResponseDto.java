package com.kuberfashion.backend.dto;

import org.springframework.data.domain.Page;

import java.util.List;

/**
 * Encapsulates the interpreted search intent and the resulting database products.
 */
public class AiSearchResponseDto {

    private AiSearchIntentDto intent;
    private List<ProductResponseDto> products;
    private long totalElements;
    private int totalPages;
    private int currentPage;
    private boolean aiInterpreted;
    private String interpretationSummary;

    public AiSearchResponseDto() {
    }

    public AiSearchResponseDto(AiSearchIntentDto intent, Page<ProductResponseDto> page) {
        this.intent = intent;
        this.products = page.getContent();
        this.totalElements = page.getTotalElements();
        this.totalPages = page.getTotalPages();
        this.currentPage = page.getNumber();
        this.aiInterpreted = intent != null && intent.isAiInterpreted();
        this.interpretationSummary = intent != null ? intent.getInterpretationSummary() : null;
    }

    public AiSearchIntentDto getIntent() {
        return intent;
    }

    public void setIntent(AiSearchIntentDto intent) {
        this.intent = intent;
    }

    public List<ProductResponseDto> getProducts() {
        return products;
    }

    public void setProducts(List<ProductResponseDto> products) {
        this.products = products;
    }

    public long getTotalElements() {
        return totalElements;
    }

    public void setTotalElements(long totalElements) {
        this.totalElements = totalElements;
    }

    public int getTotalPages() {
        return totalPages;
    }

    public void setTotalPages(int totalPages) {
        this.totalPages = totalPages;
    }

    public int getCurrentPage() {
        return currentPage;
    }

    public void setCurrentPage(int currentPage) {
        this.currentPage = currentPage;
    }

    public boolean isAiInterpreted() {
        return aiInterpreted;
    }

    public void setAiInterpreted(boolean aiInterpreted) {
        this.aiInterpreted = aiInterpreted;
    }

    public String getInterpretationSummary() {
        return interpretationSummary;
    }

    public void setInterpretationSummary(String interpretationSummary) {
        this.interpretationSummary = interpretationSummary;
    }
}
