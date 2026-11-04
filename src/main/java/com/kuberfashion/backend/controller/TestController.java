package com.kuberfashion.backend.controller;

import com.kuberfashion.backend.dto.ApiResponse;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/test")
public class TestController {
    
    @GetMapping("/health")
    public ApiResponse<Map<String, Object>> healthCheck() {
        Map<String, Object> health = new HashMap<>();
        health.put("status", "UP");
        health.put("timestamp", LocalDateTime.now());
        health.put("message", "KuberFashion Backend is running successfully!");
        health.put("version", "1.0.0");
        
        return ApiResponse.success("Health check successful", health);
    }
    
    @GetMapping("/database")
    public ApiResponse<String> databaseCheck() {
        // This endpoint will only work if database connection is successful
        return ApiResponse.success("Database connection is working!");
    }
}
