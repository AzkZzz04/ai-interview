"use client";

import {
  ArrowRight,
  BarChart3,
  CheckCircle2,
  ClipboardCheck,
  FileText,
  Gauge,
  Loader2,
  MessageSquareText,
  RefreshCw,
  Send,
  Sparkles,
  Upload
} from "lucide-react";
import { ChangeEvent, useEffect, useMemo, useRef, useState } from "react";
import { createAiAnswerFeedback, createAiAssessment, createAiQuestions } from "@/lib/api/ai";
import { getCurrentResume, ResumeUploadResponse, uploadResume } from "@/lib/api/resumes";
import {
  AnswerFeedback,
  Assessment,
  AssessmentScoreKey,
  createAssessment,
  createQuestions,
  InterviewQuestion,
  scoreAnswer
} from "@/lib/mockAssessment";

const scoreLabels: Record<AssessmentScoreKey, string> = {
  technicalDepth: "Technical depth",
  impact: "Impact",
  clarity: "Clarity",
  relevance: "Role relevance",
  ats: "ATS"
};

const starterResume = `EXPERIENCE
Backend Engineer, Example Systems
- Built Spring Boot services for resume processing and interview workflows.
- Improved PostgreSQL query performance for high-volume profile search.
- Added Redis-backed rate limiting and operational dashboards.

SKILLS
Java, Spring Boot, PostgreSQL, Redis, Next.js, TypeScript, observability

EDUCATION
B.S. Computer Science`;

type UploadedResume = {
  name: string;
  size: number;
  extension: string;
  status: "extracting" | "ready" | "error";
  message: string;
};

type ExtractionProgress = {
  startedAt: number;
  percent: number;
  stage: string;
};

type ResumeFileInfo = {
  name: string;
  size: number;
};

