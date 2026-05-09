CREATE EXTENSION IF NOT EXISTS vector;
CREATE EXTENSION IF NOT EXISTS hstore;
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

CREATE TABLE app_users (
    id uuid PRIMARY KEY DEFAULT uuid_generate_v4(),
    email varchar(320) NOT NULL UNIQUE,
    password_hash varchar(255),
    display_name varchar(120),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE resumes (
    id uuid PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id uuid NOT NULL REFERENCES app_users(id) ON DELETE CASCADE,
    original_filename varchar(255),
    content_type varchar(120),
    storage_key varchar(512),
    raw_text text,
    normalized_text text,
    detected_role varchar(160),
    detected_seniority varchar(80),
    parsed_skills jsonb NOT NULL DEFAULT '[]'::jsonb,
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE job_descriptions (
    id uuid PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id uuid NOT NULL REFERENCES app_users(id) ON DELETE CASCADE,
    title varchar(180),
    company varchar(180),
    raw_text text NOT NULL,
    normalized_text text,
    parsed_requirements jsonb NOT NULL DEFAULT '[]'::jsonb,
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE resume_assessments (
    id uuid PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id uuid NOT NULL REFERENCES app_users(id) ON DELETE CASCADE,
    resume_id uuid NOT NULL REFERENCES resumes(id) ON DELETE CASCADE,
    job_description_id uuid REFERENCES job_descriptions(id) ON DELETE SET NULL,
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
    prompt_version varchar(80) NOT NULL,
    input_hash varchar(128) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE interview_sessions (
    id uuid PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id uuid NOT NULL REFERENCES app_users(id) ON DELETE CASCADE,
    resume_id uuid NOT NULL REFERENCES resumes(id) ON DELETE CASCADE,
    job_description_id uuid REFERENCES job_descriptions(id) ON DELETE SET NULL,
    assessment_id uuid REFERENCES resume_assessments(id) ON DELETE SET NULL,
    target_role varchar(180),
    seniority varchar(80),
    status varchar(40) NOT NULL DEFAULT 'CREATED',
    created_at timestamptz NOT NULL DEFAULT now(),
    completed_at timestamptz
);

CREATE TABLE interview_questions (
    id uuid PRIMARY KEY DEFAULT uuid_generate_v4(),
    session_id uuid NOT NULL REFERENCES interview_sessions(id) ON DELETE CASCADE,
    question_text text NOT NULL,
    category varchar(80) NOT NULL,
    difficulty varchar(40) NOT NULL,
    expected_signals jsonb NOT NULL DEFAULT '[]'::jsonb,
    source_context jsonb NOT NULL DEFAULT '[]'::jsonb,
    order_index integer NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (session_id, order_index)
);

CREATE TABLE interview_answers (
    id uuid PRIMARY KEY DEFAULT uuid_generate_v4(),
    question_id uuid NOT NULL REFERENCES interview_questions(id) ON DELETE CASCADE,
    answer_text text NOT NULL,
    score integer CHECK (score BETWEEN 0 AND 100),
    feedback jsonb NOT NULL DEFAULT '{}'::jsonb,
    model_name varchar(120),
    prompt_version varchar(80),
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE resume_chunks (
    id uuid PRIMARY KEY DEFAULT uuid_generate_v4(),
    resume_id uuid NOT NULL REFERENCES resumes(id) ON DELETE CASCADE,
    chunk_index integer NOT NULL,
    section varchar(120),
    content text NOT NULL,
    metadata jsonb NOT NULL DEFAULT '{}'::jsonb,
    embedding vector(768),
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (resume_id, chunk_index)
);

CREATE TABLE job_description_chunks (
    id uuid PRIMARY KEY DEFAULT uuid_generate_v4(),
    job_description_id uuid NOT NULL REFERENCES job_descriptions(id) ON DELETE CASCADE,
    chunk_index integer NOT NULL,
    section varchar(120),
    content text NOT NULL,
    metadata jsonb NOT NULL DEFAULT '{}'::jsonb,
    embedding vector(768),
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (job_description_id, chunk_index)
);

CREATE TABLE question_embeddings (
    id uuid PRIMARY KEY DEFAULT uuid_generate_v4(),
    question_id uuid NOT NULL REFERENCES interview_questions(id) ON DELETE CASCADE,
    content text NOT NULL,
    metadata jsonb NOT NULL DEFAULT '{}'::jsonb,
    embedding vector(768),
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE answer_embeddings (
    id uuid PRIMARY KEY DEFAULT uuid_generate_v4(),
    answer_id uuid NOT NULL REFERENCES interview_answers(id) ON DELETE CASCADE,
    content text NOT NULL,
    metadata jsonb NOT NULL DEFAULT '{}'::jsonb,
    embedding vector(768),
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE vector_store (
    id uuid PRIMARY KEY DEFAULT uuid_generate_v4(),
    content text,
    metadata json,
    embedding vector(768)
);

CREATE TABLE background_jobs (
    id uuid PRIMARY KEY DEFAULT uuid_generate_v4(),
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

CREATE INDEX idx_resumes_user_id ON resumes(user_id);
CREATE INDEX idx_job_descriptions_user_id ON job_descriptions(user_id);
CREATE INDEX idx_resume_assessments_user_id ON resume_assessments(user_id);
CREATE INDEX idx_interview_sessions_user_id ON interview_sessions(user_id);
CREATE INDEX idx_interview_questions_session_id ON interview_questions(session_id);
CREATE INDEX idx_interview_answers_question_id ON interview_answers(question_id);
CREATE INDEX idx_background_jobs_status_run_after ON background_jobs(status, run_after);

CREATE INDEX idx_resume_chunks_embedding
ON resume_chunks
USING hnsw (embedding vector_cosine_ops);

CREATE INDEX idx_job_description_chunks_embedding
ON job_description_chunks
USING hnsw (embedding vector_cosine_ops);

CREATE INDEX idx_question_embeddings_embedding
ON question_embeddings
USING hnsw (embedding vector_cosine_ops);

CREATE INDEX idx_answer_embeddings_embedding
ON answer_embeddings
USING hnsw (embedding vector_cosine_ops);

CREATE INDEX idx_vector_store_embedding
ON vector_store
USING hnsw (embedding vector_cosine_ops);
