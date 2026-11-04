package com.kuberfashion.backend.controller;

import com.kuberfashion.backend.dto.AiSearchRequestDto;
import com.kuberfashion.backend.dto.AiSearchResponseDto;
import com.kuberfashion.backend.dto.ApiResponse;
import com.kuberfashion.backend.service.ProductService;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Controller dedicated to AI-driven natural language search and semantic vector discovery.
 * Maps endpoints under /api/ai for unified AI feature routing.
 */
@RestController
@RequestMapping("/api/ai")
public class AiSearchController {

    private final ProductService productService;

    @Autowired
    public AiSearchController(ProductService productService) {
        this.productService = productService;
    }

    @PostMapping("/search")
    public ResponseEntity<ApiResponse<AiSearchResponseDto>> search(
            @Valid @RequestBody(required = false) AiSearchRequestDto request,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "48") int size) {
        AiSearchRequestDto searchRequest = request != null ? request : new AiSearchRequestDto("");
        Pageable pageable = PageRequest.of(page, size);
        AiSearchResponseDto response = productService.searchWithAi(searchRequest, pageable);
        return ResponseEntity.ok(ApiResponse.success("AI search completed successfully", response));
    }

    @GetMapping("/search")
    public ResponseEntity<ApiResponse<AiSearchResponseDto>> searchGet(
            @RequestParam(value = "q", defaultValue = "") String query,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "48") int size) {
        AiSearchRequestDto request = new AiSearchRequestDto(query);
        Pageable pageable = PageRequest.of(page, size);
        AiSearchResponseDto response = productService.searchWithAi(request, pageable);
        return ResponseEntity.ok(ApiResponse.success("AI search completed successfully", response));
    }
}
