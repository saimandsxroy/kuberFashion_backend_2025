package com.kuberfashion.backend.service.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class GeminiEmbeddingServiceTest {

    @Mock
    private RestTemplate restTemplate;

    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
    }

    @Test
    @DisplayName("1. Default configuration selects gemini-embedding-2 and validates API key")
    void testDefaultConfigurationAndAvailability() {
        // Default constructor uses gemini-embedding-2
        GeminiEmbeddingService defaultService = new GeminiEmbeddingService(
                "test-mock-gemini-keyForTest12345", null, 768, 15, restTemplate, objectMapper);
        assertEquals("gemini-embedding-2", defaultService.getModel());
        assertEquals(768, defaultService.getDimension());
        assertTrue(defaultService.isAvailable());

        // Empty or placeholder keys return false
        GeminiEmbeddingService emptyKeyService = new GeminiEmbeddingService(
                "", null, 768, 15, restTemplate, objectMapper);
        assertFalse(emptyKeyService.isAvailable());

        GeminiEmbeddingService placeholderService = new GeminiEmbeddingService(
                "your_api_key_placeholder", null, 768, 15, restTemplate, objectMapper);
        assertFalse(placeholderService.isAvailable());
    }

    @Test
    @DisplayName("2. Request generation uses documented gemini-embedding-2 API format and 768 dimensionality")
    void testDocumentedApiRequestFormat() {
        // Construct dummy 768 values
        StringBuilder valuesJson = new StringBuilder("[");
        for (int i = 0; i < 768; i++) {
            if (i > 0) valuesJson.append(",");
            valuesJson.append(0.001 * (i + 1));
        }
        valuesJson.append("]");

        String mockResponse = "{\"embedding\":{\"values\":" + valuesJson + "}}";

        ArgumentCaptor<String> urlCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<HttpEntity> entityCaptor = ArgumentCaptor.forClass(HttpEntity.class);

        when(restTemplate.postForEntity(urlCaptor.capture(), entityCaptor.capture(), eq(String.class)))
                .thenReturn(new ResponseEntity<>(mockResponse, HttpStatus.OK));

        GeminiEmbeddingService service = new GeminiEmbeddingService(
                "test-mock-gemini-key", "gemini-embedding-2", 768, 15, restTemplate, objectMapper);

        float[] embedding = service.generateEmbedding("Handcrafted Pure Banarasi Silk Saree in Rose Gold");

        assertNotNull(embedding);
        assertEquals(768, embedding.length);

        // Verify endpoint URL
        String capturedUrl = urlCaptor.getValue();
        assertTrue(capturedUrl.contains("gemini-embedding-2:embedContent"),
                "URL must call gemini-embedding-2 endpoint: " + capturedUrl);
        assertTrue(capturedUrl.contains("key=test-mock-gemini-key"));

        // Verify request payload conforms to Google Generative Language API
        HttpEntity capturedEntity = entityCaptor.getValue();
        assertNotNull(capturedEntity);
        Map<String, Object> body = (Map<String, Object>) capturedEntity.getBody();
        assertNotNull(body);
        assertEquals("models/gemini-embedding-2", body.get("model"));
        assertEquals(768, body.get("outputDimensionality"));
    }

    @Test
    @DisplayName("3. Unsupported-model failures (404/400) do NOT fall back to shut-down text-embedding-004")
    void testUnsupportedModelDoesNotFallbackToShutdownModel() {
        when(restTemplate.postForEntity(contains("gemini-embedding-2"), any(HttpEntity.class), eq(String.class)))
                .thenThrow(new HttpClientErrorException(HttpStatus.NOT_FOUND, "Model gemini-embedding-2 not found"));

        GeminiEmbeddingService service = new GeminiEmbeddingService(
                "test-mock-gemini-key", "gemini-embedding-2", 768, 15, restTemplate, objectMapper);

        float[] embedding = service.generateEmbedding("Sample Product");
        assertNull(embedding, "Must return null to preserve relational search fallback instead of calling shut-down models");

        // Verify text-embedding-004 was never called
        verify(restTemplate, never()).postForEntity(contains("text-embedding-004"), any(), any());
    }

    @Test
    @DisplayName("4. Gracefully handles model timeout (ResourceAccessException) without throwing")
    void testTimeoutHandling() {
        when(restTemplate.postForEntity(anyString(), any(HttpEntity.class), eq(String.class)))
                .thenThrow(new ResourceAccessException("Connection timed out"));

        GeminiEmbeddingService service = new GeminiEmbeddingService(
                "test-mock-gemini-key", "gemini-embedding-2", 768, 15, restTemplate, objectMapper);

        float[] embedding = service.generateEmbedding("Sample Product");
        assertNull(embedding, "Embedding should be null on network timeout, without bubbling exception");
    }

    @Test
    @DisplayName("5. Gracefully handles rate limit HTTP 429 without throwing")
    void testRateLimitHandling() {
        when(restTemplate.postForEntity(anyString(), any(HttpEntity.class), eq(String.class)))
                .thenThrow(new HttpClientErrorException(HttpStatus.TOO_MANY_REQUESTS, "Quota exceeded"));

        GeminiEmbeddingService service = new GeminiEmbeddingService(
                "test-mock-gemini-key", "gemini-embedding-2", 768, 15, restTemplate, objectMapper);

        float[] embedding = service.generateEmbedding("Sample Product");
        assertNull(embedding, "Embedding should be null on rate limit HTTP 429");
    }

    @Test
    @DisplayName("6. Gracefully handles malformed JSON responses")
    void testMalformedJsonResponse() {
        when(restTemplate.postForEntity(anyString(), any(HttpEntity.class), eq(String.class)))
                .thenReturn(new ResponseEntity<>("Not Valid JSON response from proxy", HttpStatus.OK));

        GeminiEmbeddingService service = new GeminiEmbeddingService(
                "test-mock-gemini-key", "gemini-embedding-2", 768, 15, restTemplate, objectMapper);

        float[] embedding = service.generateEmbedding("Sample Product");
        assertNull(embedding);
    }

    @Test
    @DisplayName("7. Empty or blank query returns null immediately without HTTP request")
    void testBlankQueryBypassesNetwork() {
        GeminiEmbeddingService service = new GeminiEmbeddingService(
                "test-mock-gemini-key", "gemini-embedding-2", 768, 15, restTemplate, objectMapper);

        assertNull(service.generateEmbedding(""));
        assertNull(service.generateEmbedding("   "));
        assertNull(service.generateEmbedding(null));

        verifyNoInteractions(restTemplate);
    }
}
