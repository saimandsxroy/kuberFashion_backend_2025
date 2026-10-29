-- Migration V11: Add pgvector extension, product embedding vector column, and HNSW cosine index
CREATE EXTENSION IF NOT EXISTS vector;

-- Add embedding vector(768) column for Gemini embedding models
ALTER TABLE products ADD COLUMN IF NOT EXISTS embedding vector(768);

-- Add HNSW cosine distance index for ultra-fast vector nearest-neighbor search
CREATE INDEX IF NOT EXISTS idx_products_embedding_hnsw 
ON products USING hnsw (embedding vector_cosine_ops);
