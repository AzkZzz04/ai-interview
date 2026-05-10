package dev.jiaming.ai_interview.resume;

public record ResumeChunkResponse(
	int index,
	String section,
	String content,
	int characterCount
) {
}
