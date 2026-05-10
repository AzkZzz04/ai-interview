ALTER TABLE ai_interview_app.resumes
ADD COLUMN IF NOT EXISTS detected_content_type varchar(120);

ALTER TABLE ai_interview_app.resumes
ADD COLUMN IF NOT EXISTS size_bytes bigint NOT NULL DEFAULT 0;

ALTER TABLE ai_interview_app.resumes
ADD COLUMN IF NOT EXISTS updated_at timestamptz NOT NULL DEFAULT now();

ALTER TABLE ai_interview_app.resume_assessments
ADD COLUMN IF NOT EXISTS prompt_name varchar(80) NOT NULL DEFAULT 'rag-grounded';

ALTER TABLE ai_interview_app.interview_answers
ADD COLUMN IF NOT EXISTS prompt_name varchar(80);

ALTER TABLE ai_interview_app.resume_assessments
DROP COLUMN IF EXISTS prompt_version;

ALTER TABLE ai_interview_app.interview_answers
DROP COLUMN IF EXISTS prompt_version;

DROP INDEX IF EXISTS ai_interview_app.idx_resume_chunks_embedding;
DROP INDEX IF EXISTS ai_interview_app.idx_job_description_chunks_embedding;
DROP INDEX IF EXISTS ai_interview_app.idx_question_embeddings_embedding;
DROP INDEX IF EXISTS ai_interview_app.idx_answer_embeddings_embedding;

ALTER TABLE ai_interview_app.resume_chunks
ALTER COLUMN embedding TYPE vector(1024) USING NULL;

ALTER TABLE ai_interview_app.job_description_chunks
ALTER COLUMN embedding TYPE vector(1024) USING NULL;

ALTER TABLE ai_interview_app.question_embeddings
ALTER COLUMN embedding TYPE vector(1024) USING NULL;

ALTER TABLE ai_interview_app.answer_embeddings
ALTER COLUMN embedding TYPE vector(1024) USING NULL;

CREATE INDEX IF NOT EXISTS idx_resume_chunks_embedding
ON ai_interview_app.resume_chunks
USING hnsw (embedding vector_cosine_ops);

CREATE INDEX IF NOT EXISTS idx_job_description_chunks_embedding
ON ai_interview_app.job_description_chunks
USING hnsw (embedding vector_cosine_ops);

CREATE INDEX IF NOT EXISTS idx_question_embeddings_embedding
ON ai_interview_app.question_embeddings
USING hnsw (embedding vector_cosine_ops);

CREATE INDEX IF NOT EXISTS idx_answer_embeddings_embedding
ON ai_interview_app.answer_embeddings
USING hnsw (embedding vector_cosine_ops);
