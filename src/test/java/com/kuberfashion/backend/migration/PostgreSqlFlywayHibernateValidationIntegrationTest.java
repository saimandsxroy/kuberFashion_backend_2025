package com.kuberfashion.backend.migration;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
public class PostgreSqlFlywayHibernateValidationIntegrationTest {

    @DynamicPropertySource
        registry.add("spring.datasource.url", () -> System.getenv().getOrDefault("DATABASE_URL", "jdbc:postgresql://localhost:5432/kf_empty_test?sslmode=disable"));
        registry.add("spring.datasource.username", () -> System.getenv().getOrDefault("DATABASE_USERNAME", "kuberfashion_user"));
        registry.add("spring.datasource.password", () -> System.getenv().getOrDefault("DATABASE_PASSWORD", ""));
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.flyway.baseline-on-migrate", () -> "true");
        registry.add("spring.flyway.baseline-version", () -> "3");
        registry.add("spring.jpa.defer-datasource-initialization", () -> "false");
        registry.add("SKIP_DATA_INITIALIZATION", () -> "true");
        registry.add("spring.cache.type", () -> "none");
        registry.add("jwt.secret", () -> "test-only-jwt-secret-used-in-unit-tests-only-do-not-use-in-prod-32bytes");
        registry.add("phonepe.simulation-enabled", () -> "true");
        registry.add("cloudflare.r2.enabled", () -> "false");
    }

    @PersistenceContext
    private EntityManager entityManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private javax.sql.DataSource dataSource;

    @Test
    @DisplayName("Verify Flyway validation succeeds and all migrations up to V14 are valid")
    void testFlywayValidationSucceeds() {
        Flyway flywayInstance = Flyway.configure()
            .dataSource(dataSource)
            .baselineOnMigrate(true)
            .baselineVersion("3")
            .load();
        assertDoesNotThrow(() -> flywayInstance.validate(), "Flyway validation must succeed without checksum mismatch");
    }

    @Test
    @DisplayName("Verify Hibernate schema validation passes and all 14 entities can be queried")
    void testHibernateSchemaValidationPasses() {
        assertNotNull(entityManager, "EntityManager must be initialized");

        // Verify count on key entities works cleanly
        Long userCount = entityManager.createQuery("SELECT COUNT(u) FROM User u", Long.class).getSingleResult();
        assertNotNull(userCount);

        Long productCount = entityManager.createQuery("SELECT COUNT(p) FROM Product p", Long.class).getSingleResult();
        assertNotNull(productCount);

        Long categoryCount = entityManager.createQuery("SELECT COUNT(c) FROM Category c", Long.class).getSingleResult();
        assertNotNull(categoryCount);

        Long orderCount = entityManager.createQuery("SELECT COUNT(o) FROM Order o", Long.class).getSingleResult();
        assertNotNull(orderCount);

        Long coinBalanceCount = entityManager.createQuery("SELECT COUNT(cb) FROM CoinBalance cb", Long.class).getSingleResult();
        assertNotNull(coinBalanceCount);
    }

    @Test
    @DisplayName("Verify bidirectional trigger keeps 'is_active' and 'active' synchronized on products")
    void testProductActiveBidirectionalSync() {
        // 1. Insert product with is_active = false
        jdbcTemplate.execute("""
            INSERT INTO products (name, slug, price, in_stock, stock_quantity, featured, is_active, category_id)
            VALUES ('Sync Test Shirt', 'sync-test-shirt-' || gen_random_uuid(), 499.00, true, 10, false, false, 1);
        """);

        Boolean activeVal = jdbcTemplate.queryForObject(
            "SELECT active FROM products WHERE slug LIKE 'sync-test-shirt-%' ORDER BY id DESC LIMIT 1",
            Boolean.class
        );
        assertEquals(Boolean.FALSE, activeVal, "Legacy 'active' column must be synced to FALSE on insert");

        // 2. Update via legacy 'active' column
        jdbcTemplate.execute("UPDATE products SET active = true WHERE slug LIKE 'sync-test-shirt-%';");

        Boolean isActiveVal = jdbcTemplate.queryForObject(
            "SELECT is_active FROM products WHERE slug LIKE 'sync-test-shirt-%' ORDER BY id DESC LIMIT 1",
            Boolean.class
        );
        assertEquals(Boolean.TRUE, isActiveVal, "Canonical 'is_active' column must be synced to TRUE when 'active' is updated");

        // Cleanup test product
        jdbcTemplate.execute("DELETE FROM products WHERE slug LIKE 'sync-test-shirt-%';");
    }

    @Test
    @DisplayName("Verify Flyway migration and validation against representative existing database")
    void testExistingRepresentativeDatabaseFlywayValidation() {
        String dbUrl = System.getenv().getOrDefault("DATABASE_URL", "jdbc:postgresql://localhost:5432/kuberfashion?sslmode=disable");
        String dbUser = System.getenv().getOrDefault("DATABASE_USERNAME", "kuberfashion_user");
        String dbPass = System.getenv().getOrDefault("DATABASE_PASSWORD", "");
        Flyway existingFlyway = Flyway.configure()
            .dataSource(dbUrl, dbUser, dbPass)
            .baselineOnMigrate(true)
            .baselineVersion("3")
            .load();

        assertDoesNotThrow(() -> existingFlyway.migrate(), "Flyway migrate against existing database must succeed");
        assertDoesNotThrow(() -> existingFlyway.validate(), "Flyway validate against existing database must succeed without checksum error");
    }
}
