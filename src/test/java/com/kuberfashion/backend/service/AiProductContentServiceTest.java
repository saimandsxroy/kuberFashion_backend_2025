package com.kuberfashion.backend.service;

import com.kuberfashion.backend.dto.AiGenerationRequestDto;
import com.kuberfashion.backend.dto.AiGenerationResponseDto;
import com.kuberfashion.backend.exception.AiServiceException;
import com.kuberfashion.backend.service.ai.AiProductContentService;
import com.kuberfashion.backend.service.ai.GeminiAiContentProvider;
import com.kuberfashion.backend.service.ai.TemplateFallbackAiContentProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AiProductContentServiceTest {

    @Mock
    private GeminiAiContentProvider geminiProvider;

    private TemplateFallbackAiContentProvider fallbackProvider;
    private AiProductContentService aiProductContentService;

    @BeforeEach
    void setUp() {
        fallbackProvider = new TemplateFallbackAiContentProvider();
        aiProductContentService = new AiProductContentService(geminiProvider, fallbackProvider);
    }

    @Test
    @DisplayName("Throws BAD_REQUEST when request body is null")
    void shouldThrowWhenRequestIsNull() {
        assertThatThrownBy(() -> aiProductContentService.generateContent(null))
                .isInstanceOf(AiServiceException.class)
                .hasMessageContaining("Request body cannot be null")
                .matches(e -> ((AiServiceException) e).getHttpStatus() == HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("Throws BAD_REQUEST when product name is empty or blank")
    void shouldThrowWhenProductNameIsBlank() {
        AiGenerationRequestDto request = new AiGenerationRequestDto();
        request.setProductName("   ");

        assertThatThrownBy(() -> aiProductContentService.generateContent(request))
                .isInstanceOf(AiServiceException.class)
                .hasMessageContaining("Product name is required")
                .matches(e -> ((AiServiceException) e).getHttpStatus() == HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("Throws BAD_REQUEST when product name exceeds 200 characters")
    void shouldThrowWhenProductNameTooLong() {
        AiGenerationRequestDto request = new AiGenerationRequestDto();
        request.setProductName("A".repeat(201));

        assertThatThrownBy(() -> aiProductContentService.generateContent(request))
                .isInstanceOf(AiServiceException.class)
                .hasMessageContaining("must not exceed 200 characters")
                .matches(e -> ((AiServiceException) e).getHttpStatus() == HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("Uses fallback provider when Gemini is unavailable")
    void shouldUseFallbackWhenGeminiUnavailable() {
        when(geminiProvider.isAvailable()).thenReturn(false);

        AiGenerationRequestDto request = new AiGenerationRequestDto();
        request.setProductName("Slim Fit Linen Shirt");
        request.setCategoryHint("mens-fashion");
        request.setFabric("Pure Linen");
        request.setSizes(List.of("M", "L", "XL"));
        request.setColors(List.of("White", "Sky Blue"));
        request.setIntendedUse("Smart casual events");

        AiGenerationResponseDto result = aiProductContentService.generateContent(request);

        assertThat(result).isNotNull();
        assertThat(result.getDescription()).contains("Slim Fit Linen Shirt");
        assertThat(result.getDescription().length()).isLessThanOrEqualTo(950);
        assertThat(result.getBulletPoints()).isNotEmpty();
        assertThat(result.getSearchTags()).contains("mens-fashion");
        assertThat(result.getSeoTitle()).contains("Slim Fit Linen Shirt");
        assertThat(result.getSeoTitle().length()).isLessThanOrEqualTo(60);
        assertThat(result.getSeoMetaDescription().length()).isLessThanOrEqualTo(155);

        // Verify Gemini API was NOT invoked when unavailable
        verify(geminiProvider, never()).generateProductContent(any());
    }

    @Test
    @DisplayName("Uses Gemini when available and sanitizes output bounds")
    void shouldUseGeminiWhenAvailableAndSanitize() {
        when(geminiProvider.isAvailable()).thenReturn(true);

        AiGenerationResponseDto mockResponse = new AiGenerationResponseDto();
        // Set an oversized description to test truncation
        mockResponse.setDescription("D".repeat(1200));
        mockResponse.setBulletPoints(Arrays.asList("  Point 1  ", "Point 2", "Point 3", "Point 4", "Point 5", "Point 6"));
        mockResponse.setSearchTags(Arrays.asList("Linen", "Shirt", "linen", "SHIRT", "Special!@#Tag"));
        mockResponse.setSeoTitle("T".repeat(80));
        mockResponse.setSeoMetaDescription("M".repeat(200));

        when(geminiProvider.generateProductContent(any())).thenReturn(mockResponse);

        AiGenerationRequestDto request = new AiGenerationRequestDto();
        request.setProductName("Luxury Linen Shirt");

        AiGenerationResponseDto result = aiProductContentService.generateContent(request);

        assertThat(result).isNotNull();
        // Description must be truncated to <= 950 for DB column safety
        assertThat(result.getDescription().length()).isLessThanOrEqualTo(950);
        assertThat(result.getDescription()).endsWith("...");

        // Bullet points should be limited to 5 and trimmed
        assertThat(result.getBulletPoints()).hasSize(5);
        assertThat(result.getBulletPoints().get(0)).isEqualTo("Point 1");

        // Search tags should be lowercased, deduplicated, and special characters stripped
        assertThat(result.getSearchTags()).contains("linen", "shirt", "specialtag");
        long linenCount = result.getSearchTags().stream().filter(t -> t.equals("linen")).count();
        assertThat(linenCount).isEqualTo(1);

        // SEO lengths bounded
        assertThat(result.getSeoTitle().length()).isLessThanOrEqualTo(60);
        assertThat(result.getSeoMetaDescription().length()).isLessThanOrEqualTo(155);
    }
}
