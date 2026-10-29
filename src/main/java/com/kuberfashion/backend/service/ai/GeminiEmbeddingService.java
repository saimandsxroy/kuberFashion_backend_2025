package com.kuberfashion.backend.service.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.util.*;

/**
 * Service for generating 768-dimensional vector embeddings using Google Generative AI (Gemini).
 * Fully resilient with automatic fallback handling, timeout protection, and non-throwing error safety.
 */
@Service
public class GeminiEmbeddingService {

    private static final Logger logger = LoggerFactory.getLogger(GeminiEmbeddingService.class);
    private static final String GEMINI_BASE_URL = "https://generativelanguage.googleapis.com/v1beta/models/";

    private final String apiKey;
    private final String model;
    private final int dimension;
    private final int timeoutSeconds;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    @Autowired
    public GeminiEmbeddingService(
            @Value("${ai.gemini.api-key:}") String apiKey,
            @Value("${ai.gemini.embedding-model:gemini-embedding-2}") String model,
            @Value("${ai.gemini.embedding-dimension:768}") int dimension,
            @Value("${ai.gemini.timeout-seconds:15}") int timeoutSeconds,
            ObjectMapper objectMapper) {
        this.apiKey = apiKey != null ? apiKey.trim() : "";
        this.model = model != null && !model.isBlank() ? model.trim() : "gemini-embedding-2";
        this.dimension = dimension > 0 ? dimension : 768;
        this.timeoutSeconds = timeoutSeconds > 0 ? timeoutSeconds : 15;
        this.objectMapper = objectMapper != null ? objectMapper : new ObjectMapper();

        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(this.timeoutSeconds * 1000);
        factory.setReadTimeout(this.timeoutSeconds * 1000);
        this.restTemplate = new RestTemplate(factory);
    }

    // Testing constructor for dependency injection
    public GeminiEmbeddingService(
            String apiKey,
            String model,
            int dimension,
            int timeoutSeconds,
            RestTemplate restTemplate,
            ObjectMapper objectMapper) {
        this.apiKey = apiKey != null ? apiKey.trim() : "";
        this.model = model != null && !model.isBlank() ? model.trim() : "gemini-embedding-2";
        this.dimension = dimension > 0 ? dimension : 768;
        this.timeoutSeconds = timeoutSeconds > 0 ? timeoutSeconds : 15;
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper != null ? objectMapper : new ObjectMapper();
    }

    public boolean isAvailable() {
        if (apiKey == null || apiKey.isBlank()) {
            return false;
        }
        String lower = apiKey.toLowerCase(Locale.ROOT).trim();
        return !lower.startsWith("your_")
                && !lower.contains("placeholder")
                && !lower.equals("none");
    }

    public String getModel() {
        return model;
    }

    public int getDimension() {
        return dimension;
    }

    /**
     * Generates a 768-dimensional float embedding using Google's gemini-embedding-2 model.
     * Returns null if embedding generation fails or service is unavailable, preserving
     * keyword/relational search fallback without generating incompatible vectors.
     *
     * @param text input catalog or query text
     * @return float array of embedding values, or null if generation fails
     */
    public float[] generateEmbedding(String text) {
        if (text == null || text.trim().isEmpty()) {
            return null;
        }
        if (!isAvailable()) {
            logger.debug("Gemini API key is not configured; skipping remote embedding generation.");
            return null;
        }

        return callEmbeddingApi(model, text.trim());
    }

    private float[] callEmbeddingApi(String targetModel, String cleanText) {
        String url = GEMINI_BASE_URL + targetModel + ":embedContent?key=" + apiKey;

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("model", "models/" + targetModel);

        Map<String, Object> content = new HashMap<>();
        content.put("parts", Collections.singletonList(Collections.singletonMap("text", cleanText)));
        requestBody.put("content", content);
        requestBody.put("outputDimensionality", dimension);

        try {
            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(requestBody, headers);
            ResponseEntity<String> response = restTemplate.postForEntity(url, entity, String.class);

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                return parseEmbeddingResponse(response.getBody());
            } else {
                logger.warn("Gemini embedding API returned non-2xx status: {}", response.getStatusCode());
                return null;
            }
        } catch (HttpClientErrorException.TooManyRequests e) {
            logger.warn("Gemini embedding rate limit (429) hit: {}", e.getMessage());
            return null;
        } catch (HttpClientErrorException.NotFound e) {
            logger.warn("Gemini embedding model '{}' not found (404): {}", targetModel, e.getMessage());
            return null;
        } catch (HttpClientErrorException.BadRequest e) {
            logger.error("Gemini embedding 400 Bad Request: {}", e.getResponseBodyAsString());
            return null;
        } catch (ResourceAccessException e) {
            logger.warn("Gemini embedding API connection timed out: {}", e.getMessage());
            return null;
        } catch (HttpServerErrorException e) {
            logger.warn("Gemini embedding server error ({}): {}", e.getStatusCode(), e.getMessage());
            return null;
        } catch (Exception e) {
            logger.error("Unexpected error calling Gemini embedding API: {}", e.getMessage());
            return null;
        }
    }

    private float[] parseEmbeddingResponse(String responseJson) {
        try {
            JsonNode root = objectMapper.readTree(responseJson);
            JsonNode valuesNode = root.path("embedding").path("values");

            if (!valuesNode.isArray() || valuesNode.isEmpty()) {
                logger.warn("Gemini response missing or empty embedding.values");
                return null;
            }

            int size = valuesNode.size();
            float[] values = new float[size];
            for (int i = 0; i < size; i++) {
                values[i] = (float) valuesNode.get(i).asDouble();
            }

            return values;
        } catch (Exception e) {
            logger.error("Failed to parse Gemini embedding JSON response: {}", e.getMessage());
            return null;
        }
    }
}
