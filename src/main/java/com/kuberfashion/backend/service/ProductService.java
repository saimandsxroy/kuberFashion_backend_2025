package com.kuberfashion.backend.service;

import com.kuberfashion.backend.dto.AiSearchIntentDto;
import com.kuberfashion.backend.dto.AiSearchRequestDto;
import com.kuberfashion.backend.dto.AiSearchResponseDto;
import com.kuberfashion.backend.dto.ProductResponseDto;
import com.kuberfashion.backend.entity.Product;
import com.kuberfashion.backend.exception.ResourceNotFoundException;
import com.kuberfashion.backend.repository.ProductRepository;
import com.kuberfashion.backend.repository.specification.ProductSearchSpecification;
import com.kuberfashion.backend.service.ai.AiSearchIntentService;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.beans.factory.annotation.Autowired;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Service
@Transactional(readOnly = true)
public class ProductService {

    private static final Logger logger = LoggerFactory.getLogger(ProductService.class);

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private AiSearchIntentService aiSearchIntentService;

    @Cacheable(value = "products_list", key = "'all'")
    public List<ProductResponseDto> getAllProducts() {
        try {
            logger.info("Cache MISS - Fetching all products from database");
            List<Product> products = productRepository.findByActiveTrue();
            logger.info("Found {} active products from database", products.size());
            return products.stream()
                    .map(ProductResponseDto::new)
                    .collect(Collectors.toList());
        } catch (Exception e) {
            logger.error("Error fetching all products: {}", e.getMessage(), e);
            return new ArrayList<>();
        }
    }

    // @Cacheable(value = "products", key = "#id")
    public ProductResponseDto getProductById(Long id) {
        logger.info("Fetching product with id {} from database", id);
        Product product = productRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Product not found with id: " + id));
        return new ProductResponseDto(product);
    }

    public ProductResponseDto getProductBySlug(String slug) {
        Product product = productRepository.findBySlug(slug)
                .orElseThrow(() -> new ResourceNotFoundException("Product not found with slug: " + slug));
        return new ProductResponseDto(product);
    }

    @Cacheable(value = "products_list", key = "'featured'")
    public List<ProductResponseDto> getFeaturedProducts() {
        return productRepository.findByFeaturedTrueAndActiveTrue()
                .stream()
                .map(ProductResponseDto::new)
                .collect(Collectors.toList());
    }

    @Cacheable(value = "products_list", key = "'cat:' + #categorySlug")
    public List<ProductResponseDto> getProductsByCategory(String categorySlug) {
        return productRepository.findByCategorySlugAndActiveTrue(categorySlug)
                .stream()
                .map(ProductResponseDto::new)
                .collect(Collectors.toList());
    }

    @Cacheable(value = "products_list", key = "'cat:' + #categorySlug + ':p:' + #page + ':s:' + #size + ':sort:' + #sortBy + ':' + #sortDir")
    public Page<ProductResponseDto> getProductsByCategoryPaginated(String categorySlug, int page, int size,
            String sortBy, String sortDir) {
        Sort.Direction direction = sortDir.equalsIgnoreCase("desc") ? Sort.Direction.DESC : Sort.Direction.ASC;
        Pageable pageable = PageRequest.of(page, size, Sort.by(direction, getSortField(sortBy)));

        Page<Product> products = productRepository.findByCategorySlugAndActiveTrue(categorySlug, pageable);
        return products.map(ProductResponseDto::new);
    }

    @Cacheable(value = "products_list", key = "'search:' + #keyword + ':p:' + #page + ':s:' + #size")
    public Page<ProductResponseDto> searchProducts(String keyword, int page, int size) {
        Pageable pageable = PageRequest.of(page, size);
        Page<Product> products = productRepository.searchProducts(keyword, pageable);
        return products.map(ProductResponseDto::new);
    }

    @Cacheable(value = "products_list", key = "'price:' + #minPrice + ':' + #maxPrice + ':p:' + #page + ':s:' + #size")
    public Page<ProductResponseDto> getProductsByPriceRange(BigDecimal minPrice, BigDecimal maxPrice, int page,
            int size) {
        Pageable pageable = PageRequest.of(page, size);
        Page<Product> products = productRepository.findByPriceRange(minPrice, maxPrice, pageable);
        return products.map(ProductResponseDto::new);
    }

    @Cacheable(value = "products_list", key = "'cat:' + #categorySlug + ':price:' + #minPrice + ':' + #maxPrice + ':p:' + #page + ':s:' + #size")
    public Page<ProductResponseDto> getProductsByCategoryAndPriceRange(String categorySlug, BigDecimal minPrice,
            BigDecimal maxPrice, int page, int size) {
        Pageable pageable = PageRequest.of(page, size);
        Page<Product> products = productRepository.findByCategoryAndPriceRange(categorySlug, minPrice, maxPrice,
                pageable);
        return products.map(ProductResponseDto::new);
    }

    @Cacheable(value = "products_list", key = "'top:' + #limit")
    public List<ProductResponseDto> getTopRatedProducts(int limit) {
        Pageable pageable = PageRequest.of(0, limit);
        return productRepository.findTopRatedProducts(pageable)
                .stream()
                .map(ProductResponseDto::new)
                .collect(Collectors.toList());
    }

