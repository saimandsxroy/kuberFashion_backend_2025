package com.kuberfashion.backend.controller;

import com.kuberfashion.backend.service.ProductVectorService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class AdminProductEmbeddingControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ProductVectorService productVectorService;

    @Test
    @DisplayName("1. GET /api/admin/products/embeddings/status requires ADMIN role")
    void testStatusRequiresAdminRole() throws Exception {
        mockMvc.perform(get("/api/admin/products/embeddings/status"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(roles = "USER")
    @DisplayName("2. Normal USER role cannot access admin embedding endpoints (403 Forbidden)")
    void testUserRoleForbidden() throws Exception {
        mockMvc.perform(get("/api/admin/products/embeddings/status"))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/admin/products/embeddings/backfill"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("3. ADMIN can query embedding status with gemini-embedding-2 configured")
    void testAdminCanGetEmbeddingStatus() throws Exception {
        mockMvc.perform(get("/api/admin/products/embeddings/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.embeddingModel").value("gemini-embedding-2"))
                .andExpect(jsonPath("$.data.embeddingDimension").value(768));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("4. ADMIN can execute safe backfill with model tracking")
    void testAdminCanExecuteBackfill() throws Exception {
        mockMvc.perform(post("/api/admin/products/embeddings/backfill")
                        .param("overwrite", "false")
                        .param("limit", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.configuredModel").value("gemini-embedding-2"));
    }
}
