# AI Interview Project Design

## Product Goal

Build a text-first resume and interview preparation application for technical job seekers.
Users upload a resume, optionally provide a job description, receive a structured resume strength assessment, then answer generated interview questions and get feedback.

The first version should avoid video, audio, live coding, and complex collaboration features. It should focus on reliable text extraction, useful scoring, role-aware question generation, and clear feedback loops.

## Version 1 Scope

### In Scope

- Resume upload as PDF, DOCX, TXT, or pasted plain text.
- Text extraction and normalization.
- Optional job description input per assessment.
- Resume scoring for technical roles.
- Resume feedback grouped by category.
- RAG-grounded AI-generated interview questions based on resume, target role, and optional job description.
- Text answers from users.
- AI feedback on answer quality.
- Assessment and interview history.
- Basic admin-safe observability: logs, metrics, traces, and error reporting.

### Out of Scope

- Video interview recording.
- Voice transcription.
- Real-time interviewer avatar.
- Browser-based coding IDE.
- Payments and subscription billing.
- Recruiter-facing workflow.
- Multi-user organization accounts.
- Authentication and user accounts in the first prototype.

## Recommended Tech Stack

### Frontend

- Next.js with App Router.
- TypeScript.
- Tailwind CSS for fast UI delivery.
- shadcn/ui or Radix UI primitives for accessible components.
- TanStack Query for API state and server mutation handling.
- React Hook Form with Zod for form validation.
- Playwright for end-to-end smoke tests.
- Vitest and React Testing Library for component-level tests.

### Backend

- Java 21.
- Spring Boot.
- Spring Web MVC for REST APIs.
- Spring Data JPA for transactional domain data.
- Flyway for database migrations.
- Spring AI for Gemini chat, Gemini embeddings, and pgvector integration.
- Apache Tika for resume text extraction from PDF/DOCX/TXT.
- Bean Validation for request validation.
- MapStruct if DTO mapping grows beyond simple hand-written mappers.
- Testcontainers for PostgreSQL, pgvector, Redis, and object storage integration tests.

### Data and Infrastructure

- PostgreSQL as primary relational database.
- pgvector for RAG retrieval across resume, job description, question, and answer embeddings.
- Redis for rate limiting, short-lived workflow state, and background job locks.
- RustFS or MinIO-compatible S3 storage later for original resume files and generated artifacts.
- Docker Compose for local development.
- OpenTelemetry for traces and metrics.
- Prometheus and Grafana later, once the service has meaningful traffic.
- Sentry or equivalent error monitoring for frontend and backend.

### AI Provider

Gemini is the V1 AI provider.

- Chat model: Google GenAI through Spring AI, starting with `gemini-2.5-flash` for fast structured generation.
- Thinking config: set `GEMINI_THINKING_BUDGET=0` for Gemini 2.5 Flash in the first text-only workflow to reduce latency.
- Embedding model: Google GenAI text embeddings, starting with `text-embedding-004` at 768 dimensions.
- Authentication: `GEMINI_API_KEY` for local development through the Gemini Developer API; Vertex AI credentials can be added later for production.
- Backend config: keep chat and embedding models disabled by default for local startup, then enable with `AI_CHAT_MODEL=google-genai` and `AI_EMBEDDING_MODEL=google-genai`.

Abstract Gemini usage behind backend services even though it is the chosen provider. This keeps prompts, schema validation, retries, and future model swaps isolated from controllers and domain services.

## Target Repository Structure

The current repository is a Spring Boot root project. For a full-stack product, use a monorepo layout:

```text
ai_interview/
  apps/
    api/                         # Spring Boot backend
      src/main/java/dev/jiaming/ai_interview/
      src/main/resources/
      src/test/java/dev/jiaming/ai_interview/
      build.gradle.kts
    web/                         # Next.js frontend
      app/
      components/
      features/
      lib/
      public/
      package.json
  packages/
    contracts/                   # Optional generated OpenAPI client/types
  infra/
    docker-compose.yml
    postgres/
      init-pgvector.sql
    rustfs/
    observability/
  docs/
    project-design.md
    api.md
    prompts.md
  .github/workflows/
  settings.gradle.kts
  README.md
```

For the next implementation step, either keep the Spring Boot app at the repository root temporarily or move it under `apps/api`. Moving early is cleaner if the Next.js frontend will be added immediately.

## Backend Package Structure

Use package-by-feature with a small shared kernel:

