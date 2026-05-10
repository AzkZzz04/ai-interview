CREATE EXTENSION IF NOT EXISTS vector;
CREATE EXTENSION IF NOT EXISTS pgcrypto;
CREATE EXTENSION IF NOT EXISTS hstore;

CREATE SCHEMA IF NOT EXISTS ai_interview_app;

CREATE TABLE IF NOT EXISTS ai_interview_app.app_users (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    email varchar(320) NOT NULL UNIQUE,
    password_hash varchar(255),
    display_name varchar(120),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS ai_interview_app.resumes (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id uuid NOT NULL REFERENCES ai_interview_app.app_users(id) ON DELETE CASCADE,
    original_filename varchar(255),
    content_type varchar(120),
    detected_content_type varchar(120),
    size_bytes bigint NOT NULL DEFAULT 0,
    storage_key varchar(512),
    raw_text text,
    normalized_text text,
    detected_role varchar(160),
    detected_seniority varchar(80),
    parsed_skills jsonb NOT NULL DEFAULT '[]'::jsonb,
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS ai_interview_app.job_descriptions (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id uuid NOT NULL REFERENCES ai_interview_app.app_users(id) ON DELETE CASCADE,
    title varchar(180),
    company varchar(180),
    raw_text text NOT NULL,
    normalized_text text,
    parsed_requirements jsonb NOT NULL DEFAULT '[]'::jsonb,
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS ai_interview_app.resume_assessments (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id uuid NOT NULL REFERENCES ai_interview_app.app_users(id) ON DELETE CASCADE,
    resume_id uuid NOT NULL REFERENCES ai_interview_app.resumes(id) ON DELETE CASCADE,
    job_description_id uuid REFERENCES ai_interview_app.job_descriptions(id) ON DELETE SET NULL,
    overall_score integer NOT NULL CHECK (overall_score BETWEEN 0 AND 100),
    technical_depth_score integer NOT NULL CHECK (technical_depth_score BETWEEN 0 AND 100),
    impact_score integer NOT NULL CHECK (impact_score BETWEEN 0 AND 100),
    clarity_score integer NOT NULL CHECK (clarity_score BETWEEN 0 AND 100),
    relevance_score integer NOT NULL CHECK (relevance_score BETWEEN 0 AND 100),
    ats_score integer NOT NULL CHECK (ats_score BETWEEN 0 AND 100),
    strengths jsonb NOT NULL DEFAULT '[]'::jsonb,
    weaknesses jsonb NOT NULL DEFAULT '[]'::jsonb,
    recommendations jsonb NOT NULL DEFAULT '[]'::jsonb,
    model_name varchar(120) NOT NULL,
    prompt_name varchar(80) NOT NULL,
    input_hash varchar(128) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS ai_interview_app.interview_sessions (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id uuid NOT NULL REFERENCES ai_interview_app.app_users(id) ON DELETE CASCADE,
    resume_id uuid NOT NULL REFERENCES ai_interview_app.resumes(id) ON DELETE CASCADE,
    job_description_id uuid REFERENCES ai_interview_app.job_descriptions(id) ON DELETE SET NULL,
    assessment_id uuid REFERENCES ai_interview_app.resume_assessments(id) ON DELETE SET NULL,
    target_role varchar(180),
    seniority varchar(80),
    status varchar(40) NOT NULL DEFAULT 'CREATED',
    created_at timestamptz NOT NULL DEFAULT now(),
    completed_at timestamptz
);

CREATE TABLE IF NOT EXISTS ai_interview_app.interview_questions (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    session_id uuid NOT NULL REFERENCES ai_interview_app.interview_sessions(id) ON DELETE CASCADE,
    question_text text NOT NULL,
    category varchar(80) NOT NULL,
    difficulty varchar(40) NOT NULL,
    expected_signals jsonb NOT NULL DEFAULT '[]'::jsonb,
    source_context jsonb NOT NULL DEFAULT '[]'::jsonb,
    order_index integer NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (session_id, order_index)
);

CREATE TABLE IF NOT EXISTS ai_interview_app.interview_answers (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    question_id uuid NOT NULL REFERENCES ai_interview_app.interview_questions(id) ON DELETE CASCADE,
    answer_text text NOT NULL,
    score integer CHECK (score BETWEEN 0 AND 100),
    feedback jsonb NOT NULL DEFAULT '{}'::jsonb,
    model_name varchar(120),
    prompt_name varchar(80),
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS ai_interview_app.resume_chunks (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    resume_id uuid NOT NULL REFERENCES ai_interview_app.resumes(id) ON DELETE CASCADE,
    chunk_index integer NOT NULL,
    section varchar(120),
    content text NOT NULL,
    metadata jsonb NOT NULL DEFAULT '{}'::jsonb,
    embedding vector(1024),
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (resume_id, chunk_index)
);

CREATE TABLE IF NOT EXISTS ai_interview_app.job_description_chunks (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    job_description_id uuid NOT NULL REFERENCES ai_interview_app.job_descriptions(id) ON DELETE CASCADE,
    chunk_index integer NOT NULL,
    section varchar(120),
    content text NOT NULL,
    metadata jsonb NOT NULL DEFAULT '{}'::jsonb,
    embedding vector(1024),
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (job_description_id, chunk_index)
);

CREATE TABLE IF NOT EXISTS ai_interview_app.question_embeddings (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    question_id uuid NOT NULL REFERENCES ai_interview_app.interview_questions(id) ON DELETE CASCADE,
    content text NOT NULL,
    metadata jsonb NOT NULL DEFAULT '{}'::jsonb,
    embedding vector(1024),
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS ai_interview_app.answer_embeddings (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    answer_id uuid NOT NULL REFERENCES ai_interview_app.interview_answers(id) ON DELETE CASCADE,
    content text NOT NULL,
    metadata jsonb NOT NULL DEFAULT '{}'::jsonb,
    embedding vector(1024),
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS vector_store (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    content text,
    metadata json,
    embedding vector(1024)
);

CREATE TABLE IF NOT EXISTS ai_interview_app.background_jobs (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    job_type varchar(80) NOT NULL,
    resource_type varchar(80) NOT NULL,
    resource_id uuid NOT NULL,
    status varchar(40) NOT NULL DEFAULT 'PENDING',
    attempts integer NOT NULL DEFAULT 0,
    last_error text,
    run_after timestamptz NOT NULL DEFAULT now(),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_resumes_user_id ON ai_interview_app.resumes(user_id);
CREATE INDEX IF NOT EXISTS idx_job_descriptions_user_id ON ai_interview_app.job_descriptions(user_id);
CREATE INDEX IF NOT EXISTS idx_resume_assessments_user_id ON ai_interview_app.resume_assessments(user_id);
CREATE INDEX IF NOT EXISTS idx_interview_sessions_user_id ON ai_interview_app.interview_sessions(user_id);
CREATE INDEX IF NOT EXISTS idx_interview_questions_session_id ON ai_interview_app.interview_questions(session_id);
CREATE INDEX IF NOT EXISTS idx_interview_answers_question_id ON ai_interview_app.interview_answers(question_id);
CREATE INDEX IF NOT EXISTS idx_background_jobs_status_run_after ON ai_interview_app.background_jobs(status, run_after);

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

CREATE INDEX IF NOT EXISTS idx_vector_store_embedding
ON vector_store
USING hnsw (embedding vector_cosine_ops);
