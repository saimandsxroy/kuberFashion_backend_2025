package com.kuberfashion.backend.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kuberfashion.backend.dto.AiSearchRequestDto;
import com.kuberfashion.backend.entity.Category;
import com.kuberfashion.backend.entity.Product;
import com.kuberfashion.backend.entity.ProductVariant;
import com.kuberfashion.backend.repository.CategoryRepository;
import com.kuberfashion.backend.repository.ProductRepository;
import com.kuberfashion.backend.repository.ProductVariantRepository;
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

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class ProductSmartSearchIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private CategoryRepository categoryRepository;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private ProductVariantRepository productVariantRepository;

    private Category mensCategory;
    private Product kurta;
    private Product shirt;
    private Product denimVariant;

    @BeforeEach
    void setUp() {
        productVariantRepository.deleteAll();
        productRepository.deleteAll();

        // 1. Setup or fetch Category
        mensCategory = categoryRepository.findBySlug("mens-fashion").orElseGet(() -> {
            Category c = new Category();
            c.setName("Men's Fashion");
            c.setSlug("mens-fashion");
            c.setActive(true);
            return categoryRepository.save(c);
        });

        // 2. Legacy Product 1: Black Kurta (in stock, ₹1200)
        kurta = new Product();
        kurta.setName("Black Embroidered Kurta");
        kurta.setSlug("black-embroidered-kurta");
        kurta.setPrice(new BigDecimal("1200.00"));
        kurta.setCategory(mensCategory);
        kurta.setDescription("Traditional Indian black kurta for wedding and party wear.");
        kurta.setSizes(Arrays.asList("M", "L", "XL"));
        kurta.setColors(Arrays.asList("Black"));
        kurta.setInStock(true);
        kurta.setStockQuantity(10);
        kurta.setActive(true);
        kurta.setInventoryTrackingMode(Product.InventoryTrackingMode.LEGACY);
        kurta = productRepository.save(kurta);

        // 3. Legacy Product 2: White Shirt (in stock, ₹800)
        shirt = new Product();
        shirt.setName("White Casual Cotton Shirt");
        shirt.setSlug("white-casual-cotton-shirt");
        shirt.setPrice(new BigDecimal("800.00"));
        shirt.setCategory(mensCategory);
        shirt.setDescription("Breathable white cotton shirt for casual everyday styling.");
        shirt.setSizes(Arrays.asList("L", "XL"));
        shirt.setColors(Arrays.asList("White"));
        shirt.setInStock(true);
        shirt.setStockQuantity(5);
        shirt.setActive(true);
        shirt.setInventoryTrackingMode(Product.InventoryTrackingMode.LEGACY);
        shirt = productRepository.save(shirt);

        // 4. Variant-Tracked Product 3: Denim Jacket (₹2500)
        denimVariant = new Product();
        denimVariant.setName("Denim Vintage Jacket");
        denimVariant.setSlug("denim-vintage-jacket");
        denimVariant.setPrice(new BigDecimal("2500.00"));
        denimVariant.setCategory(mensCategory);
        denimVariant.setDescription("Vintage washed blue denim jacket with sturdy buttons.");
        denimVariant.setSizes(Arrays.asList("M", "L"));
        denimVariant.setColors(Arrays.asList("Blue", "Black"));
        denimVariant.setInStock(true);
        denimVariant.setActive(true);
        denimVariant.setInventoryTrackingMode(Product.InventoryTrackingMode.VARIANT);
        denimVariant = productRepository.save(denimVariant);

        // Variant A: Size M, Color Blue -> IN STOCK (5 units)
        ProductVariant varA = new ProductVariant();
        varA.setProduct(denimVariant);
        varA.setSize("M");
        varA.setColor("Blue");
        varA.setStockQuantity(5);
        varA.setSku("DENIM-M-BLU");
        varA.setActive(true);
        productVariantRepository.save(varA);

        // Variant B: Size L, Color Black -> OUT OF STOCK (0 units)
        ProductVariant varB = new ProductVariant();
        varB.setProduct(denimVariant);
        varB.setSize("L");
        varB.setColor("Black");
        varB.setStockQuantity(0);
        varB.setSku("DENIM-L-BLK");
        varB.setActive(true);
        productVariantRepository.save(varB);
    }

    @Test
    @DisplayName("1. Smart search endpoint is public (accessible without authentication token)")
    void testSmartSearchIsPublic() throws Exception {
        AiSearchRequestDto request = new AiSearchRequestDto("Black kurta under 1500");

        mockMvc.perform(post("/api/products/smart-search")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.products").isArray());
    }

    @Test
    @DisplayName("2. Natural-language search with price and color filters returns matching database records")
    void testSearchByPriceAndColor() throws Exception {
        AiSearchRequestDto request = new AiSearchRequestDto("Black kurta under 1500");

        mockMvc.perform(post("/api/products/smart-search")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.products", hasSize(1)))
                .andExpect(jsonPath("$.data.products[0].name").value("Black Embroidered Kurta"))
                .andExpect(jsonPath("$.data.products[0].price").value(1200.00));
    }

    @Test
    @DisplayName("3. Variant-level inventory awareness: returns variant product when requested (size, color) has positive stock")
    void testVariantInventoryAwarenessInStock() throws Exception {
        AiSearchRequestDto request = new AiSearchRequestDto("Blue denim jacket in size M");

        mockMvc.perform(post("/api/products/smart-search")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.products", hasSize(1)))
                .andExpect(jsonPath("$.data.products[0].name").value("Denim Vintage Jacket"));
    }

    @Test
    @DisplayName("4. Variant-level inventory awareness: excludes variant product when requested (size, color) is out of stock (stock=0)")
    void testVariantInventoryAwarenessOutOfStockExcluded() throws Exception {
        // Size L, Color Black was configured with stockQuantity = 0
        AiSearchRequestDto request = new AiSearchRequestDto("Black denim jacket in size L");

        mockMvc.perform(post("/api/products/smart-search")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.products", hasSize(0)));
    }

    @Test
    @DisplayName("5. GET /api/products/smart-search?q=... works identically for browser URL query links")
    void testGetSmartSearch() throws Exception {
        mockMvc.perform(get("/api/products/smart-search")
                        .param("q", "White shirt under 1000"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.products", hasSize(1)))
                .andExpect(jsonPath("$.data.products[0].name").value("White Casual Cotton Shirt"));
    }

    @Test
    @DisplayName("6. Customer manual chip overrides take precedence over parsed AI filters")
    void testManualChipOverrides() throws Exception {
        AiSearchRequestDto request = new AiSearchRequestDto("shirt");
        // User manually sets maxPrice chip to 500 (Shirt costs 800)
        request.setMaxPrice(new BigDecimal("500"));

        mockMvc.perform(post("/api/products/smart-search")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.products", hasSize(0)));
    }

    @Test
    @DisplayName("7. Non-matching query returns clean empty result list without crashing")
    void testEmptySearchResults() throws Exception {
        AiSearchRequestDto request = new AiSearchRequestDto("NonExistentItem999 under 100");

        mockMvc.perform(post("/api/products/smart-search")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.products", hasSize(0)))
                .andExpect(jsonPath("$.data.totalElements").value(0));
    }
}
