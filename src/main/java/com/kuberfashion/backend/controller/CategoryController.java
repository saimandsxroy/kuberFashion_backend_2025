package com.kuberfashion.backend.controller;

import com.kuberfashion.backend.dto.ApiResponse;
import com.kuberfashion.backend.dto.CategoryResponseDto;
import com.kuberfashion.backend.service.CategoryService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/categories")
public class CategoryController {
    
    @Autowired
    private CategoryService categoryService;
    
    @GetMapping
    public ResponseEntity<ApiResponse<List<CategoryResponseDto>>> getAllCategories() {
        List<CategoryResponseDto> categories = categoryService.getAllCategories();
        return ResponseEntity.ok(ApiResponse.success("Categories retrieved successfully", categories));
    }
    
    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<CategoryResponseDto>> getCategoryById(@PathVariable Long id) {
        CategoryResponseDto category = categoryService.getCategoryById(id);
        return ResponseEntity.ok(ApiResponse.success("Category retrieved successfully", category));
    }
    
    @GetMapping("/slug/{slug}")
    public ResponseEntity<ApiResponse<CategoryResponseDto>> getCategoryBySlug(@PathVariable String slug) {
        CategoryResponseDto category = categoryService.getCategoryBySlug(slug);
        return ResponseEntity.ok(ApiResponse.success("Category retrieved successfully", category));
    }
    
    @GetMapping("/with-products")
    public ResponseEntity<ApiResponse<List<CategoryResponseDto>>> getCategoriesWithProducts() {
        List<CategoryResponseDto> categories = categoryService.getCategoriesWithProducts();
        return ResponseEntity.ok(ApiResponse.success("Categories with products retrieved successfully", categories));
    }
    
    @GetMapping("/check-slug")
    public ResponseEntity<ApiResponse<Boolean>> checkSlugAvailability(@RequestParam String slug) {
        boolean isAvailable = !categoryService.existsBySlug(slug);
        return ResponseEntity.ok(ApiResponse.success("Slug availability checked", isAvailable));
    }
    
    @GetMapping("/check-name")
    public ResponseEntity<ApiResponse<Boolean>> checkNameAvailability(@RequestParam String name) {
        boolean isAvailable = !categoryService.existsByName(name);
        return ResponseEntity.ok(ApiResponse.success("Name availability checked", isAvailable));
    }
}
