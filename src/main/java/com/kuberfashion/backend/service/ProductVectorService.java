package com.kuberfashion.backend.service;

import com.kuberfashion.backend.entity.Product;
import com.kuberfashion.backend.repository.ProductRepository;
import com.kuberfashion.backend.service.ai.GeminiEmbeddingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Service managing pgvector embeddings, cosine similarity queries, and catalog backfills.
 * Operates without holding Hibernate transactions open during remote AI calls.
 */
@Service
public class ProductVectorService {

    private static final Logger logger = LoggerFactory.getLogger(ProductVectorService.class);

    private final JdbcTemplate jdbcTemplate;
    private final ProductRepository productRepository;
    private final GeminiEmbeddingService embeddingService;
    private final boolean vectorSearchEnabled;
    private final double defaultSimilarityThreshold;

    @Autowired
    public ProductVectorService(
            JdbcTemplate jdbcTemplate,
            ProductRepository productRepository,
            GeminiEmbeddingService embeddingService,
            @Value("${ai.search.vector-enabled:true}") boolean vectorSearchEnabled,
            @Value("${ai.search.vector-similarity-threshold:0.40}") double defaultSimilarityThreshold) {
        this.jdbcTemplate = jdbcTemplate;
        this.productRepository = productRepository;
        this.embeddingService = embeddingService;
        this.vectorSearchEnabled = vectorSearchEnabled;
        this.defaultSimilarityThreshold = defaultSimilarityThreshold > 0 ? defaultSimilarityThreshold : 0.40;
    }

