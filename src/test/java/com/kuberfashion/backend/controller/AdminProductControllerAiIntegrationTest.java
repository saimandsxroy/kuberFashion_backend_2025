package com.kuberfashion.backend.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kuberfashion.backend.dto.AiGenerationRequestDto;
import com.kuberfashion.backend.entity.User;
import com.kuberfashion.backend.repository.UserRepository;
import com.kuberfashion.backend.security.JwtTokenProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.hamcrest.Matchers.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class AdminProductControllerAiIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private com.kuberfashion.backend.repository.ProductRepository productRepository;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    private String adminToken;
    private String customerToken;

    @BeforeEach
    void setUp() {
        // Create Admin user
        User admin = new User();
        admin.setFirstName("Admin");
        admin.setLastName("User");
        admin.setPhone(String.format("9%09d", Math.abs((System.nanoTime() + 10) % 1_000_000_000L)));
        admin.setEmail("admin-" + System.nanoTime() + "@test.com");
        admin.setPassword("$2a$10$dummy.bcrypt.hash.placeholder.for.tests.only");
        admin.setRole(User.Role.ADMIN);
        admin = userRepository.save(admin);
        adminToken = jwtTokenProvider.generateToken(
                new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                        admin, null, admin.getAuthorities()));

        // Create regular customer user
        User customer = new User();
        customer.setFirstName("Regular");
        customer.setLastName("Customer");
        customer.setPhone(String.format("9%09d", Math.abs((System.nanoTime() + 20) % 1_000_000_000L)));
        customer.setEmail("customer-" + System.nanoTime() + "@test.com");
        customer.setPassword("$2a$10$dummy.bcrypt.hash.placeholder.for.tests.only");
        customer.setRole(User.Role.USER);
        customer = userRepository.save(customer);
        customerToken = jwtTokenProvider.generateToken(
                new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                        customer, null, customer.getAuthorities()));
    }

    @Test
    @DisplayName("Unauthenticated request to /api/admin/products/ai-generate is rejected with 401 or 403")
    void shouldRejectUnauthenticatedRequest() throws Exception {
        AiGenerationRequestDto request = new AiGenerationRequestDto();
        request.setProductName("Silk Scarf");

        mockMvc.perform(post("/api/admin/products/ai-generate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Regular customer (ROLE_USER) is forbidden from calling /api/admin/products/ai-generate (403)")
    void shouldForbidCustomerRole() throws Exception {
        AiGenerationRequestDto request = new AiGenerationRequestDto();
        request.setProductName("Silk Scarf");

        mockMvc.perform(post("/api/admin/products/ai-generate")
                        .header("Authorization", "Bearer " + customerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Admin (ROLE_ADMIN) successfully generates draft catalog content without persisting to database")
    void shouldAllowAdminToGenerateContent() throws Exception {
        long countBefore = productRepository.count();

        AiGenerationRequestDto request = new AiGenerationRequestDto();
        request.setProductName("Classic Velvet Blazer");
        request.setCategoryHint("mens-fashion");
        request.setFabric("Premium Italian Velvet");
        request.setSizes(List.of("38", "40", "42"));
        request.setColors(List.of("Midnight Navy", "Black"));
        request.setIntendedUse("Formal Evening Receptions");

        mockMvc.perform(post("/api/admin/products/ai-generate")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("AI content generated successfully"))
                .andExpect(jsonPath("$.data.description").isNotEmpty())
                .andExpect(jsonPath("$.data.bulletPoints").isArray())
                .andExpect(jsonPath("$.data.searchTags").isArray())
                .andExpect(jsonPath("$.data.seoTitle").isNotEmpty())
                .andExpect(jsonPath("$.data.suggestedCategorySlug").value("mens-fashion"));

        // Verify zero database persistence occurred during AI generation
        long countAfter = productRepository.count();
        assertThat(countAfter).isEqualTo(countBefore);
    }

    @Test
    @DisplayName("Admin request with blank product name fails validation with 400 Bad Request")
    void shouldRejectBlankProductName() throws Exception {
        AiGenerationRequestDto request = new AiGenerationRequestDto();
        request.setProductName(""); // invalid

        mockMvc.perform(post("/api/admin/products/ai-generate")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }
}
