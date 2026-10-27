package com.kuberfashion.backend.service.ai;

import com.kuberfashion.backend.dto.AiGenerationRequestDto;
import com.kuberfashion.backend.dto.AiGenerationResponseDto;
import com.kuberfashion.backend.exception.AiServiceException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Core business service for AI-assisted product content generation.
 *
 * Enforces business rules:
 * 1. Strict input validation and sanitization.
 * 2. Provider selection and outage/rate-limit resilience.
 * 3. Output bounds validation (e.g. description <= 950 chars to avoid DB constraint failures).
 * 4. Purity guarantee: Absolutely no persistence/publishing occurs during AI generation.
 */
@Service
public class AiProductContentService {

    private static final Logger logger = LoggerFactory.getLogger(AiProductContentService.class);

    private final GeminiAiContentProvider geminiProvider;
    private final TemplateFallbackAiContentProvider fallbackProvider;

    @Autowired
    public AiProductContentService(
            GeminiAiContentProvider geminiProvider,
            TemplateFallbackAiContentProvider fallbackProvider) {
        this.geminiProvider = geminiProvider;
        this.fallbackProvider = fallbackProvider;
    }

    /**
     * Generates a reviewable draft of catalog content based on admin product hints.
     *
     * @param request admin-provided hints
     * @return AI-generated draft ready for admin review
     */
    public AiGenerationResponseDto generateContent(AiGenerationRequestDto request) {
        validateRequest(request);

        AiContentProvider activeProvider = geminiProvider.isAvailable() ? geminiProvider : fallbackProvider;
        logger.info("Generating AI product content using provider: {}", activeProvider.getProviderName());

        AiGenerationResponseDto rawResponse = activeProvider.generateProductContent(request);

        return sanitizeAndEnforceSafety(rawResponse, request);
    }

    private void validateRequest(AiGenerationRequestDto request) {
        if (request == null) {
            throw new AiServiceException("Request body cannot be null", HttpStatus.BAD_REQUEST);
        }

        if (request.getProductName() == null || request.getProductName().trim().isEmpty()) {
            throw new AiServiceException("Product name is required for AI generation", HttpStatus.BAD_REQUEST);
        }

        if (request.getProductName().trim().length() > 200) {
            throw new AiServiceException("Product name must not exceed 200 characters", HttpStatus.BAD_REQUEST);
        }

        // Clean up input fields
        request.setProductName(request.getProductName().trim());
        if (request.getCategoryHint() != null) {
            request.setCategoryHint(request.getCategoryHint().trim());
        }
        if (request.getFabric() != null) {
            request.setFabric(request.getFabric().trim());
        }
        if (request.getIntendedUse() != null) {
            request.setIntendedUse(request.getIntendedUse().trim());
        }
    }

    private AiGenerationResponseDto sanitizeAndEnforceSafety(
            AiGenerationResponseDto response,
            AiGenerationRequestDto request) {

        if (response == null) {
            throw new AiServiceException("Provider produced null response", HttpStatus.INTERNAL_SERVER_ERROR);
        }

        // 1. Description safety (Product entity column length is 1000)
        String description = response.getDescription();
        if (description != null) {
            description = description.trim();
            if (description.length() > 950) {
                description = description.substring(0, 947) + "...";
            }
        } else {
            description = "High quality " + request.getProductName() + " designed for style and comfort.";
        }
        response.setDescription(description);

        // 2. Bullet points sanitization
        if (response.getBulletPoints() != null) {
            List<String> cleanBullets = response.getBulletPoints().stream()
                    .filter(b -> b != null && !b.isBlank())
                    .map(String::trim)
                    .limit(5)
                    .collect(Collectors.toList());
            response.setBulletPoints(cleanBullets);
        } else {
            response.setBulletPoints(new ArrayList<>());
        }

        // 3. Search tags normalization (lowercase, deduplicated, alphanumeric + hyphen)
        if (response.getSearchTags() != null) {
            Set<String> uniqueTags = response.getSearchTags().stream()
                    .filter(t -> t != null && !t.isBlank())
                    .map(t -> t.trim().toLowerCase().replaceAll("[^a-z0-9-]", ""))
                    .filter(t -> !t.isEmpty())
                    .collect(Collectors.toCollection(LinkedHashSet::new));
            response.setSearchTags(new ArrayList<>(uniqueTags));
        } else {
            response.setSearchTags(new ArrayList<>());
        }

        // 4. SEO Title bounds (<= 60 chars)
        if (response.getSeoTitle() != null) {
            String title = response.getSeoTitle().trim();
            if (title.length() > 60) {
                title = title.substring(0, 57) + "...";
            }
            response.setSeoTitle(title);
        } else {
            response.setSeoTitle(request.getProductName() + " | KuberFashion");
        }

        // 5. SEO Meta Description bounds (<= 155 chars)
        if (response.getSeoMetaDescription() != null) {
            String meta = response.getSeoMetaDescription().trim();
            if (meta.length() > 155) {
                meta = meta.substring(0, 152) + "...";
            }
            response.setSeoMetaDescription(meta);
        }

        // 6. Category Slug fallback
        if (response.getSuggestedCategorySlug() == null || response.getSuggestedCategorySlug().isBlank()) {
            response.setSuggestedCategorySlug(
                    request.getCategoryHint() != null && !request.getCategoryHint().isBlank()
                            ? request.getCategoryHint()
                            : "mens-fashion"
            );
        }

        // 7. Ensure missing details list is populated if missing important hints
        if (response.getMissingDetails() == null) {
            response.setMissingDetails(new ArrayList<>());
        }

        return response;
    }
}
