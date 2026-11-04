package com.kuberfashion.backend.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Map;

/**
 * Root and Healthz controller providing instant HTTP 200 responses
 * for cloud container health checks (Render, Koyeb, Kubernetes).
 */
@RestController
public class RootController {

    @RequestMapping(value = {"/", "/healthz"}, method = {RequestMethod.GET, RequestMethod.HEAD})
    public ResponseEntity<Map<String, String>> health() {
        return ResponseEntity.ok(Map.of(
                "status", "UP",
                "service", "KuberFashion API",
                "timestamp", Instant.now().toString()
        ));
    }
}

