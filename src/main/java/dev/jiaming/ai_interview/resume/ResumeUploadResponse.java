package dev.jiaming.ai_interview.resume;

import java.time.Instant;
import java.util.List;

public record ResumeUploadResponse(
	String id,
	String originalFilename,
	String contentType,
	String detectedContentType,
	long sizeBytes,
	int rawTextLength,
	int normalizedTextLength,
	String normalizedText,
	List<ResumeChunkResponse> chunks,
	Instant processedAt
) {
}
