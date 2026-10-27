package com.kuberfashion.backend.service;

import com.kuberfashion.backend.dto.ProductVariantConfigDto;
import com.kuberfashion.backend.dto.ProductVariantDto;
import com.kuberfashion.backend.entity.Product;
import com.kuberfashion.backend.entity.ProductVariant;
import com.kuberfashion.backend.exception.ResourceNotFoundException;
import com.kuberfashion.backend.repository.ProductRepository;
import com.kuberfashion.backend.repository.ProductVariantRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Service managing variant-level inventory configuration and state transitions.
 */
@Service
@Transactional
public class ProductVariantService {

    private static final Logger logger = LoggerFactory.getLogger(ProductVariantService.class);

    private final ProductRepository productRepository;
    private final ProductVariantRepository productVariantRepository;

    @Autowired
    public ProductVariantService(
            ProductRepository productRepository,
            ProductVariantRepository productVariantRepository) {
        this.productRepository = productRepository;
        this.productVariantRepository = productVariantRepository;
    }

    /**
     * Retrieve all variants configured for a given product.
     */
    @Transactional(readOnly = true)
    public List<ProductVariantDto> getVariantsByProductId(Long productId) {
        return productVariantRepository.findByProductId(productId).stream()
                .map(ProductVariantDto::new)
                .collect(Collectors.toList());
    }

    /**
     * Configures sellable variants and their known stock counts for a product.
     * Validates inputs, normalizes size and color, and rejects duplicates.
     */
    public List<ProductVariantDto> configureVariants(Long productId, List<ProductVariantConfigDto> variantConfigs) {
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new ResourceNotFoundException("Product not found with id: " + productId));

        if (variantConfigs == null || variantConfigs.isEmpty()) {
            throw new IllegalArgumentException("Variant configurations list cannot be empty");
        }

        // Validate uniqueness within the incoming configuration list
        Set<String> seenVariantKeys = new HashSet<>();
        for (ProductVariantConfigDto config : variantConfigs) {
            if (config.getSize() == null || config.getSize().trim().isEmpty()) {
                throw new IllegalArgumentException("Variant size cannot be blank");
            }
            if (config.getColor() == null || config.getColor().trim().isEmpty()) {
                throw new IllegalArgumentException("Variant color cannot be blank");
            }
            if (config.getStockQuantity() == null || config.getStockQuantity() < 0) {
                throw new IllegalArgumentException("Stock quantity must be non-negative");
            }

            String normSize = ProductVariant.normalizeSize(config.getSize());
            String normColor = ProductVariant.normalizeColor(config.getColor());
            String key = normSize + ":::" + normColor;
            if (!seenVariantKeys.add(key)) {
                throw new IllegalArgumentException(String.format(
                        "Duplicate variant in configuration: size '%s', color '%s'", config.getSize(), config.getColor()));
            }
        }

        List<ProductVariant> savedVariants = new ArrayList<>();

        for (ProductVariantConfigDto config : variantConfigs) {
            String normSize = ProductVariant.normalizeSize(config.getSize());
            String normColor = ProductVariant.normalizeColor(config.getColor());

            // Look up existing variant or create a new one
            Optional<ProductVariant> existingOpt = productVariantRepository.findByProductIdAndSizeAndColor(
                    productId, normSize, normColor);

            ProductVariant variant;
            if (existingOpt.isPresent()) {
                variant = existingOpt.get();
                variant.setStockQuantity(config.getStockQuantity());
                if (config.getSku() != null && !config.getSku().isBlank()) {
                    variant.setSku(config.getSku().trim());
                }
                if (config.getActive() != null) {
                    variant.setActive(config.getActive());
                }
            } else {
                variant = new ProductVariant(
                        product,
                        normSize,
                        normColor,
                        config.getStockQuantity(),
                        config.getSku() != null && !config.getSku().isBlank()
                                ? config.getSku().trim()
                                : "SKU-" + productId + "-" + normSize + "-" + normColor
                );
                if (config.getActive() != null) {
                    variant.setActive(config.getActive());
                }
            }

            savedVariants.add(productVariantRepository.save(variant));
        }

        logger.info("Configured {} variants for product '{}' (id={})", savedVariants.size(), product.getName(), productId);
        return savedVariants.stream().map(ProductVariantDto::new).collect(Collectors.toList());
    }

    /**
     * Atomically update a single variant's available stock quantity.
     * Validates that the variant belongs to the specified product.
     */
    public ProductVariantDto updateVariantStock(Long productId, Long variantId, int newStock) {
        if (newStock < 0) {
            throw new IllegalArgumentException("Stock quantity cannot be negative");
        }

        ProductVariant variant = productVariantRepository.findByIdWithPessimisticLock(variantId)
                .orElseThrow(() -> new ResourceNotFoundException("Product variant not found with id: " + variantId));

        // ── OWNERSHIP VALIDATION ───────────────────────────────────────────────
        // Prevent an admin from updating a variant that belongs to a different product
        // by supplying a mismatched URL path combination.
        if (variant.getProduct() == null || !variant.getProduct().getId().equals(productId)) {
            throw new ResourceNotFoundException(
                    "Variant id " + variantId + " does not belong to product id " + productId);
        }

        variant.setStockQuantity(newStock);
        ProductVariant saved = productVariantRepository.save(variant);

        logger.info("Updated stock to {} for variant id={} (size={}, color={}, productId={})",
                newStock, variantId, variant.getSize(), variant.getColor(), productId);

        return new ProductVariantDto(saved);
    }

    /**
     * Safely switches the product's inventory tracking mode.
     * Enforces the critical rule: A product MUST NOT switch to VARIANT tracking
     * until its valid sellable variants and known stock quantities have been explicitly configured.
     */
    public Product.InventoryTrackingMode updateTrackingMode(Long productId, Product.InventoryTrackingMode targetMode) {
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new ResourceNotFoundException("Product not found with id: " + productId));

        if (targetMode == Product.InventoryTrackingMode.VARIANT) {
            long activeVariantCount = productVariantRepository.countByProductIdAndActiveTrue(productId);
            if (activeVariantCount == 0) {
                throw new IllegalStateException(
                        "Cannot switch product to VARIANT tracking: no active sellable variants configured. " +
                        "Configure variants and their known stock counts before enabling variant tracking.");
            }
        }

        product.setInventoryTrackingMode(targetMode);
        productRepository.save(product);

        logger.info("Switched product '{}' (id={}) inventory tracking mode to {}",
                product.getName(), productId, targetMode);

        return product.getInventoryTrackingMode();
    }
}
