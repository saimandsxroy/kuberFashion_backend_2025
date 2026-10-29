-- ==============================================================================
-- Migration V13: Reconcile Product and Category Active Column Schemas
-- ==============================================================================
-- Resolves the divergence between legacy SQL migrations ('active') and
-- Hibernate JPA entity mappings ('is_active').
-- Guarantees zero split-brain status and satisfies Hibernate schema validation.

-- 1. Ensure 'is_active' column exists on products (canonical column used by JPA)
ALTER TABLE products ADD COLUMN IF NOT EXISTS is_active BOOLEAN NOT NULL DEFAULT true;

-- 2. Ensure 'active' column exists on products (used by legacy views and V2 indexes)
ALTER TABLE products ADD COLUMN IF NOT EXISTS active BOOLEAN DEFAULT true;

-- 3. Synchronize product status values
UPDATE products SET is_active = active WHERE active IS NOT NULL AND (is_active IS NULL OR is_active != active);
UPDATE products SET active = is_active WHERE active IS NULL;

-- 4. Bidirectional trigger to keep 'active' and 'is_active' synchronized permanently
CREATE OR REPLACE FUNCTION sync_product_active_columns()
RETURNS TRIGGER AS $$
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF NEW.is_active IS NOT NULL AND (NEW.active IS NULL OR NEW.active != NEW.is_active) THEN
            NEW.active = NEW.is_active;
        ELSIF NEW.active IS NOT NULL AND NEW.is_active IS NULL THEN
            NEW.is_active = NEW.active;
        END IF;
    ELSIF TG_OP = 'UPDATE' THEN
        IF NEW.is_active IS DISTINCT FROM OLD.is_active THEN
            NEW.active = NEW.is_active;
        ELSIF NEW.active IS DISTINCT FROM OLD.active THEN
            NEW.is_active = NEW.active;
        END IF;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_sync_product_active ON products;
CREATE TRIGGER trg_sync_product_active
    BEFORE INSERT OR UPDATE ON products
    FOR EACH ROW
    EXECUTE FUNCTION sync_product_active_columns();

-- 5. Reconcile categories table active columns
ALTER TABLE categories ADD COLUMN IF NOT EXISTS is_active BOOLEAN NOT NULL DEFAULT true;
ALTER TABLE categories ADD COLUMN IF NOT EXISTS active BOOLEAN DEFAULT true;
UPDATE categories SET is_active = COALESCE(is_active, active, true);
UPDATE categories SET active = is_active;

CREATE OR REPLACE FUNCTION sync_category_active_columns()
RETURNS TRIGGER AS $$
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF NEW.is_active IS NOT NULL AND (NEW.active IS NULL OR NEW.active != NEW.is_active) THEN
            NEW.active = NEW.is_active;
        ELSIF NEW.active IS NOT NULL AND NEW.is_active IS NULL THEN
            NEW.is_active = NEW.active;
        END IF;
    ELSIF TG_OP = 'UPDATE' THEN
        IF NEW.is_active IS DISTINCT FROM OLD.is_active THEN
            NEW.active = NEW.is_active;
        ELSIF NEW.active IS DISTINCT FROM OLD.active THEN
            NEW.is_active = NEW.active;
        END IF;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_sync_category_active ON categories;
CREATE TRIGGER trg_sync_category_active
    BEFORE INSERT OR UPDATE ON categories
    FOR EACH ROW
    EXECUTE FUNCTION sync_category_active_columns();

-- 6. Update v_active_products view to reference the canonical 'is_active' column
CREATE OR REPLACE VIEW v_active_products AS
SELECT 
    p.id,
    p.name,
    p.slug,
    p.price,
    p.original_price,
    p.discount,
    p.image,
    p.rating,
    p.reviews,
    p.description,
    p.in_stock,
    p.featured,
    p.stock_quantity,
    p.created_at,
    c.id as category_id,
    c.slug as category_slug,
    c.name as category_name
FROM products p
JOIN categories c ON p.category_id = c.id
WHERE p.is_active = true;

-- 7. Optimized indexes on canonical is_active column
CREATE INDEX IF NOT EXISTS idx_products_is_active ON products(is_active);
CREATE INDEX IF NOT EXISTS idx_products_category_is_active ON products(category_id, is_active);
CREATE INDEX IF NOT EXISTS idx_categories_is_active ON categories(is_active);
