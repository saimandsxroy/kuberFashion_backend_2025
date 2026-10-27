package com.kuberfashion.backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * Input supplied by the admin when requesting AI-generated product content.
 * All credentials and API calls remain server-side; this DTO never leaves the backend.
 */
public class AiGenerationRequestDto {

    /** The product name or working title. Required – used as the prompt anchor. */
    @NotBlank(message = "Product name is required for AI generation")
    @Size(max = 200, message = "Product name must not exceed 200 characters")
    private String productName;

    /** Category hint (e.g. "mens-fashion", "accessories"). */
    @Size(max = 100)
    private String categoryHint;

    /** Fabric or material (e.g. "cotton", "polyester blend"). */
    @Size(max = 200)
    private String fabric;

    /** Available sizes (e.g. ["S", "M", "L", "XL"]). */
    private List<String> sizes;

    /** Available colours (e.g. ["Red", "Navy", "Olive"]). */
    private List<String> colors;

    /** Intended use / occasion (e.g. "casual", "office", "sportswear"). */
    @Size(max = 200)
    private String intendedUse;

    /** Any additional notes the admin wants to convey to the AI (optional). */
    @Size(max = 500, message = "Additional notes must not exceed 500 characters")
    private String additionalNotes;

    // --- Getters and Setters ---

    public String getProductName() { return productName; }
    public void setProductName(String productName) { this.productName = productName; }

    public String getCategoryHint() { return categoryHint; }
    public void setCategoryHint(String categoryHint) { this.categoryHint = categoryHint; }

    public String getFabric() { return fabric; }
    public void setFabric(String fabric) { this.fabric = fabric; }

    public List<String> getSizes() { return sizes; }
    public void setSizes(List<String> sizes) { this.sizes = sizes; }

    public List<String> getColors() { return colors; }
    public void setColors(List<String> colors) { this.colors = colors; }

    public String getIntendedUse() { return intendedUse; }
    public void setIntendedUse(String intendedUse) { this.intendedUse = intendedUse; }

    public String getAdditionalNotes() { return additionalNotes; }
    public void setAdditionalNotes(String additionalNotes) { this.additionalNotes = additionalNotes; }
}
