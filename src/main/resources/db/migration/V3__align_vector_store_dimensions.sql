DROP INDEX IF EXISTS idx_vector_store_embedding;

TRUNCATE TABLE vector_store;

ALTER TABLE vector_store
ALTER COLUMN embedding TYPE vector(1024);

CREATE INDEX IF NOT EXISTS idx_vector_store_embedding
ON vector_store
USING hnsw (embedding vector_cosine_ops);
