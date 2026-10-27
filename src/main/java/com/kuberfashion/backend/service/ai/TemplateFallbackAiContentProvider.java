package com.kuberfashion.backend.service.ai;

import com.kuberfashion.backend.dto.AiGenerationRequestDto;
import com.kuberfashion.backend.dto.AiGenerationResponseDto;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Intelligent deterministic fallback provider that generates structured catalog content
 * when external LLM services are unconfigured, unreachable, or rate-limited.
 */
@Component
public class TemplateFallbackAiContentProvider implements AiContentProvider {

    @Override
    public AiGenerationResponseDto generateProductContent(AiGenerationRequestDto request) {
        String name = request.getProductName() != null ? request.getProductName().trim() : "Fashion Item";
        String fabric = (request.getFabric() != null && !request.getFabric().isBlank())
                ? request.getFabric().trim()
                : "premium high-grade fabric";
        String category = (request.getCategoryHint() != null && !request.getCategoryHint().isBlank())
                ? request.getCategoryHint().trim()
                : "mens-fashion";
        String intendedUse = (request.getIntendedUse() != null && !request.getIntendedUse().isBlank())
                ? request.getIntendedUse().trim()
                : "everyday versatile wear";

        AiGenerationResponseDto response = new AiGenerationResponseDto();

        // 1. Description (kept <= 800 chars for Product entity safety)
        String description = String.format(
                "Elevate your wardrobe with the %s. Crafted from %s, this piece offers an exceptional balance " +
                "of comfort, durability, and contemporary styling. Designed for %s, it features precision tailoring " +
                "and a refined silhouette that effortlessly transitions from day to evening. A timeless essential " +
                "curated exclusively for the modern fashion connoisseur.",
                name, fabric, intendedUse
        );
        response.setDescription(description);

        // 2. Bullet Points
        List<String> bullets = new ArrayList<>();
        bullets.add("Tailored from breathable " + fabric + " for all-day comfort.");
        bullets.add("Modern ergonomic cut suited for " + intendedUse + ".");
        if (request.getColors() != null && !request.getColors().isEmpty()) {
            bullets.add("Available in signature colorways: " + String.join(", ", request.getColors()) + ".");
        } else {
            bullets.add("Versatile neutral styling suitable for mixing and matching.");
        }
        if (request.getSizes() != null && !request.getSizes().isEmpty()) {
            bullets.add("Standard fit across sizes " + String.join(", ", request.getSizes()) + ".");
        }
        bullets.add("Easy-care construction with shape and color retention.");
        response.setBulletPoints(bullets);

        // 3. Search Tags
        List<String> tags = new ArrayList<>();
        tags.add(name.toLowerCase());
        tags.add(category.toLowerCase());
        if (request.getFabric() != null && !request.getFabric().isBlank()) {
            tags.add(request.getFabric().toLowerCase());
        }
        tags.addAll(Arrays.asList("kuberfashion", "trend", "stylish", "lifestyle", "apparel"));
        response.setSearchTags(tags);

        // 4. SEO Title (<= 60 chars)
        String seoTitle = name + " | KuberFashion";
        if (seoTitle.length() > 60) {
            seoTitle = seoTitle.substring(0, 57) + "...";
        }
        response.setSeoTitle(seoTitle);

        // 5. SEO Meta Description (<= 155 chars)
        String metaDesc = String.format("Discover %s in %s at KuberFashion. Ideal for %s with nationwide delivery.",
                name, fabric, intendedUse);
        if (metaDesc.length() > 155) {
            metaDesc = metaDesc.substring(0, 152) + "...";
        }
        response.setSeoMetaDescription(metaDesc);

        // 6. Category Suggestion
        response.setSuggestedCategorySlug(category);

        // 7. Missing details feedback
        List<String> missing = new ArrayList<>();
        if (request.getFabric() == null || request.getFabric().isBlank()) {
            missing.add("Fabric/material specification");
        }
        if (request.getSizes() == null || request.getSizes().isEmpty()) {
            missing.add("Available sizes (e.g. S, M, L, XL)");
        }
        if (request.getColors() == null || request.getColors().isEmpty()) {
            missing.add("Available color variants");
        }
        if (request.getIntendedUse() == null || request.getIntendedUse().isBlank()) {
            missing.add("Intended occasion / use case");
        }
        response.setMissingDetails(missing);

        return response;
    }

    @Override
    public boolean isAvailable() {
        return true;
    }

    @Override
    public String getProviderName() {
        return "Fallback Template Content Generator";
    }
}