export default function Home() {
  const [resumeText, setResumeText] = useState(starterResume);
  const [uploadedResume, setUploadedResume] = useState<UploadedResume | null>(null);
  const [jobDescription, setJobDescription] = useState("");
  const [targetRole, setTargetRole] = useState("Backend Engineer");
  const [seniority, setSeniority] = useState("Mid-level");
  const [assessment, setAssessment] = useState<Assessment | null>(null);
  const [questions, setQuestions] = useState<InterviewQuestion[]>([]);
  const [activeQuestionId, setActiveQuestionId] = useState<string | null>(null);
  const [answer, setAnswer] = useState("");
  const [answerFeedback, setAnswerFeedback] = useState<AnswerFeedback | null>(null);
  const [analysisNotice, setAnalysisNotice] = useState<string | null>(null);
  const [isAnalyzing, setIsAnalyzing] = useState(false);
  const [isSubmittingAnswer, setIsSubmittingAnswer] = useState(false);
  const [isUploadingResume, setIsUploadingResume] = useState(false);
  const [extractionProgress, setExtractionProgress] = useState<ExtractionProgress | null>(null);
  const [elapsedSeconds, setElapsedSeconds] = useState(0);
  const resumeTextareaRef = useRef<HTMLTextAreaElement>(null);

  const activeQuestion = useMemo(
    () => questions.find((question) => question.id === activeQuestionId) ?? questions[0],
    [activeQuestionId, questions]
  );

  useEffect(() => {
    let cancelled = false;

    getCurrentResume()
      .then((currentResume) => {
        if (cancelled || !currentResume) {
          return;
        }

        setResumeText(currentResume.normalizedText);
        setUploadedResume({
          name: currentResume.originalFilename,
          size: currentResume.sizeBytes,
          extension: currentResume.originalFilename.split(".").pop()?.toLowerCase() ?? "",
          status: "ready",
          message: "Loaded latest backend extraction"
        });
        setIsUploadingResume(false);
        setExtractionProgress(null);
      })
      .catch(() => {
        // The app can still work with pasted text if no backend resume is available.
      });

    return () => {
      cancelled = true;
    };
  }, []);

  useEffect(() => {
    if (!isUploadingResume) {
      setElapsedSeconds(0);
      return;
    }

    const intervalId = window.setInterval(() => {
      setExtractionProgress((current) => {
        if (!current) {
          return current;
        }
        const seconds = Math.max(0, Math.floor((Date.now() - current.startedAt) / 1000));
        setElapsedSeconds(seconds);
        return {
          ...current,
          percent: Math.min(88, Math.max(current.percent, 34 + seconds * 6)),
          stage: seconds > 8 ? "Still extracting text. Large or scanned PDFs can take longer." : current.stage
        };
      });
    }, 1000);

    return () => window.clearInterval(intervalId);
  }, [isUploadingResume]);

  useEffect(() => {
    if (!uploadedResume || uploadedResume.status !== "error" || isUploadingResume) {
      return;
    }

    let cancelled = false;
    getCurrentResume()
      .then((currentResume) => {
        if (
          cancelled ||
          !currentResume ||
          currentResume.originalFilename !== uploadedResume.name ||
          currentResume.sizeBytes !== uploadedResume.size
        ) {
          return;
        }

        setResumeText(currentResume.normalizedText);
        setUploadedResume({
          ...uploadedResume,
          status: "ready",
          message: "Backend completed extraction; inserted the latest text below"
        });
        window.requestAnimationFrame(() => {
          resumeTextareaRef.current?.focus();
        });
      })
      .catch(() => {
        // Keep the visible upload error if the recovery check cannot reach the backend.
      });

    return () => {
      cancelled = true;
    };
  }, [isUploadingResume, uploadedResume]);

  useEffect(() => {
    if (!uploadedResume || uploadedResume.status !== "extracting" || !isUploadingResume) {
      return;
    }

    let cancelled = false;
    const intervalId = window.setInterval(() => {
      void getCurrentResume()
        .then((currentResume) => {
          if (
            cancelled ||
            !currentResume ||
            currentResume.originalFilename !== uploadedResume.name ||
            currentResume.sizeBytes !== uploadedResume.size
          ) {
            return;
          }

          applyExtractedResume(
            uploadedResume,
            uploadedResume.extension,
            currentResume,
            "Backend completed extraction; inserted the latest text below"
          );
          setIsUploadingResume(false);
          setExtractionProgress(null);
        })
        .catch(() => {
          // Keep waiting for the active upload request or the next polling tick.
        });
    }, 1_500);

    return () => {
      cancelled = true;
      window.clearInterval(intervalId);
    };
  }, [isUploadingResume, uploadedResume]);

  async function handleResumeUpload(event: ChangeEvent<HTMLInputElement>) {
    const file = event.target.files?.[0];
    if (!file) {
      return;
    }

    const extension = file.name.split(".").pop()?.toLowerCase() ?? "";
    const uploadStartedAt = Date.now();
    const isTextFile =
      file.type.startsWith("text/") || ["txt", "text", "md", "markdown"].includes(extension);

    setUploadedResume({
      name: file.name,
      size: file.size,
      extension,
      status: "extracting",
      message: isTextFile ? "Extracting text" : "Extracting PDF/DOCX text on the backend"
    });
    setAssessment(null);
    setQuestions([]);
    setAnswerFeedback(null);
    setAnalysisNotice(null);
    setIsUploadingResume(true);
    setExtractionProgress({
      startedAt: Date.now(),
      percent: 12,
      stage: isTextFile ? "Reading text file" : "Uploading resume to extractor"
    });
    if (!isTextFile) {
      setResumeText("");
    }

    try {
      window.setTimeout(() => {
        setExtractionProgress((current) => current ? { ...current, percent: 34, stage: "Extracting document text" } : current);
      }, 250);
      window.setTimeout(() => {
        setExtractionProgress((current) => current ? { ...current, percent: 62, stage: "Normalizing sections and whitespace" } : current);
      }, 900);
      const response = await Promise.race([
        uploadResume(file),
        waitForBackendResume(file, uploadStartedAt)
      ]);
      if (!response) {
        throw new Error("Resume extraction did not complete before the recovery timeout.");
      }
      applyExtractedResume(file, extension, response);
    }
    catch (error) {
      if (isTextFile) {
        const fallbackText = await readTextFile(file);
        setResumeText(fallbackText);
        setUploadedResume({
          name: file.name,
          size: file.size,
          extension,
          status: "ready",
          message: "Backend unavailable; text loaded in the browser"
        });
      }
      else {
        const recoveredResume = await recoverUploadedResume(file, uploadStartedAt);
        if (recoveredResume) {
          applyExtractedResume(file, extension, recoveredResume, "Backend completed extraction; inserted the latest text below");
        }
        else {
          setUploadedResume({
            name: file.name,
            size: file.size,
            extension,
            status: "error",
            message: extractionErrorMessage(error)
          });
        }
      }
    }
    finally {
      setIsUploadingResume(false);
      window.setTimeout(() => setExtractionProgress(null), 900);
      event.target.value = "";
    }
  }

  function applyExtractedResume(
    file: ResumeFileInfo,
    extension: string,
    response: ResumeUploadResponse,
    message?: string
  ) {
    setExtractionProgress((current) => current ? { ...current, percent: 100, stage: "Inserted extracted text" } : current);
    setResumeText(response.normalizedText);
    setUploadedResume({
      name: response.originalFilename || file.name,
      size: response.sizeBytes,
      extension,
      status: "ready",
      message: message ?? `Extracted ${response.normalizedTextLength.toLocaleString()} characters and inserted them below`
    });
    window.requestAnimationFrame(() => {
      resumeTextareaRef.current?.focus();
    });
  }

  async function recoverLatestResumeFromBackend() {
    if (!uploadedResume) {
      return;
    }

    try {
      const currentResume = await getCurrentResume();
      if (
        currentResume &&
        currentResume.originalFilename === uploadedResume.name &&
        currentResume.sizeBytes === uploadedResume.size
      ) {
        applyExtractedResume(
          uploadedResume,
          uploadedResume.extension,
          currentResume,
          "Backend completed extraction; inserted the latest text below"
        );
        return;
      }

      setUploadedResume({
        ...uploadedResume,
        message: "No matching backend extraction is available yet. Try uploading again."
      });
    }
    catch {
      setUploadedResume({
        ...uploadedResume,
        message: "Backend extractor is not reachable. Start the Spring Boot API and try again."
      });
    }
  }

  async function runAssessment() {
    if (isUploadingResume || isAnalyzing) {
      return;
    }
    if (!resumeText.trim()) {
      setAnalysisNotice("Add resume text or upload a resume before running Gemini analysis.");
      return;
    }

    setIsAnalyzing(true);
    setAnalysisNotice(null);

    const payload = {
      resumeText,
      jobDescription,
      targetRole,
      seniority
    };

    const notices: string[] = [];
    let nextAssessment: Assessment;
    let nextQuestions: InterviewQuestion[];

    try {
      nextAssessment = await createAiAssessment(payload);
    }
    catch (error) {
      nextAssessment = createAssessment(resumeText, jobDescription);
      notices.push(`Gemini assessment unavailable: ${friendlyAiError(error)} Local draft assessment is shown.`);
    }

    setAssessment(nextAssessment);

    try {
      const aiQuestions = await createAiQuestions(payload);
      nextQuestions = aiQuestions.length > 0 ? aiQuestions : createQuestions(resumeText, jobDescription);
    }
    catch (error) {
      nextQuestions = createQuestions(resumeText, jobDescription);
      notices.push(`Gemini questions unavailable: ${friendlyAiError(error)} Local draft questions are shown.`);
    }

    setQuestions(nextQuestions);
    setActiveQuestionId(nextQuestions[0]?.id ?? null);
    setAnswerFeedback(null);
    setAnswer("");
    setAnalysisNotice(notices.length > 0 ? notices.join(" ") : null);
    setIsAnalyzing(false);
  }

  async function submitAnswer() {
    if (!answer.trim() || !activeQuestion || isSubmittingAnswer) {
      return;
    }

    setIsSubmittingAnswer(true);
    setAnswerFeedback(null);

    try {
      const feedback = await createAiAnswerFeedback({
        resumeText,
        jobDescription,
        targetRole,
        seniority,
        questionText: activeQuestion.questionText,
        category: activeQuestion.category,
        expectedSignals: activeQuestion.expectedSignals,
        answerText: answer
      });
      setAnswerFeedback(feedback);
      setAnalysisNotice(null);
    }
    catch (error) {
      setAnswerFeedback(scoreAnswer(answer));
      setAnalysisNotice(`Gemini feedback unavailable: ${friendlyAiError(error)} Local draft feedback is shown.`);
    }
    finally {
      setIsSubmittingAnswer(false);
    }
  }

  return (
    <main className="app-shell">
      <aside className="sidebar">
        <div className="brand">
          <div className="brand-mark">
            <Sparkles size={18} aria-hidden="true" />
          </div>
          <div>
            <strong>AI Interview</strong>
            <span>Tech resume coach</span>
          </div>
        </div>

        <nav className="nav-list" aria-label="Primary">
          <a className="nav-item active" href="#resume">
            <FileText size={18} aria-hidden="true" />
            Resume
          </a>
          <a className="nav-item" href="#assessment">
            <Gauge size={18} aria-hidden="true" />
            Assessment
          </a>
          <a className="nav-item" href="#interview">
            <MessageSquareText size={18} aria-hidden="true" />
            Interview
          </a>
        </nav>

        <div className="sidebar-status">
          <span className="status-dot" />
          <div>
            <strong>PDF/DOCX + text</strong>
            <span>RAG + Gemini ready</span>
          </div>
        </div>
      </aside>

      <section className="workspace">
        <header className="topbar">
          <div>
            <p className="eyebrow">Candidate workspace</p>
            <h1>Resume assessment and interview practice</h1>
          </div>
          <button className="secondary-button" type="button" onClick={runAssessment} disabled={isAnalyzing || isUploadingResume}>
            {isAnalyzing ? <Loader2 className="spin" size={16} aria-hidden="true" /> : <RefreshCw size={16} aria-hidden="true" />}
            Refresh analysis
          </button>
        </header>

        <section className="layout-grid" id="resume">
          <div className="panel input-panel">
            <div className="panel-heading">
              <div>
                <p className="eyebrow">Resume input</p>
                <h2>Source material</h2>
              </div>
              <label className="icon-button" title="Upload resume">
                <Upload size={18} aria-hidden="true" />
                <input
                  type="file"
                  accept=".pdf,.doc,.docx,.txt,.text,.md,application/pdf,application/msword,application/vnd.openxmlformats-officedocument.wordprocessingml.document,text/plain,text/markdown"
                  onChange={handleResumeUpload}
                  disabled={isUploadingResume}
                />
              </label>
            </div>

            {uploadedResume ? (
              <div className={`upload-summary ${uploadedResume.status}`}>
                {uploadedResume.status === "extracting" ? (
                  <Loader2 className="spin" size={18} aria-hidden="true" />
                ) : (
                  <FileText size={18} aria-hidden="true" />
                )}
                <div>
                  <strong>{uploadedResume.name}</strong>
                  <span>
                    {formatFileSize(uploadedResume.size)} · {uploadedResume.message}
                  </span>
                  {uploadedResume.status === "error" ? (
                    <button className="recover-upload-button" type="button" onClick={recoverLatestResumeFromBackend}>
                      Use backend text
                    </button>
                  ) : null}
                </div>
              </div>
            ) : null}

            {isUploadingResume && extractionProgress ? (
              <div className="upload-progress" role="status" aria-live="polite">
                <div className="upload-progress-header">
                  <strong>{extractionProgress.stage}</strong>
                  <span>{elapsedSeconds}s</span>
                </div>
                <div className="progress-track" aria-label={`Extraction progress ${extractionProgress.percent}%`}>
                  <span style={{ width: `${extractionProgress.percent}%` }} />
                </div>
              </div>
            ) : null}

            <label className="field">
              <span>Target role</span>
              <input value={targetRole} onChange={(event) => setTargetRole(event.target.value)} />
            </label>

            <label className="field">
              <span>Seniority</span>
              <select value={seniority} onChange={(event) => setSeniority(event.target.value)}>
                <option>Entry-level</option>
                <option>Mid-level</option>
                <option>Senior</option>
                <option>Staff+</option>
              </select>
            </label>

            <label className="field">
              <span>Resume text</span>
              <textarea
                ref={resumeTextareaRef}
                className="resume-textarea"
                placeholder={
                  isUploadingResume
                    ? "Extracting resume text..."
                    : "Paste resume text, or upload PDF, DOC, DOCX, TXT, or Markdown."
                }
                value={resumeText}
                onChange={(event) => setResumeText(event.target.value)}
                readOnly={isUploadingResume}
              />
            </label>

            <label className="field">
              <span>Job description</span>
              <textarea
                className="jd-textarea"
                placeholder="Paste a job description to make scoring and questions role-specific."
                value={jobDescription}
                onChange={(event) => setJobDescription(event.target.value)}
              />
            </label>

            {analysisNotice ? (
              <div className="ai-notice" role="status">
                {analysisNotice}
              </div>
            ) : null}

            <button className="primary-button" type="button" onClick={runAssessment} disabled={isAnalyzing || isUploadingResume}>
              {isAnalyzing ? <Loader2 className="spin" size={18} aria-hidden="true" /> : <Sparkles size={18} aria-hidden="true" />}
              {isAnalyzing ? "Analyzing with Gemini" : "Analyze resume"}
              <ArrowRight size={16} aria-hidden="true" />
            </button>
          </div>

          <div className="right-rail">
            <section className="panel" id="assessment">
              <div className="panel-heading">
                <div>
                  <p className="eyebrow">{targetRole} · {seniority}</p>
                  <h2>Assessment</h2>
                </div>
                <div className="score-ring" aria-label={`Overall score ${assessment?.overallScore ?? 0}`}>
                  {assessment?.overallScore ?? "--"}
                </div>
              </div>

              {assessment ? (
                <>
                  <div className="score-grid">
                    {(Object.keys(assessment.scores) as AssessmentScoreKey[]).map((key) => (
                      <div className="metric" key={key}>
                        <div>
                          <span>{scoreLabels[key]}</span>
                          <strong>{assessment.scores[key]}</strong>
                        </div>
                        <div className="meter">
                          <span style={{ width: `${assessment.scores[key]}%` }} />
                        </div>
                      </div>
                    ))}
                  </div>

                  <div className="insight-columns">
                    <InsightList title="Strengths" items={assessment.strengths} tone="positive" />
                    <InsightList title="Gaps" items={assessment.weaknesses} tone="warning" />
                  </div>

                  <div className="recommendation-list">
                    {assessment.recommendations.map((recommendation) => (
                      <article className="recommendation" key={recommendation.message}>
                        <span className={`priority ${recommendation.priority}`}>{recommendation.priority}</span>
                        <div>
                          <strong>{recommendation.section}</strong>
                          <p>{recommendation.message}</p>
                        </div>
                      </article>
                    ))}
                  </div>
                </>
              ) : (
                <EmptyState
                  icon={<BarChart3 size={24} aria-hidden="true" />}
                  title="No assessment yet"
                  text="Run the analysis to score the resume and prepare interview questions."
                />
              )}
            </section>

            <section className="panel" id="interview">
              <div className="panel-heading">
                <div>
                  <p className="eyebrow">{questions.length || 0} questions</p>
                  <h2>Interview practice</h2>
                </div>
                <ClipboardCheck size={22} aria-hidden="true" />
              </div>

              {activeQuestion ? (
                <div className="interview-layout">
                  <div className="question-list">
                    {questions.map((question) => (
                      <button
                        className={question.id === activeQuestion.id ? "question-tab active" : "question-tab"}
                        key={question.id}
                        type="button"
                        onClick={() => {
                          setActiveQuestionId(question.id);
                          setAnswerFeedback(null);
                        }}
                      >
                        <span>{question.category}</span>
                        <small>{question.difficulty}</small>
                      </button>
                    ))}
                  </div>

                  <div className="answer-panel">
                    <div className="question-copy">
                      <span>{activeQuestion.category}</span>
                      <p>{activeQuestion.questionText}</p>
                    </div>

                    <div className="signal-list">
                      {activeQuestion.expectedSignals.map((signal) => (
                        <span key={signal}>{signal}</span>
                      ))}
                    </div>

                    <textarea
                      className="answer-textarea"
                      placeholder="Type your answer here."
                      value={answer}
                      onChange={(event) => setAnswer(event.target.value)}
                    />

                    <button
                      className="primary-button compact"
                      type="button"
                      onClick={submitAnswer}
                      disabled={isSubmittingAnswer || !answer.trim()}
                    >
                      {isSubmittingAnswer ? <Loader2 className="spin" size={16} aria-hidden="true" /> : <Send size={16} aria-hidden="true" />}
                      {isSubmittingAnswer ? "Scoring answer" : "Get feedback"}
                    </button>

                    {answerFeedback ? (
                      <div className="feedback">
                        <div className="feedback-score">
                          <CheckCircle2 size={18} aria-hidden="true" />
                          <strong>{answerFeedback.score}</strong>
                        </div>
                        <div>
                          <p>{answerFeedback.summary}</p>
                          <span>{answerFeedback.nextStep}</span>
                        </div>
                        {answerFeedback.strengths?.length || answerFeedback.gaps?.length ? (
                          <div className="feedback-details">
                            {answerFeedback.strengths?.length ? (
                              <div>
                                <strong>Working</strong>
                                {answerFeedback.strengths.map((item) => <span key={item}>{item}</span>)}
                              </div>
                            ) : null}
                            {answerFeedback.gaps?.length ? (
                              <div>
                                <strong>Improve</strong>
                                {answerFeedback.gaps.map((item) => <span key={item}>{item}</span>)}
                              </div>
                            ) : null}
                          </div>
                        ) : null}
                        {answerFeedback.followUpQuestion ? (
                          <div className="feedback-follow-up">
                            <strong>Follow-up</strong>
                            <span>{answerFeedback.followUpQuestion}</span>
                          </div>
                        ) : null}
                      </div>
                    ) : null}
                  </div>
                </div>
              ) : (
                <EmptyState
                  icon={<MessageSquareText size={24} aria-hidden="true" />}
                  title="Questions are waiting"
                  text="Analyze a resume to generate a role-aware interview set."
                />
              )}
            </section>
          </div>
        </section>
      </section>
    </main>
  );
}