    /**
     * Checks whether the PostgreSQL 'vector' extension is enabled in the active database.
     */
    public boolean isPgVectorAvailable() {
        try {
            Integer count = jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM pg_extension WHERE extname = 'vector'",
                    Integer.class
            );
            return count != null && count > 0;
        } catch (Exception e) {
            logger.debug("pgvector check returned false or failed: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Checks if vector search is active (configured enabled AND pgvector available).
     */
    public boolean isVectorSearchActive() {
        return vectorSearchEnabled && isPgVectorAvailable();
    }

    /**
     * Builds verified catalog text for embedding from product attributes without hallucination.
     */
    public String buildEmbeddingText(Product product) {
        if (product == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();

        if (product.getName() != null && !product.getName().isBlank()) {
            sb.append("Product: ").append(product.getName().trim()).append("\n");
        }
        if (product.getCategory() != null && product.getCategory().getName() != null) {
            sb.append("Category: ").append(product.getCategory().getName().trim()).append("\n");
        }
        if (product.getDescription() != null && !product.getDescription().isBlank()) {
            sb.append("Description: ").append(product.getDescription().trim()).append("\n");
        }
        if (product.getColors() != null && !product.getColors().isEmpty()) {
            sb.append("Colors: ").append(String.join(", ", product.getColors())).append("\n");
        }
        if (product.getSizes() != null && !product.getSizes().isEmpty()) {
            sb.append("Sizes: ").append(String.join(", ", product.getSizes())).append("\n");
        }
        if (product.getPrice() != null) {
            sb.append("Price: ₹").append(product.getPrice()).append("\n");
        }

        return sb.toString().trim();
    }

    /**
     * Safely generates and stores an embedding for a product.
     * Executes outside existing transactions so failure never aborts product creation/updates.
     */
    public boolean updateProductEmbeddingSafely(Long productId) {
        if (productId == null || !isPgVectorAvailable()) {
            return false;
        }

        try {
            Optional<Product> productOpt = productRepository.findById(productId);
            if (productOpt.isEmpty()) {
                logger.warn("Product ID {} not found for embedding update", productId);
                return false;
            }

            Product product = productOpt.get();
            String embeddingText = buildEmbeddingText(product);
            if (embeddingText.isBlank()) {
                logger.warn("Embedding text empty for product ID {}", productId);
                return false;
            }

            float[] embedding = embeddingService.generateEmbedding(embeddingText);
            if (embedding == null || embedding.length == 0) {
                logger.warn("Embedding generation returned null for product ID {}", productId);
                return false;
            }

            String vectorString = formatVectorLiteral(embedding);
            String activeModel = embeddingService.getModel();
            int rowsUpdated = jdbcTemplate.update(
                    "UPDATE products SET embedding = CAST(? AS vector), embedding_model = ? WHERE id = ?",
                    vectorString,
                    activeModel,
                    productId
            );

            logger.info("Successfully updated vector embedding for product ID: {} ({} dims, model: {})",
                    productId, embedding.length, activeModel);
            return rowsUpdated > 0;
        } catch (Exception e) {
            logger.error("Failed to safely update embedding for product ID {}: {}", productId, e.getMessage());
            return false;
        }
    }

    /**
     * Queries PostgreSQL for candidate products using cosine distance (<=>).
     * Enforces model compatibility: only evaluates candidates generated with the exact same active embedding model.
     * Returns a linked map of {productId -> cosineSimilarityScore} sorted descending by similarity.
     */
    public Map<Long, Double> findVectorCandidates(String queryText, Double similarityThreshold, int limit) {
        Map<Long, Double> results = new LinkedHashMap<>();

        if (queryText == null || queryText.trim().isEmpty() || !isVectorSearchActive()) {
            return results;
        }

        float[] queryEmbedding = embeddingService.generateEmbedding(queryText.trim());
        if (queryEmbedding == null || queryEmbedding.length == 0) {
            logger.debug("Query embedding generation returned null; falling back to relational keyword search.");
            return results;
        }

        double threshold = similarityThreshold != null && similarityThreshold > 0
                ? similarityThreshold
                : defaultSimilarityThreshold;

        int effectiveLimit = limit > 0 ? limit : 48;
        String vectorLiteral = formatVectorLiteral(queryEmbedding);
        String activeModel = embeddingService.getModel();

        try {
            // Strictly match embedding_model to ensure mathematical vector space compatibility
            String sql = "SELECT id, (1.0 - (embedding <=> CAST(? AS vector))) AS similarity " +
                         "FROM products " +
                         "WHERE embedding IS NOT NULL " +
                         "  AND embedding_model = ? " +
                         "  AND (1.0 - (embedding <=> CAST(? AS vector))) >= ? " +
                         "ORDER BY embedding <=> CAST(? AS vector) ASC " +
                         "LIMIT ?";

            jdbcTemplate.query(
                    sql,
                    ps -> {
                        ps.setString(1, vectorLiteral);
                        ps.setString(2, activeModel);
                        ps.setString(3, vectorLiteral);
                        ps.setDouble(4, threshold);
                        ps.setString(5, vectorLiteral);
                        ps.setInt(6, effectiveLimit);
                    },
                    rs -> {
                        long id = rs.getLong("id");
                        double similarity = rs.getDouble("similarity");
                        results.put(id, similarity);
                    }
            );

            logger.info("pgvector cosine search for '{}' (model: {}) found {} candidates above threshold {}",
                    queryText, activeModel, results.size(), threshold);
        } catch (Exception e) {
            logger.error("Error executing pgvector cosine distance query: {}", e.getMessage());
        }

        return results;
    }

    /**
     * Finds semantically similar products using the source product's already-stored embedding as
     * a bound query parameter. Zero remote AI API calls — uses only embeddings already persisted in PostgreSQL.
     *
     * <p>Algorithm:
     * <ol>
     *   <li>Fetch the source product's embedding vector string and embedding_model from PostgreSQL.</li>
     *   <li>If no embedding or pgvector is unavailable, return empty map (caller handles fallback).</li>
     *   <li>Execute cosine distance query using the fetched vector, enforcing model equality and
     *       excluding the source product ID.</li>
     * </ol>
     *
     * @param productId the source product whose neighbors to find
     * @param limit     maximum number of similar products to return
     * @return LinkedHashMap of {candidateProductId -&gt; cosineSimilarityScore} sorted descending
     */
    public Map<Long, Double> findSimilarProductsByStoredEmbedding(Long productId, int limit) {
        Map<Long, Double> results = new LinkedHashMap<>();
        if (productId == null || !isVectorSearchActive()) {
            return results;
        }

        try {
            // Step 1: Fetch stored embedding string and model for source product
            List<Map<String, Object>> sourceRows = jdbcTemplate.queryForList(
                    "SELECT embedding::text AS emb, embedding_model FROM products WHERE id = ? AND embedding IS NOT NULL",
                    productId
            );
            if (sourceRows.isEmpty() || sourceRows.get(0).get("emb") == null) {
                logger.debug("No stored embedding for product ID {} — vector similarity skipped", productId);
                return results;
            }

            String sourceVector = (String) sourceRows.get(0).get("emb");
            String sourceModel  = (String) sourceRows.get(0).get("embedding_model");

            if (sourceModel == null || sourceModel.isBlank()) {
                logger.debug("Source product {} has no embedding_model set — skipping", productId);
                return results;
            }

            int effectiveLimit = limit > 0 ? limit : 8;

            // Step 2: Query nearest neighbors using the stored vector as a parameter, model-compatible only
            String sql = "SELECT p.id, (1.0 - (p.embedding <=> CAST(? AS vector))) AS similarity " +
                         "FROM products p " +
                         "WHERE p.id != ? " +
                         "  AND p.is_active = true " +
                         "  AND p.embedding IS NOT NULL " +
                         "  AND p.embedding_model = ? " +
                         "ORDER BY p.embedding <=> CAST(? AS vector) ASC " +
                         "LIMIT ?";

            jdbcTemplate.query(
                    sql,
                    ps -> {
                        ps.setString(1, sourceVector);
                        ps.setLong(2, productId);
                        ps.setString(3, sourceModel);
                        ps.setString(4, sourceVector);
                        ps.setInt(5, effectiveLimit);
                    },
                    rs -> {
                        long id = rs.getLong("id");
                        double similarity = rs.getDouble("similarity");
                        results.put(id, similarity);
                    }
            );

            logger.info("Stored-embedding similarity for product {} (model: {}): {} candidates found",
                    productId, sourceModel, results.size());
        } catch (Exception e) {
            logger.error("Error executing stored-embedding similarity query for product {}: {}",
                    productId, e.getMessage());
        }

        return results;
    }

    /**
     * Backfills embeddings for products lacking them or containing outdated model vectors.
     * Safe and repeatable: does not modify prices, descriptions, stocks, or orders.
     */
    public Map<String, Object> backfillEmbeddings(boolean overwriteExisting, int maxBatchSize) {
        Map<String, Object> report = new LinkedHashMap<>();
        String activeModel = embeddingService.getModel();
        report.put("configuredModel", activeModel);

        if (!isPgVectorAvailable()) {
            report.put("success", false);
            report.put("message", "pgvector extension is not available in database");
            return report;
        }

        try {
            String sql;
            if (overwriteExisting) {
                sql = "SELECT id FROM products ORDER BY id ASC";
            } else {
                // Target products missing an embedding OR having an outdated/mismatched model
                sql = "SELECT id FROM products WHERE embedding IS NULL OR embedding_model IS NULL OR embedding_model != ? ORDER BY id ASC";
            }

            if (maxBatchSize > 0) {
                sql += " LIMIT " + maxBatchSize;
            }

            List<Long> productIds = overwriteExisting
                    ? jdbcTemplate.queryForList(sql, Long.class)
                    : jdbcTemplate.queryForList(sql, Long.class, activeModel);

            int total = productIds.size();
            int successCount = 0;
            int failCount = 0;

            for (Long id : productIds) {
                boolean ok = updateProductEmbeddingSafely(id);
                if (ok) {
                    successCount++;
                } else {
                    failCount++;
                }
            }

            report.put("success", true);
            report.put("configuredModel", activeModel);
            report.put("totalTargeted", total);
            report.put("successful", successCount);
            report.put("failed", failCount);
            report.put("overwrite", overwriteExisting);
            return report;
        } catch (Exception e) {
            logger.error("Backfill execution error: {}", e.getMessage());
            report.put("success", false);
            report.put("error", e.getMessage());
            return report;
        }
    }

    /**
     * Returns catalog embedding status metrics, detailing vector coverage and model compatibility.
     */
    public Map<String, Object> getEmbeddingStatus() {
        Map<String, Object> stats = new LinkedHashMap<>();
        boolean pgvectorOk = isPgVectorAvailable();
        String activeModel = embeddingService.getModel();

        stats.put("pgvectorAvailable", pgvectorOk);
        stats.put("vectorSearchEnabled", vectorSearchEnabled);
        stats.put("embeddingModel", activeModel);
        stats.put("embeddingDimension", embeddingService.getDimension());
        stats.put("geminiApiConfigured", embeddingService.isAvailable());

        if (pgvectorOk) {
            try {
                Long total = jdbcTemplate.queryForObject("SELECT count(*) FROM products", Long.class);
                Long embedded = jdbcTemplate.queryForObject("SELECT count(*) FROM products WHERE embedding IS NOT NULL", Long.class);
                Long configuredModelCount = jdbcTemplate.queryForObject(
                        "SELECT count(*) FROM products WHERE embedding IS NOT NULL AND embedding_model = ?",
                        Long.class, activeModel);
                Long outdatedModelCount = jdbcTemplate.queryForObject(
                        "SELECT count(*) FROM products WHERE embedding IS NOT NULL AND (embedding_model IS NULL OR embedding_model != ?)",
                        Long.class, activeModel);
                Long pending = jdbcTemplate.queryForObject(
                        "SELECT count(*) FROM products WHERE embedding IS NULL OR embedding_model IS NULL OR embedding_model != ?",
                        Long.class, activeModel);

                Integer indexCount = jdbcTemplate.queryForObject(
                        "SELECT count(*) FROM pg_indexes WHERE tablename = 'products' AND indexname = 'idx_products_embedding_hnsw'",
                        Integer.class
                );

                stats.put("totalProducts", total != null ? total : 0);
                stats.put("embeddedProducts", embedded != null ? embedded : 0);
                stats.put("configuredModelEmbeddings", configuredModelCount != null ? configuredModelCount : 0);
                stats.put("outdatedModelEmbeddings", outdatedModelCount != null ? outdatedModelCount : 0);
                stats.put("pendingEmbeddings", pending != null ? pending : 0);
                stats.put("hnswIndexPresent", indexCount != null && indexCount > 0);
            } catch (Exception e) {
                stats.put("statsQueryError", e.getMessage());
            }
        }

        return stats;
    }

    /**
     * Converts a float array to standard PostgreSQL vector literal format: '[0.1,0.2,...]'
     */
    public static String formatVectorLiteral(float[] vector) {
        if (vector == null || vector.length == 0) {
            return "[]";
        }
        StringBuilder sb = new StringBuilder(vector.length * 8 + 2);
        sb.append('[');
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(vector[i]);
        }
        sb.append(']');
        return sb.toString();
    }
}
