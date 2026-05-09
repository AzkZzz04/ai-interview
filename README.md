# AI Interview

Text-first resume assessment and interview practice application for technical job seekers.

The product lets users upload a resume, optionally provide a job description, receive a structured resume strength assessment, generate tailored interview questions, answer them, and get feedback.

## Current State

This repository currently contains the Spring Boot backend, the first local infrastructure setup, and a Next.js frontend prototype. The backend prototype is no-auth and single-resume for now.

- Spring Boot API backend.
- Next.js frontend.
- PostgreSQL with pgvector.
- Redis.
- RustFS or another S3-compatible object store.

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
- RustFS S3-compatible storage on `localhost:9000`

The backend defaults to PostgreSQL on `localhost:5432` because this workspace already has pgvector there. If you need Compose to manage a separate PostgreSQL instance later, run:

```bash
docker compose --profile managed-postgres up -d
```

The managed PostgreSQL service binds to `localhost:55432` by default, or to `POSTGRES_PORT` if set.

The backend defaults to Redis on `localhost:6379`. If you want the app to use the Compose-managed Redis instance instead, set `REDIS_PORT=6380` in `.env`.

Gemini calls use the Gemini Developer API key from local `.env`. To enable Gemini chat and embeddings for development, update `.env`:

```properties
GEMINI_API_KEY=your-api-key
AI_CHAT_MODEL=google-genai
AI_EMBEDDING_MODEL=google-genai
GEMINI_CHAT_MODEL=gemini-2.5-flash
GEMINI_REQUEST_TIMEOUT_SECONDS=90
GEMINI_MAX_OUTPUT_TOKENS=2048
GEMINI_THINKING_BUDGET=0
```

Keep `.env` local and untracked. Do not put real API keys in `.env.example`.

## First Implementation Milestones

1. Set up project foundation and local infrastructure.
2. Add resume upload and text extraction.
3. Add resume assessment with optional job description matching.
4. Add interview question generation and answer feedback.
5. Harden with rate limits, tests, observability, and schema validation.

## Backend API

The first no-auth API keeps only the latest uploaded resume in memory:

- `POST /api/resumes` with multipart field `file`
- `GET /api/resumes/current`
- `POST /api/assessments`
- `POST /api/interview/questions`
- `POST /api/interview/feedback`

Supported upload formats: PDF, DOC, DOCX, TXT, and Markdown.

The AI endpoints call Gemini and return typed JSON for the frontend. If Gemini is unavailable or the configured key/model has no quota, the frontend falls back to local draft scoring so the workflow remains usable during development.
