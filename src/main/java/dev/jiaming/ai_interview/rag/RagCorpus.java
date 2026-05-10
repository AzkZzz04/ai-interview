package dev.jiaming.ai_interview.rag;

import java.util.List;

public record RagCorpus(
	String id,
	List<String> documentIds,
	int resumeChunkCount,
	int jobDescriptionChunkCount
) {
}
