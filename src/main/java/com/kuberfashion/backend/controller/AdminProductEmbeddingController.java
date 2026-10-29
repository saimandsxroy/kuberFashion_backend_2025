package com.kuberfashion.backend.controller;

import com.kuberfashion.backend.dto.ApiResponse;
import com.kuberfashion.backend.service.ProductVectorService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Controller for administrative monitoring and backfilling of product vector embeddings.
 */
@RestController
@RequestMapping("/api/admin/products/embeddings")
@CrossOrigin(origins = { "http://localhost:5173", "http://127.0.0.1:5173", "https://kuberfashions.in", "https://www.kuberfashions.in" })
public class AdminProductEmbeddingController {

    private final ProductVectorService productVectorService;

    @Autowired
    public AdminProductEmbeddingController(ProductVectorService productVectorService) {
        this.productVectorService = productVectorService;
    }

    @GetMapping("/status")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getEmbeddingStatus() {
        Map<String, Object> stats = productVectorService.getEmbeddingStatus();
        return ResponseEntity.ok(ApiResponse.success("Product embedding status retrieved", stats));
    }

    @PostMapping("/backfill")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<Map<String, Object>>> backfillEmbeddings(
            @RequestParam(value = "overwrite", defaultValue = "false") boolean overwrite,
            @RequestParam(value = "limit", defaultValue = "100") int limit) {
        Map<String, Object> report = productVectorService.backfillEmbeddings(overwrite, limit);
        return ResponseEntity.ok(ApiResponse.success("Embedding backfill executed", report));
    }

    @PostMapping("/{productId}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<String>> updateSingleProductEmbedding(@PathVariable Long productId) {
        boolean ok = productVectorService.updateProductEmbeddingSafely(productId);
        if (ok) {
            return ResponseEntity.ok(ApiResponse.success("Product embedding updated successfully for ID " + productId, "OK"));
        } else {
            return ResponseEntity.badRequest().body(ApiResponse.error("Failed to generate or persist embedding for ID " + productId));
        }
    }
}
