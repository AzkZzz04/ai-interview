import { AnswerFeedback, Assessment, InterviewQuestion } from "@/lib/mockAssessment";

const API_BASE_URL = process.env.NEXT_PUBLIC_API_BASE_URL ?? "http://127.0.0.1:8080";
const REQUEST_TIMEOUT_MS = 120_000;

export type AiAnalysisPayload = {
  resumeText: string;
  jobDescription: string;
  targetRole: string;
  seniority: string;
};

export type AnswerFeedbackPayload = AiAnalysisPayload & {
  questionText: string;
  category: string;
  expectedSignals: string[];
  answerText: string;
};

export async function createAiAssessment(payload: AiAnalysisPayload): Promise<Assessment> {
  return postJson<Assessment>("/api/assessments", payload);
}

export async function createAiQuestions(payload: AiAnalysisPayload): Promise<InterviewQuestion[]> {
  const response = await postJson<{ questions: InterviewQuestion[] }>("/api/interview/questions", payload);
  return response.questions;
}

export async function createAiAnswerFeedback(payload: AnswerFeedbackPayload): Promise<AnswerFeedback> {
  return postJson<AnswerFeedback>("/api/interview/feedback", payload);
}

async function postJson<TResponse>(path: string, body: unknown): Promise<TResponse> {
  const controller = new AbortController();
  const timeoutId = window.setTimeout(() => controller.abort(), REQUEST_TIMEOUT_MS);

  try {
    const response = await fetch(`${API_BASE_URL}${path}`, {
      method: "POST",
      headers: {
        "Content-Type": "application/json"
      },
      body: JSON.stringify(body),
      signal: controller.signal
    });

    if (!response.ok) {
      const message = await apiErrorMessage(response);
      throw new Error(message);
    }

    return response.json() as Promise<TResponse>;
  }
  catch (error) {
    if (error instanceof DOMException && error.name === "AbortError") {
      throw new Error("AI request timed out after 120 seconds. Try again with a shorter resume or job description.");
    }
    throw error;
  }
  finally {
    window.clearTimeout(timeoutId);
  }
}

async function apiErrorMessage(response: Response) {
  try {
    const payload = await response.json() as { message?: string; error?: string };
    return payload.message ?? payload.error ?? `Request failed with status ${response.status}`;
  }
  catch {
    return `Request failed with status ${response.status}`;
  }
}
