package dev.jiaming.ai_interview.resume.controller;

public record ResumeChunkResponse(
	int index,
	String section,
	String content,
	int characterCount
) {
}
