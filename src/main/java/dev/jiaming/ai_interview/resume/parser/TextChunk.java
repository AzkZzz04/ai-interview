package dev.jiaming.ai_interview.resume.parser;

public record TextChunk(
	int index,
	String section,
	String content
) {
}
