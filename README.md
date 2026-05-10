# AI Interview

Text-first resume assessment and interview practice application for technical job seekers.

The product lets users upload a resume, optionally provide a job description, receive a structured resume strength assessment, generate tailored interview questions, answer them, and get feedback.

## Current State

This repository currently contains the Spring Boot backend, the first local infrastructure setup, and a Next.js frontend prototype. The backend prototype is no-auth and single-resume for now.

- Spring Boot API backend.
- Next.js frontend.
- PostgreSQL with pgvector.
- Redis.
- LocalStack S3-compatible object storage.

See [docs/project-design.md](docs/project-design.md) for the proposed architecture, domain model, API surface, repository structure, and implementation milestones.

## Local Infrastructure

Create local environment config:

```bash
cp .env.example .env
```

Set `DATABASE_URL`, `DATABASE_USERNAME`, and `DATABASE_PASSWORD` in `.env` to match your existing PostgreSQL/pgvector instance. The local default database is `interview_guide` on `localhost:5432`.

Start the local services:

```bash
docker compose up -d
```

This starts:

- Redis on `localhost:6380` by default when managed by Compose
- LocalStack S3 on `localhost:4566`

The backend defaults to PostgreSQL on `localhost:5432` because this workspace already has pgvector there. If you need Compose to manage a separate PostgreSQL instance later, run:

```bash
docker compose --profile managed-postgres up -d
```

The managed PostgreSQL service binds to `localhost:55432` by default, or to `POSTGRES_PORT` if set.

The backend defaults to Redis on `localhost:6379`. The bundled Compose Redis service publishes container port `6379` to `localhost:6380` by default, so set `REDIS_PORT=6380` only if you are using that Compose-managed Redis instance. If your Docker Redis is already mapped to host port `6379`, leave `REDIS_PORT=6379`.

Redis is used for operational guardrails only: per-client limits on expensive upload/AI requests and short-lived duplicate in-flight locks around Gemini calls. PostgreSQL remains the durable source of truth.

Mutation endpoints also support an optional `Idempotency-Key` header. When present, Redis stores the successful response for the same client, endpoint, and request fingerprint for `REDIS_IDEMPOTENCY_TTL_SECONDS` seconds. Retrying the same request with the same key returns the cached response; reusing the key with a different payload returns `409`.

The backend stores original uploaded resume files in S3-compatible storage and defaults to LocalStack:

```properties
S3_ENDPOINT=http://localhost:4566
S3_REGION=us-east-1
S3_BUCKET=ai-interview
S3_ACCESS_KEY=test
S3_SECRET_KEY=test
```

Application-owned persistence tables are created under the `ai_interview_app` schema to avoid collisions with existing local tables in `public`.

Gemini calls use the Gemini Developer API key from local `.env`. To enable Gemini chat and embeddings for development, update `.env`:

```properties
GEMINI_API_KEY=your-api-key
AI_CHAT_MODEL=google-genai
AI_EMBEDDING_MODEL=google-genai
GEMINI_CHAT_MODEL=gemini-2.5-flash
GEMINI_REQUEST_TIMEOUT_SECONDS=90
GEMINI_MAX_OUTPUT_TOKENS=2048
GEMINI_THINKING_BUDGET=0
GEMINI_EMBEDDING_MODEL=gemini-embedding-001
GEMINI_EMBEDDING_DIMENSIONS=1024
RAG_EMBEDDING_DIMENSIONS=1024
```

Keep `.env` local and untracked. Do not put real API keys in `.env.example`.

## First Implementation Milestones

1. Set up project foundation and local infrastructure.
2. Add resume upload and text extraction.
3. Add resume assessment with optional job description matching.
4. Add interview question generation and answer feedback.
5. Harden with rate limits, tests, observability, and schema validation.

## Backend API

The first no-auth API persists uploaded resumes, extracted chunks, generated assessments, interview sessions, questions, and answer feedback for a single local user:

- `POST /api/resumes` with multipart field `file`
- `GET /api/resumes/current`
- `POST /api/assessments`
- `POST /api/interview/questions`
- `POST /api/interview/feedback`

Supported upload formats: PDF, DOC, DOCX, TXT, and Markdown.

The AI endpoints call Gemini and return typed JSON for the frontend. If Gemini is unavailable or the configured key/model has no quota, the frontend falls back to local draft scoring so the workflow remains usable during development.
