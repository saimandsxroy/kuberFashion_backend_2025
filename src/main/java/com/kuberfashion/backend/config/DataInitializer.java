package com.kuberfashion.backend.config;

import com.kuberfashion.backend.entity.User;
import com.kuberfashion.backend.entity.Product;
import com.kuberfashion.backend.entity.Category;
import com.kuberfashion.backend.repository.UserRepository;
import com.kuberfashion.backend.repository.ProductRepository;
import com.kuberfashion.backend.repository.CategoryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * Initializes essential data for the application.
 * Runs after StartupValidator to ensure database connectivity.
 */
@Component
@Order(2) // Run after StartupValidator
public class DataInitializer implements CommandLineRunner {

    private static final Logger logger = LoggerFactory.getLogger(DataInitializer.class);

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private CategoryRepository categoryRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired(required = false)
    private org.springframework.data.redis.core.RedisTemplate<String, Object> redisTemplate;

    @Value("${spring.profiles.active:dev}")
    private String activeProfile;

    @Value("${SKIP_DATA_INITIALIZATION:false}")
    private boolean skipDataInitialization;

    @Override
    @Transactional
    public void run(String... args) throws Exception {
        logger.info("🚀 Starting data initialization...");
        logger.info("📋 Active profile: {}", activeProfile);

        if (skipDataInitialization) {
            logger.info("⏭️  Data initialization skipped (SKIP_DATA_INITIALIZATION=true)");
            return;
        }

        try {
            // CRITICAL: Clear cache on startup to prevent deserialization errors
            try {
                if (redisTemplate != null) {
                    redisTemplate.getConnectionFactory().getConnection().serverCommands().flushAll();
                    logger.info("🧹 Redis cache cleared on startup");
                }
            } catch (Exception e) {
                logger.warn("⚠️ Failed to clear Redis cache on startup: {}", e.getMessage());
            }

            // CRITICAL: Create admin user FIRST before anything else
            initializeAdminUser();

            // Create test user in development
            if ("dev".equals(activeProfile)) {
                initializeTestUser();
            }

            // Initialize categories (essential for the application)
            initializeCategories();

            // Initialize sample products only if database is empty
            initializeSampleProducts();

            logger.info("✅ Data initialization completed successfully!");

        } catch (Exception e) {
            logger.error("❌ Data initialization failed: {}", e.getMessage(), e);

            // In production, we might want to continue without sample data
            if ("prod".equals(activeProfile)) {
                logger.warn("⚠️  Continuing startup despite data initialization failure in production mode");
            } else {
                throw new RuntimeException("Data initialization failed", e);
            }
        }
    }

    private void initializeTestUser() {
        try {
            Optional<User> byPhone = userRepository.findByPhone("9876543210");
            Optional<User> byEmail = userRepository.findByEmail("test@kuberfashion.com");
            if (byPhone.isPresent()) {
                User user = byPhone.get();
                user.setPassword(passwordEncoder.encode("test123"));
                user.setEnabled(true);
                userRepository.save(user);
                logger.info("ℹ️ Test user updated with test123 password: phone: {}", user.getPhone());
            } else if (byEmail.isPresent()) {
                User user = byEmail.get();
                user.setPassword(passwordEncoder.encode("test123"));
                user.setEnabled(true);
                userRepository.save(user);
                logger.info("ℹ️ Test user updated with test123 password: email: {}", user.getEmail());
            } else {
                User testUser = new User();
                testUser.setFirstName("Test");
                testUser.setLastName("User");
                testUser.setEmail("test@kuberfashion.com");
                testUser.setPhone("9876543210");
                testUser.setPassword(passwordEncoder.encode("test123"));
                testUser.setRole(User.Role.USER);
                testUser.setEnabled(true);

                userRepository.save(testUser);
                logger.info("✅ Test user created: test@kuberfashion.com / test123");
            }
        } catch (Exception e) {
            logger.error("❌ Failed to initialize test user: {}", e.getMessage());
            throw e;
        }
    }

