package com.kuberfashion.backend.dto;

import java.util.List;

/**
 * The AI-generated product content returned to the admin frontend.
 *
 * This is a DRAFT — it is never automatically persisted to the database.
 * The admin reviews and manually applies (or discards) each field.
 */
public class AiGenerationResponseDto {

    /** Long-form product description (up to ~250 words). */
    private String description;

    /** 3–5 short marketing bullet points for the product listing. */
    private List<String> bulletPoints;

    /** 5–10 searchable tags for catalog and SEO discovery. */
    private List<String> searchTags;

    /** SEO-optimised page title (≤ 60 characters). */
    private String seoTitle;

    /** SEO meta description (≤ 155 characters). */
    private String seoMetaDescription;

    /** Suggested category slug based on the provided hints. */
    private String suggestedCategorySlug;

    /**
     * Fields the AI detected as missing or incomplete from the input hints.
     * Helps the admin know what to add before creating the product listing.
     */
    private List<String> missingDetails;

    // --- Getters and Setters ---

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public List<String> getBulletPoints() { return bulletPoints; }
    public void setBulletPoints(List<String> bulletPoints) { this.bulletPoints = bulletPoints; }

    public List<String> getSearchTags() { return searchTags; }
    public void setSearchTags(List<String> searchTags) { this.searchTags = searchTags; }

    public String getSeoTitle() { return seoTitle; }
    public void setSeoTitle(String seoTitle) { this.seoTitle = seoTitle; }

    public String getSeoMetaDescription() { return seoMetaDescription; }
    public void setSeoMetaDescription(String seoMetaDescription) { this.seoMetaDescription = seoMetaDescription; }

    public String getSuggestedCategorySlug() { return suggestedCategorySlug; }
    public void setSuggestedCategorySlug(String suggestedCategorySlug) { this.suggestedCategorySlug = suggestedCategorySlug; }

    public List<String> getMissingDetails() { return missingDetails; }
    public void setMissingDetails(List<String> missingDetails) { this.missingDetails = missingDetails; }
}
