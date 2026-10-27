-- ==============================================================================
-- Migration V10: Variant-Level Inventory Management
-- ==============================================================================
-- 1. Add inventory_tracking_mode to products table with default 'LEGACY'
--    Ensures 100% backward compatibility for all existing products.
ALTER TABLE products ADD COLUMN IF NOT EXISTS inventory_tracking_mode VARCHAR(20) NOT NULL DEFAULT 'LEGACY';

-- 2. Create product_variants table for individual sellable variants (size + color)
CREATE TABLE IF NOT EXISTS product_variants (
    id BIGSERIAL PRIMARY KEY,
    product_id BIGINT NOT NULL REFERENCES products(id) ON DELETE CASCADE,
    size VARCHAR(50) NOT NULL,
    color VARCHAR(50) NOT NULL,
    sku VARCHAR(100),
    stock_quantity INT NOT NULL DEFAULT 0,
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_product_variants_stock CHECK (stock_quantity >= 0)
);

-- 3. Unique index preventing duplicate variants (case-insensitive & whitespace trimmed)
CREATE UNIQUE INDEX IF NOT EXISTS uq_product_variants_prod_size_color
ON product_variants (product_id, UPPER(TRIM(size)), UPPER(TRIM(color)));

-- 4. Foreign key and lookup indexes
CREATE INDEX IF NOT EXISTS idx_product_variants_product_id ON product_variants (product_id);
CREATE INDEX IF NOT EXISTS idx_product_variants_sku ON product_variants (sku);

-- 5. Add variant_id to order_items table to link ordered items directly to variants
ALTER TABLE order_items ADD COLUMN IF NOT EXISTS variant_id BIGINT REFERENCES product_variants(id) ON DELETE SET NULL;
CREATE INDEX IF NOT EXISTS idx_order_items_variant_id ON order_items (variant_id);
