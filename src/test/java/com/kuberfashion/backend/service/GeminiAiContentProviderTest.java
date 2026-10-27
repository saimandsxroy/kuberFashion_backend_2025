package com.kuberfashion.backend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kuberfashion.backend.dto.AiGenerationRequestDto;
import com.kuberfashion.backend.dto.AiGenerationResponseDto;
import com.kuberfashion.backend.service.ai.GeminiAiContentProvider;
import com.kuberfashion.backend.service.ai.TemplateFallbackAiContentProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GeminiAiContentProviderTest {

    @Mock
    private RestTemplate restTemplate;

    private ObjectMapper objectMapper;
    private TemplateFallbackAiContentProvider fallbackProvider;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        fallbackProvider = new TemplateFallbackAiContentProvider();
    }

    @Test
    @DisplayName("Successfully parses valid JSON payload from Gemini response with markdown fences")
    void shouldParseGeminiResponseWithMarkdownFences() {
        GeminiAiContentProvider provider = new GeminiAiContentProvider(
                "fake-api-key",
                "gemini-1.5-flash",
                10,
                true,
                restTemplate,
                objectMapper,
                fallbackProvider
        );

        String geminiJson = """
                {
                  "candidates": [
                    {
                      "content": {
                        "parts": [
                          {
                            "text": "```json\\n{\\n  \\"description\\": \\"Exquisite handcrafted silk kurta for festive celebrations.\\",\\n  \\"bulletPoints\\": [\\"Pure mulberry silk\\", \\"Hand-embroidered neckline\\"],\\n  \\"searchTags\\": [\\"silk\\", \\"kurta\\", \\"festive\\"],\\n  \\"seoTitle\\": \\"Men Festive Silk Kurta | KuberFashion\\",\\n  \\"seoMetaDescription\\": \\"Shop exclusive mulberry silk kurta for men.\\",\\n  \\"suggestedCategorySlug\\": \\"mens-fashion\\",\\n  \\"missingDetails\\": [\\"wash care instructions\\"]\\n}\\n```"
                          }
                        ]
                      }
                    }
                  ]
                }
                """;

        when(restTemplate.postForEntity(anyString(), any(HttpEntity.class), eq(String.class)))
                .thenReturn(new ResponseEntity<>(geminiJson, HttpStatus.OK));

        AiGenerationRequestDto request = new AiGenerationRequestDto();
        request.setProductName("Silk Kurta");

        AiGenerationResponseDto response = provider.generateProductContent(request);

        assertThat(response).isNotNull();
        assertThat(response.getDescription()).isEqualTo("Exquisite handcrafted silk kurta for festive celebrations.");
        assertThat(response.getBulletPoints()).contains("Pure mulberry silk", "Hand-embroidered neckline");
        assertThat(response.getSearchTags()).contains("silk", "kurta", "festive");
        assertThat(response.getSuggestedCategorySlug()).isEqualTo("mens-fashion");
    }

    @Test
    @DisplayName("Falls back gracefully to template provider on 429 Too Many Requests")
    void shouldFallbackOn429RateLimit() {
        GeminiAiContentProvider provider = new GeminiAiContentProvider(
                "fake-api-key",
                "gemini-1.5-flash",
                10,
                true, // fallbackEnabled
                restTemplate,
                objectMapper,
                fallbackProvider
        );

        when(restTemplate.postForEntity(anyString(), any(HttpEntity.class), eq(String.class)))
                .thenThrow(new HttpClientErrorException(HttpStatus.TOO_MANY_REQUESTS));

        AiGenerationRequestDto request = new AiGenerationRequestDto();
        request.setProductName("Silk Kurta");

        AiGenerationResponseDto response = provider.generateProductContent(request);

        assertThat(response).isNotNull();
        assertThat(response.getDescription()).contains("Silk Kurta");
        assertThat(response.getBulletPoints()).isNotEmpty();
    }

    @Test
    @DisplayName("Falls back gracefully to template provider on network timeout")
    void shouldFallbackOnTimeout() {
        GeminiAiContentProvider provider = new GeminiAiContentProvider(
                "fake-api-key",
                "gemini-1.5-flash",
                10,
                true, // fallbackEnabled
                restTemplate,
                objectMapper,
                fallbackProvider
        );

        when(restTemplate.postForEntity(anyString(), any(HttpEntity.class), eq(String.class)))
                .thenThrow(new ResourceAccessException("Read timed out"));

        AiGenerationRequestDto request = new AiGenerationRequestDto();
        request.setProductName("Linen Trousers");

        AiGenerationResponseDto response = provider.generateProductContent(request);

        assertThat(response).isNotNull();
        assertThat(response.getDescription()).contains("Linen Trousers");
        assertThat(response.getBulletPoints()).isNotEmpty();
    }

    @Test
    @DisplayName("Delegates to template provider immediately when API key is empty")
    void shouldDelegateWhenApiKeyEmpty() {
        GeminiAiContentProvider provider = new GeminiAiContentProvider(
                "",
                "gemini-1.5-flash",
                10,
                true,
                restTemplate,
                objectMapper,
                fallbackProvider
        );

        assertThat(provider.isAvailable()).isFalse();

        AiGenerationRequestDto request = new AiGenerationRequestDto();
        request.setProductName("Cotton Tee");

        AiGenerationResponseDto response = provider.generateProductContent(request);

        assertThat(response).isNotNull();
        assertThat(response.getDescription()).contains("Cotton Tee");
    }
}
