-- =====================================================
-- V9: Add idempotency_key column and unique constraint
-- Description: Enforce database-level order creation idempotency per user
-- =====================================================

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns 
        WHERE table_name = 'orders' 
        AND column_name = 'idempotency_key'
    ) THEN
        ALTER TABLE orders ADD COLUMN idempotency_key VARCHAR(100);
        RAISE NOTICE 'Added idempotency_key column to orders table';
    ELSE
        RAISE NOTICE 'Column idempotency_key already exists in orders table';
    END IF;
END $$;

-- Create partial unique index: permits multiple NULLs (orders created without an idempotency key)
-- while strictly enforcing that non-null keys cannot be duplicated for the same user.
CREATE UNIQUE INDEX IF NOT EXISTS uk_orders_user_idempotency 
ON orders (user_id, idempotency_key) 
WHERE idempotency_key IS NOT NULL;
