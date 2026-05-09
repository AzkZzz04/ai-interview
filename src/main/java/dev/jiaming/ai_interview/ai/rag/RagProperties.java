package dev.jiaming.ai_interview.ai.rag;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.rag")
public record RagProperties(
	int embeddingDimensions,
	int defaultTopK
) {
}
