package com.kuberfashion.backend.controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.CacheManager;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/cache")
public class CacheController {

    @Autowired(required = false)
    private RedisTemplate<String, Object> redisTemplate;

    @Autowired
    private CacheManager cacheManager;

    @Value("${spring.cache.type:simple}")
    private String cacheType;

    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> checkCacheHealth() {
        Map<String, Object> response = new HashMap<>();
        
        response.put("cache_type", cacheType);
        response.put("cache_manager", cacheManager.getClass().getSimpleName());
        response.put("cache_names", cacheManager.getCacheNames());
        
        if ("redis".equals(cacheType) && redisTemplate != null) {
            try {
                // Test Redis connection
                redisTemplate.opsForValue().set("test:health", "OK");
                String testValue = (String) redisTemplate.opsForValue().get("test:health");
                redisTemplate.delete("test:health");
                
                response.put("redis_connection", "OK");
                response.put("test_write_read", testValue != null ? "OK" : "FAILED");
                
                return ResponseEntity.ok(response);
            } catch (Exception e) {
                response.put("redis_connection", "FAILED");
                response.put("error", e.getMessage());
                
                return ResponseEntity.status(500).body(response);
            }
        } else {
            response.put("cache_status", "Using simple in-memory cache");
            return ResponseEntity.ok(response);
        }
    }

    @PostMapping("/clear")
    public ResponseEntity<Map<String, String>> clearAllCaches() {
        Map<String, String> response = new HashMap<>();
        
        try {
            // Clear specific caches
            if (cacheManager.getCache("products") != null) {
                cacheManager.getCache("products").clear();
            }
            if (cacheManager.getCache("products_list") != null) {
                cacheManager.getCache("products_list").clear();
            }
            
            response.put("status", "SUCCESS");
            response.put("message", "All caches cleared");
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            response.put("status", "FAILED");
            response.put("error", e.getMessage());
            return ResponseEntity.status(500).body(response);
        }
    }

    @GetMapping("/test")
    public ResponseEntity<Map<String, Object>> testCache() {
        Map<String, Object> response = new HashMap<>();
        long startTime = System.currentTimeMillis();
        
        try {
            // This should trigger cache behavior
            response.put("cache_type", cacheType);
            response.put("timestamp", System.currentTimeMillis());
            response.put("response_time_ms", System.currentTimeMillis() - startTime);
            response.put("message", "Cache test endpoint - check server logs for cache behavior");
            
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            response.put("error", e.getMessage());
            return ResponseEntity.status(500).body(response);
        }
    }
}