```text
dev.jiaming.ai_interview
  resume/
    controller/
    service/
    domain/
    repository/
    parser/
  assessment/
    controller/
    service/
    domain/
    repository/
    scoring/
  interview/
    controller/
    service/
    domain/
    repository/
  ai/
    llm/
    embedding/
    prompt/
    rag/
    safety/
  storage/
    object/
    virus/
  common/
    config/
    error/
    security/
    web/
    persistence/
```

Important boundary: controllers should only handle HTTP concerns, services own workflows, domain objects own invariants, repositories own persistence, and `ai/` owns model-specific integration details.

## Core Domain Model

The prototype is single-user and single-resume. API requests are unauthenticated and operate on the latest uploaded resume in memory. Multi-user ownership can be added later after the resume and interview workflow is working end to end.

### Resume

- `id`
- `original_filename`
- `content_type`
- `raw_text`
- `normalized_text`
- `detected_role`
- `detected_seniority`
- `parsed_skills`
- `created_at`

### JobDescription

- `id`
- `title`
- `company`
- `raw_text`
- `normalized_text`
- `parsed_requirements`
- `created_at`

### ResumeAssessment

- `id`
- `resume_id`
- `job_description_id`
- `overall_score`
- `technical_depth_score`
- `impact_score`
- `clarity_score`
- `relevance_score`
- `ats_score`
- `strengths_json`
- `weaknesses_json`
- `recommendations_json`
- `model_name`
- `prompt_version`
- `created_at`

### InterviewSession

- `id`
- `resume_id`
- `job_description_id`
- `assessment_id`
- `target_role`
- `seniority`
- `status`
- `created_at`
- `completed_at`

### InterviewQuestion

- `id`
- `session_id`
- `question_text`
- `category`
- `difficulty`
- `expected_signals_json`
- `source_context_json`
- `order_index`

### InterviewAnswer

- `id`
- `question_id`
- `answer_text`
- `score`
- `feedback_json`
- `created_at`

### Embedding Tables

Use pgvector for RAG retrieval, semantic matching, and deduplication:

- `resume_chunks`: resume section chunks and embeddings.
- `job_description_chunks`: job requirement chunks and embeddings.
- `question_embeddings`: generated question embeddings for deduplication.
- `answer_embeddings`: optional in V1, useful for analytics and future coaching.

## RAG Design

RAG is required in V1. The application should not ask Gemini to evaluate a whole unbounded resume and job description directly when a scoped retrieval step can provide better context.

### Indexing Pipeline

1. Extract resume text with Apache Tika.
2. Normalize whitespace, remove repeated headers/footers, and preserve section labels.
3. Split into chunks by resume section first, then by token/character limits.
4. Attach metadata to each chunk:
   - `userId`
   - `resumeId`
   - `sourceType`
   - `section`
   - `chunkIndex`
   - `detectedRole`
   - `detectedSeniority`
5. Generate embeddings with Google GenAI text embeddings.
6. Store chunk text, metadata, and vector in PostgreSQL/pgvector.

Job descriptions follow the same process, with metadata for title, company, requirement category, and seniority signal.

### Retrieval Pipeline

For resume assessment, retrieve context using multiple focused queries:

- technical depth and systems ownership
- measurable impact
- target role alignment
- job description must-have requirements
- missing or weak experience signals

For interview question generation, retrieve:

- strongest resume projects
- weakest resume areas
- most relevant job description requirements
- prior generated questions to avoid duplicates

For answer feedback, retrieve:

- the source resume/project context for the question
- expected answer signals
- the job description requirement that motivated the question

### Prompt Grounding Rules

- Gemini should receive only retrieved context snippets, user-visible question/answer text, scoring rubric, and schema instructions.
- Prompts must tell Gemini to avoid inventing experience not present in the retrieved context.
- Every response should include `sourceContextIds` where possible so the UI can explain which resume or job description snippets influenced the result.
- If retrieval confidence is weak, the output should say what information is missing instead of guessing.

### Retrieval Defaults

- Embedding dimensions: `768`.
- Similarity metric: cosine distance.
- Default top K: `8`.
- Store enough metadata to filter by `userId`, `resumeId`, `jobDescriptionId`, and `sourceType`.
- Use separate domain chunk tables for application-owned queries and Spring AI `vector_store` for framework-backed retrieval experiments.

## Resume Scoring Design

Score resumes on dimensions that matter for technical hiring:

