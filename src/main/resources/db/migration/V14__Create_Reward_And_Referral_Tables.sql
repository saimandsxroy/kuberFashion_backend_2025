-- ==============================================================================
-- Migration V14: Reconcile Complete Application Schema
-- ==============================================================================
-- 1. Reconcile users table (supabase_id to VARCHAR(255))
-- 2. Reconcile products table (discount type to INTEGER, is_offer column)
-- 3. Reconcile categories table (product_count column)
-- 4. Reconcile payment_transactions table (redirect_url column)
-- 5. Reconcile cart_items table (selected_size, selected_color columns)
-- 6. Reconcile orders table (subtotal, tax, shipping, referral_code, purchase_mode, timestamps)
-- 7. Ensure reward and referral tables exist (coin_balances, coin_transactions, coupon_transactions, referral_relations)
-- Guarantees 100% strict Hibernate ddl-auto=validate compatibility across fresh and existing databases.

-- ------------------------------------------------------------------------------
-- 1. Users Schema Reconcile
-- ------------------------------------------------------------------------------
DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM information_schema.columns 
        WHERE table_name = 'users' AND column_name = 'supabase_id' 
        AND data_type = 'uuid'
    ) THEN
        ALTER TABLE users ALTER COLUMN supabase_id TYPE VARCHAR(255) USING supabase_id::text;
    END IF;
END $$;

-- ------------------------------------------------------------------------------
-- 2. Products Schema Reconcile
-- ------------------------------------------------------------------------------
DROP VIEW IF EXISTS v_active_products;

DO $$
BEGIN
    -- Reconcile discount type to INTEGER to match Product entity
    IF EXISTS (
        SELECT 1 FROM information_schema.columns 
        WHERE table_name = 'products' AND column_name = 'discount' 
        AND data_type IN ('numeric', 'decimal')
    ) THEN
        ALTER TABLE products ALTER COLUMN discount TYPE INTEGER USING ROUND(COALESCE(discount, 0))::INTEGER;
    END IF;
END $$;

ALTER TABLE products ALTER COLUMN discount SET DEFAULT 0;
ALTER TABLE products ADD COLUMN IF NOT EXISTS is_offer BOOLEAN NOT NULL DEFAULT false;

-- Recreate view with updated discount column
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

-- ------------------------------------------------------------------------------
-- 3. Categories Schema Reconcile
-- ------------------------------------------------------------------------------
ALTER TABLE categories ADD COLUMN IF NOT EXISTS product_count INTEGER DEFAULT 0;

-- ------------------------------------------------------------------------------
-- 4. Payment Transactions Schema Reconcile
-- ------------------------------------------------------------------------------
ALTER TABLE payment_transactions ADD COLUMN IF NOT EXISTS redirect_url VARCHAR(1000);

-- ------------------------------------------------------------------------------
-- 5. Cart Items Schema Reconcile
-- ------------------------------------------------------------------------------
DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM information_schema.columns 
        WHERE table_name = 'cart_items' AND column_name = 'color'
    ) AND NOT EXISTS (
        SELECT 1 FROM information_schema.columns 
        WHERE table_name = 'cart_items' AND column_name = 'selected_color'
    ) THEN
        ALTER TABLE cart_items RENAME COLUMN color TO selected_color;
    ELSE
        ALTER TABLE cart_items ADD COLUMN IF NOT EXISTS selected_color VARCHAR(50);
    END IF;
END $$;

DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM information_schema.columns 
        WHERE table_name = 'cart_items' AND column_name = 'size'
    ) AND NOT EXISTS (
        SELECT 1 FROM information_schema.columns 
        WHERE table_name = 'cart_items' AND column_name = 'selected_size'
    ) THEN
        ALTER TABLE cart_items RENAME COLUMN size TO selected_size;
    ELSE
        ALTER TABLE cart_items ADD COLUMN IF NOT EXISTS selected_size VARCHAR(10);
    END IF;
END $$;

-- ------------------------------------------------------------------------------
-- 6. Orders Schema Reconcile
-- ------------------------------------------------------------------------------
ALTER TABLE orders ADD COLUMN IF NOT EXISTS subtotal DECIMAL(10,2) NOT NULL DEFAULT 0;
ALTER TABLE orders ADD COLUMN IF NOT EXISTS tax_amount DECIMAL(10,2) NOT NULL DEFAULT 0;
ALTER TABLE orders ADD COLUMN IF NOT EXISTS shipping_amount DECIMAL(10,2) NOT NULL DEFAULT 0;
ALTER TABLE orders ADD COLUMN IF NOT EXISTS shipped_at TIMESTAMP;
ALTER TABLE orders ADD COLUMN IF NOT EXISTS delivered_at TIMESTAMP;
ALTER TABLE orders ADD COLUMN IF NOT EXISTS cancelled_at TIMESTAMP;
ALTER TABLE orders ADD COLUMN IF NOT EXISTS referral_code VARCHAR(20);
ALTER TABLE orders ADD COLUMN IF NOT EXISTS purchase_mode VARCHAR(50) DEFAULT 'ONLINE';

-- ------------------------------------------------------------------------------
-- 7. Reward and Referral Tables
-- ------------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS coin_balances (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL UNIQUE REFERENCES users(id) ON DELETE CASCADE,
    balance BIGINT NOT NULL DEFAULT 0,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_coin_balance_user ON coin_balances(user_id);

CREATE TABLE IF NOT EXISTS coin_transactions (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    source_user_id BIGINT REFERENCES users(id) ON DELETE SET NULL,
    type VARCHAR(20) NOT NULL CHECK (type IN ('EARN', 'SPEND', 'ADJUST')),
    amount BIGINT NOT NULL,
    level INTEGER,
    reason VARCHAR(100),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_tx_user ON coin_transactions(user_id);
CREATE INDEX IF NOT EXISTS idx_tx_source_user ON coin_transactions(source_user_id);
CREATE INDEX IF NOT EXISTS idx_tx_created_at ON coin_transactions(created_at);

CREATE TABLE IF NOT EXISTS coupon_transactions (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    source_user_id BIGINT REFERENCES users(id) ON DELETE SET NULL,
    level INTEGER,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_coupon_user ON coupon_transactions(user_id);
CREATE INDEX IF NOT EXISTS idx_coupon_source ON coupon_transactions(source_user_id);

CREATE TABLE IF NOT EXISTS referral_relations (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL UNIQUE REFERENCES users(id) ON DELETE CASCADE,
    parent_id BIGINT REFERENCES users(id) ON DELETE SET NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_ref_user ON referral_relations(user_id);
CREATE INDEX IF NOT EXISTS idx_ref_parent ON referral_relations(parent_id);
