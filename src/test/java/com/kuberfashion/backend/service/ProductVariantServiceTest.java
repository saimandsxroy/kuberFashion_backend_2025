package com.kuberfashion.backend.service;

import com.kuberfashion.backend.dto.ProductVariantConfigDto;
import com.kuberfashion.backend.dto.ProductVariantDto;
import com.kuberfashion.backend.entity.Product;
import com.kuberfashion.backend.entity.ProductVariant;
import com.kuberfashion.backend.exception.ResourceNotFoundException;
import com.kuberfashion.backend.repository.ProductRepository;
import com.kuberfashion.backend.repository.ProductVariantRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ProductVariantServiceTest {

    @Mock
    private ProductRepository productRepository;

    @Mock
    private ProductVariantRepository productVariantRepository;

    @InjectMocks
    private ProductVariantService productVariantService;

    private Product testProduct;

    @BeforeEach
    void setUp() {
        testProduct = new Product();
        testProduct.setId(100L);
        testProduct.setName("Silk Kurta");
        testProduct.setPrice(BigDecimal.valueOf(1999.00));
        testProduct.setStockQuantity(50);
        testProduct.setInventoryTrackingMode(Product.InventoryTrackingMode.LEGACY);
    }

    @Test
    @DisplayName("Successfully configure sellable variants with normalization")
    void testConfigureVariants_Success() {
        when(productRepository.findById(100L)).thenReturn(Optional.of(testProduct));
        when(productVariantRepository.findByProductIdAndSizeAndColor(eq(100L), anyString(), anyString()))
                .thenReturn(Optional.empty());
        when(productVariantRepository.save(any(ProductVariant.class)))
                .thenAnswer(inv -> {
                    ProductVariant v = inv.getArgument(0);
                    v.setId(999L);
                    return v;
                });

        ProductVariantConfigDto c1 = new ProductVariantConfigDto(" m ", " navy blue ", 20, "SKU-M-BLU", true);
        ProductVariantConfigDto c2 = new ProductVariantConfigDto("l", "Ruby Red", 15, "SKU-L-RED", true);

        List<ProductVariantDto> results = productVariantService.configureVariants(100L, List.of(c1, c2));

        assertThat(results).hasSize(2);
        assertThat(results.get(0).getSize()).isEqualTo("M");
        assertThat(results.get(0).getColor()).isEqualTo("NAVY BLUE");
        assertThat(results.get(0).getStockQuantity()).isEqualTo(20);
        assertThat(results.get(1).getSize()).isEqualTo("L");
        assertThat(results.get(1).getColor()).isEqualTo("RUBY RED");
        assertThat(results.get(1).getStockQuantity()).isEqualTo(15);
    }

    @Test
    @DisplayName("Rejects duplicate variants in same batch even with different casing or whitespace")
    void testConfigureVariants_RejectsDuplicates() {
        when(productRepository.findById(100L)).thenReturn(Optional.of(testProduct));

        ProductVariantConfigDto c1 = new ProductVariantConfigDto("M", "Red", 10, null, true);
        ProductVariantConfigDto c2 = new ProductVariantConfigDto("  m  ", "red", 5, null, true);

        assertThatThrownBy(() -> productVariantService.configureVariants(100L, List.of(c1, c2)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Duplicate variant in configuration");
    }

    @Test
    @DisplayName("Rejects empty variant configuration list")
    void testConfigureVariants_RejectsEmpty() {
        when(productRepository.findById(100L)).thenReturn(Optional.of(testProduct));

        assertThatThrownBy(() -> productVariantService.configureVariants(100L, List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cannot be empty");
    }

    @Test
    @DisplayName("Rejects negative stock in variant configuration")
    void testConfigureVariants_RejectsNegativeStock() {
        when(productRepository.findById(100L)).thenReturn(Optional.of(testProduct));

        ProductVariantConfigDto c1 = new ProductVariantConfigDto("M", "Red", -5, null, true);

        assertThatThrownBy(() -> productVariantService.configureVariants(100L, List.of(c1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Stock quantity must be non-negative");
    }

    @Test
    @DisplayName("Rejects blank size or color")
    void testConfigureVariants_RejectsBlankSizeOrColor() {
        when(productRepository.findById(100L)).thenReturn(Optional.of(testProduct));

        ProductVariantConfigDto c1 = new ProductVariantConfigDto("   ", "Red", 5, null, true);
        assertThatThrownBy(() -> productVariantService.configureVariants(100L, List.of(c1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Variant size cannot be blank");

        ProductVariantConfigDto c2 = new ProductVariantConfigDto("M", "  ", 5, null, true);
        assertThatThrownBy(() -> productVariantService.configureVariants(100L, List.of(c2)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Variant color cannot be blank");
    }

    @Test
    @DisplayName("Update stock quantity for variant with pessimistic lock, validating product ownership")
    void testUpdateVariantStock_Success() {
        ProductVariant variant = new ProductVariant(testProduct, "M", "Black", 10, "SKU-1", true);
        variant.setId(55L);
        // testProduct already has id = 100L; its product field on the variant is set by the constructor

        when(productVariantRepository.findByIdWithPessimisticLock(55L)).thenReturn(Optional.of(variant));
        when(productVariantRepository.save(any(ProductVariant.class))).thenAnswer(inv -> inv.getArgument(0));

        ProductVariantDto result = productVariantService.updateVariantStock(100L, 55L, 25);

        assertThat(result.getStockQuantity()).isEqualTo(25);
        verify(productVariantRepository).save(variant);
    }

    @Test
    @DisplayName("Rejects negative stock on single variant update")
    void testUpdateVariantStock_RejectsNegative() {
        assertThatThrownBy(() -> productVariantService.updateVariantStock(100L, 55L, -1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Stock quantity cannot be negative");

        verifyNoInteractions(productVariantRepository);
    }

    @Test
    @DisplayName("Rejects variant stock update when variant belongs to a different product (IDOR protection)")
    void testUpdateVariantStock_RejectsWrongProductId() {
        // Create a different product (id = 999) and assign it as the variant's owner
        Product otherProduct = new Product();
        otherProduct.setId(999L);
        otherProduct.setName("Other Product");
        otherProduct.setSlug("other-product");
        otherProduct.setPrice(BigDecimal.valueOf(500));
        otherProduct.setInventoryTrackingMode(Product.InventoryTrackingMode.LEGACY);

        ProductVariant variant = new ProductVariant(otherProduct, "M", "Black", 10, "SKU-OTHER", true);
        variant.setId(55L);

        when(productVariantRepository.findByIdWithPessimisticLock(55L)).thenReturn(Optional.of(variant));

        // Caller supplies productId=100 but variant actually belongs to product 999
        assertThatThrownBy(() -> productVariantService.updateVariantStock(100L, 55L, 25))
                .isInstanceOf(com.kuberfashion.backend.exception.ResourceNotFoundException.class)
                .hasMessageContaining("does not belong to product id 100");

        verify(productVariantRepository, never()).save(any());
    }

    @Test
    @DisplayName("Switching to VARIANT tracking fails when product has no active variants")
    void testUpdateTrackingMode_ToVariant_FailsWhenNoVariants() {
        when(productRepository.findById(100L)).thenReturn(Optional.of(testProduct));
        when(productVariantRepository.countByProductIdAndActiveTrue(100L)).thenReturn(0L);

        assertThatThrownBy(() -> productVariantService.updateTrackingMode(100L, Product.InventoryTrackingMode.VARIANT))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Cannot switch product to VARIANT tracking: no active sellable variants configured");

        assertThat(testProduct.getInventoryTrackingMode()).isEqualTo(Product.InventoryTrackingMode.LEGACY);
        verify(productRepository, never()).save(any());
    }

    @Test
    @DisplayName("Switching to VARIANT tracking succeeds when active variants exist")
    void testUpdateTrackingMode_ToVariant_Succeeds() {
        when(productRepository.findById(100L)).thenReturn(Optional.of(testProduct));
        when(productVariantRepository.countByProductIdAndActiveTrue(100L)).thenReturn(3L);
        when(productRepository.save(any(Product.class))).thenAnswer(inv -> inv.getArgument(0));

        Product.InventoryTrackingMode result = productVariantService.updateTrackingMode(100L, Product.InventoryTrackingMode.VARIANT);

        assertThat(result).isEqualTo(Product.InventoryTrackingMode.VARIANT);
        assertThat(testProduct.getInventoryTrackingMode()).isEqualTo(Product.InventoryTrackingMode.VARIANT);
        verify(productRepository).save(testProduct);
    }

    @Test
    @DisplayName("Switching to LEGACY tracking is permitted")
    void testUpdateTrackingMode_ToLegacy_Allowed() {
        testProduct.setInventoryTrackingMode(Product.InventoryTrackingMode.VARIANT);
        when(productRepository.findById(100L)).thenReturn(Optional.of(testProduct));
        when(productRepository.save(any(Product.class))).thenAnswer(inv -> inv.getArgument(0));

        Product.InventoryTrackingMode result = productVariantService.updateTrackingMode(100L, Product.InventoryTrackingMode.LEGACY);

        assertThat(result).isEqualTo(Product.InventoryTrackingMode.LEGACY);
        verify(productRepository).save(testProduct);
    }
}
