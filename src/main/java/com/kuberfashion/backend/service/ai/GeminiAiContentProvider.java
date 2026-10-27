package com.kuberfashion.backend.service.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kuberfashion.backend.dto.AiGenerationRequestDto;
import com.kuberfashion.backend.dto.AiGenerationResponseDto;
import com.kuberfashion.backend.exception.AiServiceException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.util.*;

/**
 * Provider that integrates with Google's Gemini generative AI REST API
 * to generate rich product descriptions, tags, and catalog metadata.
 */
@Component
public class GeminiAiContentProvider implements AiContentProvider {

    private static final Logger logger = LoggerFactory.getLogger(GeminiAiContentProvider.class);

    private final String apiKey;
    private final String model;
    private final int timeoutSeconds;
    private final boolean fallbackEnabled;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final TemplateFallbackAiContentProvider fallbackProvider;

    @Autowired
    public GeminiAiContentProvider(
            @Value("${ai.gemini.api-key:}") String apiKey,
            @Value("${ai.gemini.model:gemini-1.5-flash}") String model,
            @Value("${ai.gemini.timeout-seconds:15}") int timeoutSeconds,
            @Value("${ai.gemini.fallback-enabled:true}") boolean fallbackEnabled,
            ObjectMapper objectMapper,
            TemplateFallbackAiContentProvider fallbackProvider) {
        this.apiKey = apiKey != null ? apiKey.trim() : "";
        this.model = model != null && !model.isBlank() ? model.trim() : "gemini-1.5-flash";
        this.timeoutSeconds = timeoutSeconds > 0 ? timeoutSeconds : 15;
        this.fallbackEnabled = fallbackEnabled;
        this.objectMapper = objectMapper;
        this.fallbackProvider = fallbackProvider;

        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(this.timeoutSeconds * 1000);
        factory.setReadTimeout(this.timeoutSeconds * 1000);
        this.restTemplate = new RestTemplate(factory);
    }

    // Constructor for testing with custom RestTemplate
    public GeminiAiContentProvider(
            String apiKey,
            String model,
            int timeoutSeconds,
            boolean fallbackEnabled,
            RestTemplate restTemplate,
            ObjectMapper objectMapper,
            TemplateFallbackAiContentProvider fallbackProvider) {
        this.apiKey = apiKey != null ? apiKey.trim() : "";
        this.model = model != null && !model.isBlank() ? model.trim() : "gemini-1.5-flash";
        this.timeoutSeconds = timeoutSeconds > 0 ? timeoutSeconds : 15;
        this.fallbackEnabled = fallbackEnabled;
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
        this.fallbackProvider = fallbackProvider;
    }

    @Override
    public boolean isAvailable() {
        return !apiKey.isBlank();
    }

    @Override
    public String getProviderName() {
        return "Google Gemini (" + model + ")";
    }