function InsightList({
  title,
  items,
  tone
}: {
  title: string;
  items: string[];
  tone: "positive" | "warning";
}) {
  return (
    <div className={`insight-list ${tone}`}>
      <strong>{title}</strong>
      {items.map((item) => (
        <p key={item}>{item}</p>
      ))}
    </div>
  );
}

function EmptyState({
  icon,
  title,
  text
}: {
  icon: React.ReactNode;
  title: string;
  text: string;
}) {
  return (
    <div className="empty-state">
      {icon}
      <strong>{title}</strong>
      <p>{text}</p>
    </div>
  );
}

function formatFileSize(size: number) {
  if (size < 1024) {
    return `${size} B`;
  }
  if (size < 1024 * 1024) {
    return `${Math.round(size / 1024)} KB`;
  }
  return `${(size / (1024 * 1024)).toFixed(1)} MB`;
}

function readTextFile(file: File) {
  return new Promise<string>((resolve, reject) => {
    const reader = new FileReader();
    reader.onload = () => resolve(String(reader.result ?? ""));
    reader.onerror = () => reject(reader.error ?? new Error("Could not read text file"));
    reader.readAsText(file);
  });
}

async function recoverUploadedResume(file: File, uploadStartedAt: number) {
  return waitForBackendResume(file, uploadStartedAt, 8, 750);
}