- `technical_depth`: specificity of technologies, systems, complexity, and ownership.
- `impact`: quantified results, business outcomes, scale, latency, revenue, adoption, reliability.
- `clarity`: readability, concise bullets, action verbs, structure.
- `relevance`: match against target role and optional job description.
- `ats`: formatting, section naming, keyword coverage, parseability.

The API should return both numeric scores and actionable feedback. Avoid pretending the score is absolute; it is a decision-support signal.

Example response shape:

```json
{
  "overallScore": 78,
  "scores": {
    "technicalDepth": 82,
    "impact": 70,
    "clarity": 76,
    "relevance": 84,
    "ats": 79
  },
  "strengths": ["Strong backend systems experience"],
  "weaknesses": ["Several bullets lack measurable impact"],
  "recommendations": [
    {
      "section": "Experience",
      "priority": "high",
      "message": "Add scale, latency, traffic, cost, or reliability metrics to backend project bullets."
    }
  ]
}
```

## Interview Generation Flow

1. User uploads resume.
2. Backend extracts text with Apache Tika.
3. Backend normalizes text and detects likely role/seniority.
4. Backend chunks resume and stores embeddings in pgvector.
5. User optionally adds job description.
6. Backend parses and embeds job description.
7. Backend retrieves relevant resume and job description chunks from pgvector.
8. Backend creates a Gemini-powered resume assessment using structured AI output grounded by retrieved chunks.
9. Backend generates interview questions from:
   - retrieved resume facts,
   - retrieved job requirements,
   - weak resume areas,
   - target role and seniority,
   - previous generated questions for deduplication.
10. User answers questions.
11. Backend retrieves the question context and evaluates answer quality with Gemini.

## Interview Question Categories

- Resume deep dive.
- Technical fundamentals.
- System design.
- Project architecture.
- Debugging and incident response.
- Collaboration and leadership.
- Behavioral examples.
- Role-specific tooling and domain knowledge.

For V1, generate 8 to 12 questions per session:

- 3 resume-specific questions.
- 2 technical fundamentals questions.
- 2 system/project design questions.
- 1 debugging or production-readiness question.
- 1 behavioral question.
- 1 to 3 job-description-specific questions when a job description exists.

## Answer Feedback Rubric

Each answer should receive:

- `score`: 0 to 100.
- `summary`: short direct evaluation.
- `strengths`: what the answer did well.
- `gaps`: missing details or weak reasoning.
- `betterAnswerOutline`: concise improved answer structure.
- `followUpQuestion`: one realistic interviewer follow-up.

The backend should enforce JSON schema output from the model and retry once with a repair prompt if parsing fails.

## REST API Surface

The current prototype is no-auth, single-resume, and does not persist assessments or interview sessions yet.

### Resumes

- `POST /api/resumes` multipart upload for PDF, DOC, DOCX, TXT, or Markdown.
- `GET /api/resumes/current`

### Assessments

- `POST /api/assessments`

Request includes resume text or uses the latest uploaded resume, optional job description, target role, and seniority.

### Interview Practice

- `POST /api/interview/questions`
- `POST /api/interview/feedback`

Questions are generated from the resume/job context. Feedback scores one text answer against the active question and expected signals.

### Future Persistent APIs

These APIs should be added when database-backed history is introduced:

#### Job Descriptions

- `POST /api/job-descriptions`
- `GET /api/job-descriptions`
- `GET /api/job-descriptions/{jobDescriptionId}`
- `DELETE /api/job-descriptions/{jobDescriptionId}`

#### Assessments

- `GET /api/assessments`
- `GET /api/assessments/{assessmentId}`

#### Interview Sessions

- `POST /api/interview-sessions`
- `GET /api/interview-sessions`
- `GET /api/interview-sessions/{sessionId}`
- `POST /api/interview-sessions/{sessionId}/questions/{questionId}/answers`
- `GET /api/interview-sessions/{sessionId}/summary`

## Frontend Structure

```text
apps/web/
  app/
    dashboard/
    resumes/
      page.tsx
      [resumeId]/
    assessments/
      [assessmentId]/
    interviews/
      [sessionId]/
    layout.tsx
    page.tsx
  components/
    ui/
    layout/
  features/
    resume-upload/
    resume-assessment/
    job-description/
    interview-session/
  lib/
    api-client.ts
    query-client.ts
    validation/
    formatters/
```

First screen should be the working dashboard, not a marketing page. It should show the latest uploaded resume, assessment score, active interview session, and a clear upload action.

## V1 User Flow