    private void initializeAdminUser() {
        try {
            // Check if admin exists with the new email OR phone already in use
            Optional<User> oldAdmin = userRepository.findByEmail("admin@kuberfashion.com");
            if (oldAdmin.isPresent()) {
                User admin = oldAdmin.get();
                admin.setEmail("admin@kuberfashion.in");
                admin.setPassword(passwordEncoder.encode("kuberfashion2025@"));
                userRepository.save(admin);
                logger.info("✅ Admin user migrated to admin@kuberfashion.in / kuberfashion2025@");
            } else if (!userRepository.existsByEmail("admin@kuberfashion.in") && !userRepository.existsByPhone("1234567890")) {
                User admin = new User();
                admin.setFirstName("Admin");
                admin.setLastName("KuberFashion");
                admin.setEmail("admin@kuberfashion.in");
                admin.setPhone("1234567890");
                admin.setPassword(passwordEncoder.encode("kuberfashion2025@"));
                admin.setRole(User.Role.ADMIN);
                admin.setEnabled(true);

                userRepository.save(admin);
                logger.info("✅ Admin user created successfully!");
                logger.info("   📧 Email: admin@kuberfashion.in");
                logger.info("   🔑 Password: kuberfashion2025@");
            } else {
                logger.info("✅ Admin user already exists");
            }

            // Also print confirmation for console
            System.out.println("ℹ️ Admin user already exists.");
        } catch (Exception e) {
            logger.error("❌ CRITICAL: Failed to create admin user: {}", e.getMessage());
            throw new RuntimeException("Admin user creation failed - this is critical for application functionality",
                    e);
        }
    }

    private void initializeCategories() {
        try {
            logger.info("📂 Initializing categories...");

            String[] categoryData = {
                    "mens-fashion,Men's Fashion",
                    "womens-fashion,Women's Fashion",
                    "kids-fashion,Kids Fashion",
                    "footwear,Footwear",
                    "kvision,KVision Membership",
                    "accessories,Accessories",
                    "sports-active,Sports & Active"
            };

            int createdCount = 0;
            for (String data : categoryData) {
                String[] parts = data.split(",");
                String slug = parts[0];
                String name = parts[1];

                if (!categoryRepository.existsBySlug(slug)) {
                    Category category = new Category();
                    category.setSlug(slug);
                    category.setName(name);
                    category.setDescription("Premium " + name.toLowerCase() + " collection");
                    category.setActive(true);
                    categoryRepository.save(category);
                    logger.info("✅ Category created: {}", name);
                    createdCount++;
                } else {
                    logger.debug("ℹ️  Category already exists: {}", name);
                }
            }

            if (createdCount > 0) {
                logger.info("✅ Created {} new categories", createdCount);
            } else {
                logger.info("ℹ️  All categories already exist");
            }

        } catch (Exception e) {
            logger.error("❌ Failed to initialize categories: {}", e.getMessage());
            throw new RuntimeException("Category initialization failed", e);
        }
    }