    @Override
    public AiGenerationResponseDto generateProductContent(AiGenerationRequestDto request) {
        if (!isAvailable()) {
            if (fallbackEnabled) {
                logger.info("Gemini API key not configured. Falling back to template generator.");
                return fallbackProvider.generateProductContent(request);
            }
            throw new AiServiceException(
                    "Gemini AI provider is not configured. Set GEMINI_API_KEY environment variable.",
                    HttpStatus.SERVICE_UNAVAILABLE
            );
        }

        try {
            return callGeminiApi(request);
        } catch (HttpClientErrorException.TooManyRequests e) {
            logger.warn("Gemini rate limit encountered: {}", e.getMessage());
            if (fallbackEnabled) {
                logger.info("Falling back to template provider due to rate limit.");
                return fallbackProvider.generateProductContent(request);
            }
            throw new AiServiceException("AI service is currently rate limited. Please try again shortly.",
                    HttpStatus.TOO_MANY_REQUESTS);
        } catch (ResourceAccessException e) {
            logger.error("Gemini API connection timed out or failed: {}", e.getMessage());
            if (fallbackEnabled) {
                logger.info("Falling back to template provider due to network timeout.");
                return fallbackProvider.generateProductContent(request);
            }
            throw new AiServiceException("AI service timed out. Please try again.", HttpStatus.GATEWAY_TIMEOUT);
        } catch (Exception e) {
            logger.error("Error communicating with Gemini API: {}", e.getMessage(), e);
            if (fallbackEnabled) {
                logger.info("Falling back to template provider after Gemini error.");
                return fallbackProvider.generateProductContent(request);
            }
            throw new AiServiceException("Failed to generate content via Gemini: " + e.getMessage(),
                    HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    private AiGenerationResponseDto callGeminiApi(AiGenerationRequestDto request) throws Exception {
        String url = String.format(
                "https://generativelanguage.googleapis.com/v1beta/models/%s:generateContent?key=%s",
                model, apiKey
        );

        String prompt = buildPrompt(request);

        Map<String, Object> part = new HashMap<>();
        part.put("text", prompt);

        Map<String, Object> content = new HashMap<>();
        content.put("parts", Collections.singletonList(part));

        Map<String, Object> generationConfig = new HashMap<>();
        generationConfig.put("responseMimeType", "application/json");
        generationConfig.put("temperature", 0.7);

        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("contents", Collections.singletonList(content));
        requestBody.put("generationConfig", generationConfig);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(requestBody, headers);

        ResponseEntity<String> response = restTemplate.postForEntity(url, entity, String.class);

        if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
            throw new AiServiceException("Gemini API returned status: " + response.getStatusCode());
        }

        return parseGeminiResponse(response.getBody(), request);
    }

    private String buildPrompt(AiGenerationRequestDto request) {
        StringBuilder sb = new StringBuilder();
        sb.append("You are an expert e-commerce catalog copywriter and SEO specialist for KuberFashion, a premium fashion brand.\n");
        sb.append("Generate high-converting, elegant product copy based on the following product hints:\n\n");
        sb.append("- Product Name: ").append(request.getProductName()).append("\n");
        if (request.getCategoryHint() != null && !request.getCategoryHint().isBlank()) {
            sb.append("- Category Hint: ").append(request.getCategoryHint()).append("\n");
        }
        if (request.getFabric() != null && !request.getFabric().isBlank()) {
            sb.append("- Fabric / Material: ").append(request.getFabric()).append("\n");
        }
        if (request.getSizes() != null && !request.getSizes().isEmpty()) {
            sb.append("- Sizes Available: ").append(String.join(", ", request.getSizes())).append("\n");
        }
        if (request.getColors() != null && !request.getColors().isEmpty()) {
            sb.append("- Colors Available: ").append(String.join(", ", request.getColors())).append("\n");
        }
        if (request.getIntendedUse() != null && !request.getIntendedUse().isBlank()) {
            sb.append("- Intended Use / Occasion: ").append(request.getIntendedUse()).append("\n");
        }
        if (request.getAdditionalNotes() != null && !request.getAdditionalNotes().isBlank()) {
            sb.append("- Additional Admin Notes: ").append(request.getAdditionalNotes()).append("\n");
        }

        sb.append("\nReturn ONLY valid JSON matching this exact structure with NO markdown formatting or wrappers:\n");
        sb.append("{\n");
        sb.append("  \"description\": \"(Comprehensive and compelling product description between 300 and 700 characters long. MUST NOT exceed 800 characters)\",\n");
        sb.append("  \"bulletPoints\": [\"(3 to 5 clear feature/benefit bullet points)\"],\n");
        sb.append("  \"searchTags\": [\"(5 to 10 relevant lowercase search keywords/tags)\"],\n");
        sb.append("  \"seoTitle\": \"(SEO title under 60 characters)\",\n");
        sb.append("  \"seoMetaDescription\": \"(SEO meta description under 155 characters)\",\n");
        sb.append("  \"suggestedCategorySlug\": \"(One of: mens-fashion, womens-fashion, kids-fashion, accessories, sports-active)\",\n");
        sb.append("  \"missingDetails\": [\"(List any important attributes missing from the input hints, e.g. fabric, wash care, sizing specifics)\"]\n");
        sb.append("}\n");

        return sb.toString();
    }

    private AiGenerationResponseDto parseGeminiResponse(String responseBody, AiGenerationRequestDto originalRequest) {
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            JsonNode candidates = root.path("candidates");
            if (!candidates.isArray() || candidates.isEmpty()) {
                throw new AiServiceException("No candidates returned from Gemini API");
            }

            JsonNode parts = candidates.get(0).path("content").path("parts");
            if (!parts.isArray() || parts.isEmpty()) {
                throw new AiServiceException("Empty content parts in Gemini API response");
            }

            String text = parts.get(0).path("text").asText();
            if (text == null || text.isBlank()) {
                throw new AiServiceException("Blank text in Gemini response");
            }

            // Strip markdown backticks if present
            String cleanJson = text.trim();
            if (cleanJson.startsWith("```json")) {
                cleanJson = cleanJson.substring(7);
            } else if (cleanJson.startsWith("```")) {
                cleanJson = cleanJson.substring(3);
            }
            if (cleanJson.endsWith("```")) {
                cleanJson = cleanJson.substring(0, cleanJson.length() - 3);
            }
            cleanJson = cleanJson.trim();

            AiGenerationResponseDto result = objectMapper.readValue(cleanJson, AiGenerationResponseDto.class);

            // Safety limit enforcement: Product entity column is length 1000
            if (result.getDescription() != null && result.getDescription().length() > 950) {
                result.setDescription(result.getDescription().substring(0, 947) + "...");
            }

            return result;
        } catch (Exception e) {
            logger.warn("Failed to parse Gemini JSON: {}. Using fallback provider.", e.getMessage());
            if (fallbackEnabled) {
                return fallbackProvider.generateProductContent(originalRequest);
            }
            throw new AiServiceException("Failed to parse AI response: " + e.getMessage());
        }
    }
}