    @Cacheable(value = "products_list", key = "'new:' + #limit")
    public List<ProductResponseDto> getNewestProducts(int limit) {
        Pageable pageable = PageRequest.of(0, limit);
        return productRepository.findNewestProducts(pageable)
                .stream()
                .map(ProductResponseDto::new)
                .collect(Collectors.toList());
    }

    @Cacheable(value = "products_list", key = "'trending'")
    public List<ProductResponseDto> getTrendingProducts() {
        // For now, return top rated products as trending
        return getTopRatedProducts(10);
    }

    @Cacheable(value = "products_list", key = "'available'")
    public List<ProductResponseDto> getAvailableProducts() {
        return productRepository.findAvailableProducts()
                .stream()
                .map(ProductResponseDto::new)
                .collect(Collectors.toList());
    }

    @Cacheable(value = "products_list", key = "'offers'")
    public List<ProductResponseDto> getOfferProducts() {
        return productRepository.findByIsOfferTrueAndActiveTrue()
                .stream()
                .map(ProductResponseDto::new)
                .collect(Collectors.toList());
    }

    public long getTotalProducts() {
        return productRepository.countActiveProducts();
    }

    public long getAvailableProductsCount() {
        return productRepository.countAvailableProducts();
    }

    // Admin methods
    @Transactional
    @CacheEvict(value = { "products_list" }, allEntries = true)
    public Product createProduct(Product product) {
        // Ensure unique slug
        String baseSlug = product.getSlug();
        String uniqueSlug = baseSlug;
        int counter = 1;

        while (productRepository.existsBySlug(uniqueSlug)) {
            uniqueSlug = baseSlug + "-" + counter;
            counter++;
        }

        product.setSlug(uniqueSlug);
        return productRepository.save(product);
    }

    @Transactional
    @CacheEvict(value = { "products", "products_list" }, key = "#product.id", allEntries = true)
    public Product updateProduct(Product product) {
        // Check if slug changed and ensure uniqueness
        Product existingProduct = productRepository.findById(product.getId()).orElse(null);
        if (existingProduct != null && !existingProduct.getSlug().equals(product.getSlug())) {
            String baseSlug = product.getSlug();
            String uniqueSlug = baseSlug;
            int counter = 1;

            while (productRepository.existsBySlug(uniqueSlug) && !uniqueSlug.equals(existingProduct.getSlug())) {
                uniqueSlug = baseSlug + "-" + counter;
                counter++;
            }

            product.setSlug(uniqueSlug);
        }
        return productRepository.save(product);
    }

    @Transactional
    @CacheEvict(value = { "products", "products_list" }, key = "#id", allEntries = true)
    public void deleteProduct(Long id) {
        productRepository.deleteById(id);
    }

    public Product findById(Long id) {
        return productRepository.findById(id).orElse(null);
    }

    public AiSearchResponseDto searchWithAi(AiSearchRequestDto request, Pageable pageable) {
        String rawQuery = request != null && request.getQuery() != null ? request.getQuery().trim() : "";
        logger.info("🤖 Executing AI smart search for customer query: '{}'", rawQuery);

        // 1. Parse natural-language intent via Gemini (with automatic heuristic fallback)
        AiSearchIntentDto intent = aiSearchIntentService.parseSearchIntent(rawQuery);

        // 2. Apply customer manual filter overrides if chips were edited or cleared in UI
        if (request != null) {
            if (request.getKeywords() != null) {
                String kw = request.getKeywords().trim();
                intent.setKeywords(kw.isEmpty() ? null : kw);
            }
            if (request.getCategory() != null) {
                String cat = request.getCategory().trim();
                intent.setCategory(cat.isEmpty() ? null : cat.toLowerCase(java.util.Locale.ROOT));
            }
            if (request.getColor() != null) {
                String col = request.getColor().trim();
                intent.setColor(col.isEmpty() ? null : col);
            }
            if (request.getSize() != null) {
                String sz = request.getSize().trim();
                intent.setSize(sz.isEmpty() ? null : sz);
            }
            if (request.getMinPrice() != null) {
                intent.setMinPrice(request.getMinPrice().compareTo(BigDecimal.ZERO) <= 0 ? null : request.getMinPrice());
            }
            if (request.getMaxPrice() != null) {
                intent.setMaxPrice(request.getMaxPrice().compareTo(BigDecimal.ZERO) <= 0 ? null : request.getMaxPrice());
            }
            if (request.getInStockOnly() != null) {
                intent.setInStockOnly(request.getInStockOnly());
            }
        }

        // 3. Construct safe, parameterized, inventory-aware JPA criteria specification
        ProductSearchSpecification spec = new ProductSearchSpecification(intent);

        // 4. Query PostgreSQL database
        Page<Product> productPage = productRepository.findAll(spec, pageable);

        // 5. Convert to ProductResponseDto Page
        Page<ProductResponseDto> dtoPage = productPage.map(ProductResponseDto::new);

        return new AiSearchResponseDto(intent, dtoPage);
    }

    private String getSortField(String sortBy) {
        switch (sortBy) {
            case "price-low":
            case "price-high":
                return "price";
            case "rating":
                return "rating";
            case "newest":
                return "createdAt";
            case "name":
                return "name";
            default:
                return "id"; // Default sort by id for featured
        }
    }
}