    private void initializeSampleProducts() {
        try {
            long productCount = productRepository.count();
            if (productCount > 0) {
                logger.info("ℹ️  Products already exist ({} products), skipping sample data initialization",
                        productCount);
                return;
            }

            // Only create sample products in development mode or if explicitly requested
            if ("prod".equals(activeProfile)) {
                logger.info("ℹ️  Skipping sample product creation in production mode");
                return;
            }

            logger.info("🛍️  Initializing sample products...");

            Category mensCategory = categoryRepository.findBySlug("mens-fashion").orElse(null);
            Category womensCategory = categoryRepository.findBySlug("womens-fashion").orElse(null);
            Category footwearCategory = categoryRepository.findBySlug("footwear").orElse(null);

            if (mensCategory == null || womensCategory == null || footwearCategory == null) {
                logger.error("❌ ERROR: Required categories not found! Cannot create sample products.");
                logger.error("   Missing categories - mens: {}, womens: {}, footwear: {}",
                        mensCategory == null, womensCategory == null, footwearCategory == null);
                return;
            }

            if (mensCategory != null) {
                createProduct("Classic Cotton T-Shirt", "classic-cotton-tshirt",
                        new BigDecimal("29.99"), new BigDecimal("39.99"), 25, mensCategory,
                        "https://images.unsplash.com/photo-1521572163474-6864f9cf17ab?ixlib=rb-4.0.3&auto=format&fit=crop&w=800&q=80",
                        Arrays.asList(
                                "https://images.unsplash.com/photo-1521572163474-6864f9cf17ab?ixlib=rb-4.0.3&auto=format&fit=crop&w=800&q=80"),
                        "Premium quality cotton t-shirt with comfortable fit. Perfect for casual wear and everyday comfort.",
                        Arrays.asList("S", "M", "L", "XL", "XXL"),
                        Arrays.asList("White", "Black", "Navy", "Gray"),
                        true, true, 50);

                createProduct("Denim Jacket", "denim-jacket",
                        new BigDecimal("69.99"), new BigDecimal("89.99"), 22, mensCategory,
                        "https://images.unsplash.com/photo-1551698618-1dfe5d97d256?ixlib=rb-4.0.3&auto=format&fit=crop&w=800&q=80",
                        Arrays.asList(
                                "https://images.unsplash.com/photo-1551698618-1dfe5d97d256?ixlib=rb-4.0.3&auto=format&fit=crop&w=800&q=80"),
                        "Classic denim jacket with vintage wash. Timeless style that goes with everything.",
                        Arrays.asList("S", "M", "L", "XL", "XXL"),
                        Arrays.asList("Light Blue", "Dark Blue", "Black"),
                        true, false, 30);
            }

            if (womensCategory != null) {
                createProduct("Elegant Summer Dress", "elegant-summer-dress",
                        new BigDecimal("79.99"), new BigDecimal("99.99"), 20, womensCategory,
                        "https://images.unsplash.com/photo-1515372039744-b8f02a3ae446?ixlib=rb-4.0.3&auto=format&fit=crop&w=800&q=80",
                        Arrays.asList(
                                "https://images.unsplash.com/photo-1515372039744-b8f02a3ae446?ixlib=rb-4.0.3&auto=format&fit=crop&w=800&q=80"),
                        "Flowing summer dress perfect for warm weather. Lightweight fabric with beautiful floral patterns.",
                        Arrays.asList("XS", "S", "M", "L", "XL"),
                        Arrays.asList("Floral Blue", "Floral Pink", "Solid White"),
                        true, true, 25);

                createProduct("Silk Blouse", "silk-blouse",
                        new BigDecimal("95.99"), new BigDecimal("129.99"), 26, womensCategory,
                        "https://images.unsplash.com/photo-1564257577-4d4c6b5b8b8a?ixlib=rb-4.0.3&auto=format&fit=crop&w=800&q=80",
                        Arrays.asList(
                                "https://images.unsplash.com/photo-1564257577-4d4c6b5b8b8a?ixlib=rb-4.0.3&auto=format&fit=crop&w=800&q=80"),
                        "Luxurious silk blouse perfect for professional and formal occasions. Elegant and comfortable.",
                        Arrays.asList("XS", "S", "M", "L", "XL"),
                        Arrays.asList("White", "Cream", "Navy", "Black"),
                        true, true, 15);
            }

            if (footwearCategory != null) {
                createProduct("Premium Sneakers", "premium-sneakers",
                        new BigDecimal("129.99"), new BigDecimal("159.99"), 19, footwearCategory,
                        "https://images.unsplash.com/photo-1549298916-b41d501d3772?ixlib=rb-4.0.3&auto=format&fit=crop&w=800&q=80",
                        Arrays.asList(
                                "https://images.unsplash.com/photo-1549298916-b41d501d3772?ixlib=rb-4.0.3&auto=format&fit=crop&w=800&q=80"),
                        "High-quality sneakers with superior comfort and style. Perfect for both casual and athletic wear.",
                        Arrays.asList("7", "8", "9", "10", "11", "12"),
                        Arrays.asList("White", "Black", "Gray", "Navy"),
                        true, true, 40);
            }

            logger.info("✅ Sample products created successfully!");
        } catch (Exception e) {
            logger.error("❌ ERROR initializing sample products: {}", e.getMessage(), e);

            // Don't fail startup for sample product creation issues
            if ("prod".equals(activeProfile)) {
                logger.warn("⚠️  Sample product creation failed in production - continuing startup");
            } else {
                throw new RuntimeException("Sample product initialization failed", e);
            }
        }
    }

    private void createProduct(String name, String slug, BigDecimal price, BigDecimal originalPrice,
            int discount, Category category, String image, List<String> images,
            String description, List<String> sizes, List<String> colors,
            boolean inStock, boolean featured, int stockQuantity) {
        Product product = new Product();
        product.setName(name);
        product.setSlug(slug);
        product.setPrice(price);
        product.setOriginalPrice(originalPrice);
        product.setDiscount(discount);
        product.setCategory(category);
        product.setImage(image);
        product.setImages(images);
        product.setRating(new BigDecimal("4.5"));
        product.setReviews(100);
        product.setDescription(description);
        product.setSizes(sizes);
        product.setColors(colors);
        product.setInStock(inStock);
        product.setFeatured(featured);
        product.setStockQuantity(stockQuantity);
        product.setActive(true);

        productRepository.save(product);
    }
}
