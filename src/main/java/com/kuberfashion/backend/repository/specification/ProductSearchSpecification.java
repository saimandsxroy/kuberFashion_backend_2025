package com.kuberfashion.backend.repository.specification;

import com.kuberfashion.backend.dto.AiSearchIntentDto;
import com.kuberfashion.backend.entity.Category;
import com.kuberfashion.backend.entity.Product;
import com.kuberfashion.backend.entity.ProductVariant;
import jakarta.persistence.criteria.*;
import org.springframework.data.jpa.domain.Specification;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Dynamic, type-safe JPA Specification for AI-powered product search.
 * Safely executes parameterized database queries according to interpreted search intent,
 * rigorously honoring inventory tracking rules across both LEGACY and VARIANT modes.
 */
public class ProductSearchSpecification implements Specification<Product> {

    private final AiSearchIntentDto intent;

    public ProductSearchSpecification(AiSearchIntentDto intent) {
        this.intent = intent != null ? intent : new AiSearchIntentDto();
    }

    @Override
    public Predicate toPredicate(Root<Product> root, CriteriaQuery<?> query, CriteriaBuilder cb) {
        query.distinct(true);

        List<Predicate> predicates = new ArrayList<>();

        // 1. Active Products Only (or active is null for backwards compatibility)
        predicates.add(cb.or(
                cb.isNull(root.get("active")),
                cb.isTrue(root.get("active"))
        ));

        // 2. Keyword Search (Name or Description)
        if (intent.getKeywords() != null && !intent.getKeywords().trim().isEmpty()) {
            String cleanKeywords = intent.getKeywords().trim().toLowerCase(Locale.ROOT);
            String[] tokens = cleanKeywords.split("\\s+");

            List<Predicate> keywordPredicates = new ArrayList<>();
            // Match the full phrase or individual key tokens
            String escapedKeywords = escapeLike(cleanKeywords);
            keywordPredicates.add(cb.like(cb.lower(root.get("name")), "%" + escapedKeywords + "%", '\\'));
            keywordPredicates.add(cb.like(cb.lower(root.get("description")), "%" + escapedKeywords + "%", '\\'));

            for (String token : tokens) {
                if (token.length() > 2) {
                    String escapedToken = escapeLike(token);
                    keywordPredicates.add(cb.like(cb.lower(root.get("name")), "%" + escapedToken + "%", '\\'));
                    keywordPredicates.add(cb.like(cb.lower(root.get("description")), "%" + escapedToken + "%", '\\'));
                }
            }

            predicates.add(cb.or(keywordPredicates.toArray(new Predicate[0])));
        }

        // 3. Category Filter
        if (intent.getCategory() != null && !intent.getCategory().trim().isEmpty()) {
            String cat = escapeLike(intent.getCategory().trim().toLowerCase(Locale.ROOT));
            Join<Product, Category> categoryJoin = root.join("category", JoinType.LEFT);

            predicates.add(cb.or(
                    cb.like(cb.lower(categoryJoin.get("slug")), "%" + cat + "%", '\\'),
                    cb.like(cb.lower(categoryJoin.get("name")), "%" + cat + "%", '\\')
            ));
        }

        // 4. Price Range Filters
        if (intent.getMinPrice() != null && intent.getMinPrice().compareTo(BigDecimal.ZERO) >= 0) {
            predicates.add(cb.greaterThanOrEqualTo(root.get("price"), intent.getMinPrice()));
        }
        if (intent.getMaxPrice() != null && intent.getMaxPrice().compareTo(BigDecimal.ZERO) > 0) {
            predicates.add(cb.lessThanOrEqualTo(root.get("price"), intent.getMaxPrice()));
        }

        // 5. Inventory & Variant Awareness
        boolean filterSize = intent.getSize() != null && !intent.getSize().trim().isEmpty();
        boolean filterColor = intent.getColor() != null && !intent.getColor().trim().isEmpty();
        boolean inStockRequested = Boolean.TRUE.equals(intent.getInStockOnly()) || filterSize || filterColor;

        if (inStockRequested) {
            // A. Subquery for Variant Tracking Mode
            Subquery<Long> variantSubquery = query.subquery(Long.class);
            Root<ProductVariant> pv = variantSubquery.from(ProductVariant.class);
            List<Predicate> pvPreds = new ArrayList<>();

            pvPreds.add(cb.equal(pv.get("product").get("id"), root.get("id")));
            pvPreds.add(cb.isTrue(pv.get("active")));
            pvPreds.add(cb.greaterThan(pv.get("stockQuantity"), 0));

            if (filterSize) {
                pvPreds.add(cb.equal(
                        cb.upper(pv.get("size")),
                        intent.getSize().trim().toUpperCase(Locale.ROOT)
                ));
            }

            if (filterColor) {
                pvPreds.add(cb.like(
                        cb.upper(pv.get("color")),
                        "%" + escapeLike(intent.getColor().trim().toUpperCase(Locale.ROOT)) + "%",
                        '\\'
                ));
            }

            variantSubquery.select(pv.get("product").get("id"))
                    .where(pvPreds.toArray(new Predicate[0]));

            Predicate variantTrackingMatch = cb.and(
                    cb.equal(root.get("inventoryTrackingMode"), Product.InventoryTrackingMode.VARIANT),
                    cb.exists(variantSubquery)
            );

            // B. Condition for Legacy Tracking Mode (treats null mode as LEGACY for backward compatibility)
            List<Predicate> legacyPreds = new ArrayList<>();
            legacyPreds.add(cb.or(
                    cb.isNull(root.get("inventoryTrackingMode")),
                    cb.equal(root.get("inventoryTrackingMode"), Product.InventoryTrackingMode.LEGACY)
            ));

            if (Boolean.TRUE.equals(intent.getInStockOnly())) {
                legacyPreds.add(cb.isTrue(root.get("inStock")));
                legacyPreds.add(cb.greaterThan(root.get("stockQuantity"), 0));
            }

            if (filterSize) {
                Join<Product, String> sizesJoin = root.join("sizes", JoinType.LEFT);
                legacyPreds.add(cb.equal(
                        cb.upper(sizesJoin),
                        intent.getSize().trim().toUpperCase(Locale.ROOT)
                ));
            }

            if (filterColor) {
                Join<Product, String> colorsJoin = root.join("colors", JoinType.LEFT);
                legacyPreds.add(cb.like(
                        cb.upper(colorsJoin),
                        "%" + escapeLike(intent.getColor().trim().toUpperCase(Locale.ROOT)) + "%",
                        '\\'
                ));
            }

            Predicate legacyTrackingMatch = cb.and(legacyPreds.toArray(new Predicate[0]));

            // Combine both: product can match either as a VARIANT-tracked item or a LEGACY-tracked item
            predicates.add(cb.or(variantTrackingMatch, legacyTrackingMatch));
        }

        return cb.and(predicates.toArray(new Predicate[0]));
    }

    private String escapeLike(String input) {
        if (input == null) return "";
        return input.replace("\\", "\\\\")
                    .replace("%", "\\%")
                    .replace("_", "\\_");
    }
}