1. Upload resume or paste resume text.
2. Optionally paste job description.
3. Start assessment.
4. Review score and recommendations.
5. Generate interview session.
6. Answer questions one by one.
7. Review feedback and session summary.
8. Re-upload revised resume and compare scores.

## PostgreSQL and pgvector Notes

Enable pgvector:

```sql
CREATE EXTENSION IF NOT EXISTS vector;
```

Use vector dimensions matching the selected embedding model. V1 uses Google GenAI `text-embedding-004` with 768 dimensions, but this must remain configurable because embedding providers and model options vary.

Recommended vector indexes:

```sql
CREATE INDEX idx_resume_chunks_embedding
ON resume_chunks
USING hnsw (embedding vector_cosine_ops);

CREATE INDEX idx_job_description_chunks_embedding
ON job_description_chunks
USING hnsw (embedding vector_cosine_ops);
```

## Redis Usage

- Rate limit AI-heavy endpoints per client/session.
- Store idempotency tokens for upload and assessment requests.
- Cache short-lived assessment status.
- Lock background processing jobs.

Do not use Redis as the source of truth for assessments, answers, or user history.

## Object Storage Usage

Object storage is not required for the first prototype. The current upload API extracts text from the uploaded file and keeps only the latest resume result in memory.

Use RustFS or MinIO-compatible S3 APIs later for:

- Original uploaded resumes.
- Extracted text snapshots if needed.
- Generated downloadable reports later.

Store only object keys in PostgreSQL. Keep object storage private and serve downloads through signed URLs or backend proxy endpoints.

## Background Processing

V1 can process synchronously for a small prototype, but the backend should be designed around jobs:

- `resume_text_extraction`
- `resume_embedding`
- `job_description_embedding`
- `resume_assessment`
- `interview_question_generation`
- `answer_feedback_generation`

Start with Spring `@Async` or a simple database-backed job table. Add a queue only after there is real need. Redis can provide locks, but PostgreSQL should keep durable job state.

For the current prototype, resume upload and text extraction run synchronously in the request and store only the latest processed resume in memory.

## Security and Privacy

- Store credentials with strong password hashing such as Argon2 or BCrypt.
- Validate upload file type and size.
- Scan uploaded files before processing if the app becomes public.
- Never expose raw model prompts to the frontend.
- Log request ids, not resume contents.
- Redact PII from logs.
- Add authentication and per-user authorization checks only when multi-user accounts are introduced.
- Encrypt secrets through environment variables or a secrets manager.

## Prompt and AI Output Management

Keep prompts in versioned backend files or database records:

```text
src/main/resources/prompts/
  resume-assessment-v1.md
  interview-question-generation-v1.md
  answer-feedback-v1.md
```

Every AI-generated persisted record should store:

- `model_name`
- `prompt_version`
- `input_hash`
- `source_context_ids`
- `created_at`

This makes evaluations and prompt migrations possible.

## Implementation Milestones

### Milestone 1: Project Foundation

- Restructure as monorepo or consciously keep backend at root.
- Add Next.js frontend.
- Add Docker Compose for PostgreSQL with pgvector, Redis, and RustFS.
- Add Flyway.
- Add basic health endpoint.

### Milestone 2: Resume Upload

- Implement resume upload and text extraction.
- Keep the latest extracted resume in memory for the prototype.
- Defer object storage and database persistence.

### Milestone 3: Assessment

- Add job description input.
- Add resume and job description chunking.
- Store embeddings in pgvector.
- Retrieve resume and job description context with RAG.
- Generate structured Gemini resume assessment.
- Render assessment results in frontend.

### Milestone 4: Interview Sessions

- Generate RAG-grounded Gemini question sets.
- Let users answer questions.
- Generate Gemini answer feedback grounded by the original question context.
- Show session summary.

### Milestone 5: Hardening

- Add rate limits.
- Add retry and schema-repair logic for AI calls.
- Add observability.
- Add integration tests with Testcontainers.
- Add frontend end-to-end smoke tests.

## Design Decisions

- Keep AI orchestration in the backend so prompts, model keys, and scoring logic are not exposed to the browser.
- Use Gemini as the V1 AI provider for chat and embeddings.
- Use PostgreSQL as the durable source of truth and pgvector for RAG semantic retrieval.
- Use Redis only for ephemeral workflow concerns.
- Defer object storage in the prototype; when persistence is added, store uploaded files in object storage rather than PostgreSQL.
- Treat every AI response as untrusted data until it passes schema validation.
- Keep the first version text-only to reduce product and infrastructure complexity.
