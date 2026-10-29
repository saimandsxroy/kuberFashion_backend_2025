package com.kuberfashion.backend.service;

import com.kuberfashion.backend.entity.Category;
import com.kuberfashion.backend.entity.Product;
import com.kuberfashion.backend.repository.ProductRepository;
import com.kuberfashion.backend.service.ai.GeminiEmbeddingService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementSetter;
import org.springframework.jdbc.core.RowCallbackHandler;

import java.math.BigDecimal;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ProductVectorServiceTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

    @Mock
    private ProductRepository productRepository;

    @Mock
    private GeminiEmbeddingService embeddingService;

    @Test
    @DisplayName("1. buildEmbeddingText extracts strictly verified product data without inventing attributes")
    void testBuildEmbeddingText() {
        ProductVectorService service = new ProductVectorService(
                jdbcTemplate, productRepository, embeddingService, true, 0.40);

        Product product = new Product();
        product.setId(101L);
        product.setName("Rose Gold Banarasi Silk Saree");
        product.setDescription("Authentic Kanjeevaram pure silk with gold zari border");
        product.setPrice(new BigDecimal("3499.00"));
        product.setColors(Arrays.asList("PINK", "GOLD"));
        product.setSizes(Collections.singletonList("FREE"));

        Category category = new Category();
        category.setName("Women's Fashion");
        product.setCategory(category);

        String text = service.buildEmbeddingText(product);

        assertTrue(text.contains("Product: Rose Gold Banarasi Silk Saree"));
        assertTrue(text.contains("Category: Women's Fashion"));
        assertTrue(text.contains("Description: Authentic Kanjeevaram pure silk with gold zari border"));
        assertTrue(text.contains("Colors: PINK, GOLD"));
        assertTrue(text.contains("Sizes: FREE"));
        assertTrue(text.contains("Price: ₹3499.00"));
        assertFalse(text.contains("fabric: synthetic"), "Should not contain hallucinated or unverified fields");
    }

    @Test
    @DisplayName("2. formatVectorLiteral formats float arrays into PostgreSQL vector literal syntax")
    void testFormatVectorLiteral() {
        float[] vector = new float[]{0.15f, -0.42f, 0.99f};
        String literal = ProductVectorService.formatVectorLiteral(vector);

        assertEquals("[0.15,-0.42,0.99]", literal);
        assertEquals("[]", ProductVectorService.formatVectorLiteral(new float[0]));
        assertEquals("[]", ProductVectorService.formatVectorLiteral(null));
    }

    @Test
    @DisplayName("3. updateProductEmbeddingSafely stores vector and records embedding_model")
    void testUpdateProductEmbeddingSafelyRecordsModel() {
        when(jdbcTemplate.queryForObject(contains("pg_extension"), eq(Integer.class))).thenReturn(1);
        when(embeddingService.getModel()).thenReturn("gemini-embedding-2");

        Product product = new Product();
        product.setId(201L);
        product.setName("Emerald Green Silk Lehenga");
        when(productRepository.findById(201L)).thenReturn(Optional.of(product));

        // Case A: Embedding generation fails (returns null)
        when(embeddingService.generateEmbedding(anyString())).thenReturn(null);

        ProductVectorService service = new ProductVectorService(
                jdbcTemplate, productRepository, embeddingService, true, 0.40);

        boolean result = service.updateProductEmbeddingSafely(201L);
        assertFalse(result, "Should return false when embedding fails");
        verify(jdbcTemplate, never()).update(startsWith("UPDATE products"), any(), any(), any());

        // Case B: Embedding generation succeeds
        float[] sampleVec = new float[]{0.1f, 0.2f, 0.3f};
        when(embeddingService.generateEmbedding(anyString())).thenReturn(sampleVec);
        when(jdbcTemplate.update(startsWith("UPDATE products SET embedding"), anyString(), anyString(), eq(201L))).thenReturn(1);

        boolean successResult = service.updateProductEmbeddingSafely(201L);
        assertTrue(successResult);
        verify(jdbcTemplate, times(1)).update(
                contains("UPDATE products SET embedding = CAST(? AS vector), embedding_model = ? WHERE id = ?"),
                eq("[0.1,0.2,0.3]"),
                eq("gemini-embedding-2"),
                eq(201L)
        );
    }

    @Test
    @DisplayName("4. backfillEmbeddings targets missing records and mismatched model embeddings")
    void testBackfillBehaviorEnforcesModelMatch() {
        when(jdbcTemplate.queryForObject(contains("pg_extension"), eq(Integer.class))).thenReturn(1);
        when(embeddingService.getModel()).thenReturn("gemini-embedding-2");

        Product p1 = new Product();
        p1.setId(10L);
        p1.setName("Item 1");

        when(productRepository.findById(10L)).thenReturn(Optional.of(p1));
        when(embeddingService.generateEmbedding(anyString())).thenReturn(new float[]{0.5f});
        when(jdbcTemplate.update(anyString(), anyString(), anyString(), eq(10L))).thenReturn(1);

        // Targeted query without overwrite: checks missing OR mismatched model
        when(jdbcTemplate.queryForList(contains("embedding_model != ?"), eq(Long.class), eq("gemini-embedding-2")))
                .thenReturn(Collections.singletonList(10L));

        ProductVectorService service = new ProductVectorService(
                jdbcTemplate, productRepository, embeddingService, true, 0.40);

        Map<String, Object> report = service.backfillEmbeddings(false, 100);

        assertEquals(true, report.get("success"));
        assertEquals("gemini-embedding-2", report.get("configuredModel"));
        assertEquals(1, report.get("totalTargeted"));
        assertEquals(1, report.get("successful"));
        assertEquals(0, report.get("failed"));
    }

    @Test
    @DisplayName("5. findVectorCandidates enforces model compatibility in SQL query")
    void testFindVectorCandidatesEnforcesModelFilter() {
        when(jdbcTemplate.queryForObject(contains("pg_extension"), eq(Integer.class))).thenReturn(1);
        when(embeddingService.getModel()).thenReturn("gemini-embedding-2");
        when(embeddingService.generateEmbedding("summer dresses")).thenReturn(new float[]{0.2f, 0.4f});

        ProductVectorService service = new ProductVectorService(
                jdbcTemplate, productRepository, embeddingService, true, 0.40);

        Map<Long, Double> candidates = service.findVectorCandidates("summer dresses", 0.40, 10);
        assertNotNull(candidates);

        // Verify that SQL contains embedding_model = ? to ensure query vectors only match identical model vectors
        verify(jdbcTemplate, times(1)).query(
                contains("AND embedding_model = ?"),
                any(PreparedStatementSetter.class),
                any(RowCallbackHandler.class)
        );
    }

    @Test
    @DisplayName("6. findVectorCandidates falls back gracefully to empty map if service disabled")
    void testFindVectorCandidatesFallbackWhenDisabled() {
        ProductVectorService service = new ProductVectorService(
                jdbcTemplate, productRepository, embeddingService, false, 0.40);

        Map<Long, Double> candidates = service.findVectorCandidates("summer dresses", 0.40, 10);
        assertTrue(candidates.isEmpty(), "When vector search is disabled or unavailable, candidates map is safely empty");
        verifyNoInteractions(embeddingService);
    }
}