async function waitForBackendResume(
  file: ResumeFileInfo,
  uploadStartedAt: number,
  attempts = 80,
  intervalMs = 1_000
) {
  for (let attempt = 0; attempt < attempts; attempt += 1) {
    if (attempt > 0) {
      await delay(intervalMs);
    }

    try {
      const currentResume = await getCurrentResume();
      if (!currentResume) {
        continue;
      }

      const processedAt = Date.parse(currentResume.processedAt);
      const filenameMatches = currentResume.originalFilename === file.name;
      const sizeMatches = currentResume.sizeBytes === file.size;
      const processedAfterUploadStarted =
        Number.isNaN(processedAt) || processedAt >= uploadStartedAt - 5_000;

      if (filenameMatches && sizeMatches && processedAfterUploadStarted) {
        return currentResume;
      }
    }
    catch {
      return null;
    }
  }

  return null;
}

function delay(milliseconds: number) {
  return new Promise((resolve) => window.setTimeout(resolve, milliseconds));
}

function extractionErrorMessage(error: unknown) {
  const message = error instanceof Error ? error.message : "Could not extract text from this resume";
  if (/No readable resume text/i.test(message)) {
    return "No selectable text was found. This PDF may be scanned; use an OCR PDF or paste the text.";
  }
  if (/timed out/i.test(message)) {
    return "Extraction timed out. This PDF may be scanned, encrypted, or malformed; try an OCR PDF or paste the text.";
  }
  if (/Failed to fetch|NetworkError|Load failed/i.test(message)) {
    return "Backend extractor is not reachable. Start the Spring Boot API on port 8080 and try again.";
  }
  return message;
}

function friendlyAiError(error: unknown) {
  const message = error instanceof Error ? error.message : "AI request failed";
  if (/Failed to fetch|NetworkError|Load failed/i.test(message)) {
    return "Spring Boot API is not reachable.";
  }
  if (/GEMINI_API_KEY/i.test(message)) {
    return "Gemini key is not configured.";
  }
  if (/quota|rate[- ]?limit/i.test(message)) {
    return "Gemini quota is exhausted for the configured key/model.";
  }
  if (/timed out/i.test(message)) {
    return "the AI request timed out.";
  }
  return message;
}
