package dev.jiaming.ai_interview.rag;

import java.util.Map;

public record RagContextSnippet(
	String id,
	String content,
	Map<String, Object> metadata,
	Double score
) {

	public String sourceContextId() {
		Object contextId = metadata == null ? null : metadata.get("contextId");
		return contextId == null ? id : contextId.toString();
	}
}
