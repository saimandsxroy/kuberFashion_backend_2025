package com.kuberfashion.backend.service.ai;

import com.kuberfashion.backend.dto.AiGenerationRequestDto;
import com.kuberfashion.backend.dto.AiGenerationResponseDto;

/**
 * Standard interface for AI product description and catalog assistant providers.
 */
public interface AiContentProvider {

    /**
     * Generate structured catalog content based on admin product hints.
     *
     * @param request the input hints provided by the administrator
     * @return generated product description, bullets, search tags, SEO metadata, etc.
     */
    AiGenerationResponseDto generateProductContent(AiGenerationRequestDto request);

    /**
     * Check if this provider is currently available (configured with credentials and healthy).
     */
    boolean isAvailable();

    /**
     * Human-readable provider identifier (e.g., "Google Gemini", "Fallback Template Generator").
     */
    String getProviderName();
}
