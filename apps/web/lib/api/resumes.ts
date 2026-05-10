import { createIdempotencyKey } from "@/lib/api/idempotency";

export type ResumeUploadResponse = {
  id: string;
  originalFilename: string;
  contentType: string | null;
  detectedContentType: string | null;
  sizeBytes: number;
  rawTextLength: number;
  normalizedTextLength: number;
  normalizedText: string;
  chunks: Array<{
    index: number;
    section: string;
    content: string;
    characterCount: number;
  }>;
  processedAt: string;
};

const API_BASE_URL = process.env.NEXT_PUBLIC_API_BASE_URL ?? "http://127.0.0.1:8080";

export async function uploadResume(file: File): Promise<ResumeUploadResponse> {
  const formData = new FormData();
  formData.append("file", file);
  const controller = new AbortController();
  const timeoutId = window.setTimeout(() => controller.abort(), 30_000);

  try {
    const idempotencyKey = createIdempotencyKey("resume-upload");
    const response = await fetch(`${API_BASE_URL}/api/resumes`, {
      method: "POST",
      headers: {
        "Idempotency-Key": idempotencyKey
      },
      body: formData,
      signal: controller.signal
    });

    if (!response.ok) {
      throw new Error(await responseMessage(response));
    }

    return response.json() as Promise<ResumeUploadResponse>;
  }
  catch (error) {
    if (error instanceof DOMException && error.name === "AbortError") {
      throw new Error("Resume extraction timed out after 30 seconds. This PDF may be scanned or malformed.");
    }
    throw error;
  }
  finally {
    window.clearTimeout(timeoutId);
  }
}

export async function getCurrentResume(): Promise<ResumeUploadResponse | null> {
  const response = await fetch(`${API_BASE_URL}/api/resumes/current`);

  if (response.status === 404) {
    return null;
  }

  if (!response.ok) {
    throw new Error(await responseMessage(response));
  }

  return response.json() as Promise<ResumeUploadResponse>;
}

async function responseMessage(response: Response) {
  const fallback = `Resume upload failed with status ${response.status}`;
  const contentType = response.headers.get("content-type") ?? "";

  if (!contentType.includes("application/json")) {
    return (await response.text()) || fallback;
  }

  const body = (await response.json()) as { message?: string; error?: string };
  return body.message ?? body.error ?? fallback;
}
