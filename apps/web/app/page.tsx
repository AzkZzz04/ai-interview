"use client";

import {
  FileText,
  Gauge,
  Loader2,
  MessageSquareText,
  RefreshCw,
  Sparkles
} from "lucide-react";
import { ChangeEvent, useEffect, useMemo, useRef, useState } from "react";
import { AssessmentPanel } from "@/components/AssessmentPanel";
import { InterviewPracticePanel } from "@/components/InterviewPracticePanel";
import {
  ExtractionProgress,
  ResumeInputPanel,
  UploadedResume
} from "@/components/ResumeInputPanel";
import { createAiAnswerFeedback, createAiAssessment, createAiQuestions } from "@/lib/api/ai";
import { getCurrentResume, ResumeUploadResponse, uploadResume } from "@/lib/api/resumes";
import {
  AnswerFeedback,
  Assessment,
  createAssessment,
  createQuestions,
  InterviewQuestion,
  scoreAnswer
} from "@/lib/mockAssessment";

const starterResume = `EXPERIENCE
Backend Engineer, Example Systems
- Built Spring Boot services for resume processing and interview workflows.
- Improved PostgreSQL query performance for high-volume profile search.
- Added Redis-backed rate limiting and operational dashboards.

SKILLS
Java, Spring Boot, PostgreSQL, Redis, Next.js, TypeScript, observability

EDUCATION
B.S. Computer Science`;

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
          <ResumeInputPanel
            uploadedResume={uploadedResume}
            extractionProgress={extractionProgress}
            elapsedSeconds={elapsedSeconds}
            targetRole={targetRole}
            seniority={seniority}
            resumeText={resumeText}
            jobDescription={jobDescription}
            analysisNotice={analysisNotice}
            isAnalyzing={isAnalyzing}
            isUploadingResume={isUploadingResume}
            resumeTextareaRef={resumeTextareaRef}
            onResumeUpload={handleResumeUpload}
            onRecoverLatestResume={recoverLatestResumeFromBackend}
            onTargetRoleChange={setTargetRole}
            onSeniorityChange={setSeniority}
            onResumeTextChange={setResumeText}
            onJobDescriptionChange={setJobDescription}
            onRunAssessment={runAssessment}
          />

          <div className="right-rail">
            <AssessmentPanel targetRole={targetRole} seniority={seniority} assessment={assessment} />
            <InterviewPracticePanel
              questions={questions}
              activeQuestion={activeQuestion}
              answer={answer}
              answerFeedback={answerFeedback}
              isSubmittingAnswer={isSubmittingAnswer}
              onActiveQuestionChange={setActiveQuestionId}
              onAnswerChange={setAnswer}
              onClearAnswerFeedback={() => setAnswerFeedback(null)}
              onSubmitAnswer={submitAnswer}
            />
          </div>
        </section>
      </section>
    </main>
  );
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
