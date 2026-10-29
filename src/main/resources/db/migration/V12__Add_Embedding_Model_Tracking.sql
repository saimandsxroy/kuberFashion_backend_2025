-- Migration V12: Add embedding_model tracking column to enforce vector compatibility
ALTER TABLE products ADD COLUMN IF NOT EXISTS embedding_model VARCHAR(100);

-- Index for filtering by model during vector similarity queries
CREATE INDEX IF NOT EXISTS idx_products_embedding_model ON products(embedding_model);
