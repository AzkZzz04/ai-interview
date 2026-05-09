package dev.jiaming.ai_interview.ai.rag;

import java.util.Map;

public record RagContextSnippet(
	String content,
	Map<String, Object> metadata
) {
}
