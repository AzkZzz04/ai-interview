package dev.jiaming.ai_interview.resume;

import java.util.UUID;

public record PersistedResume(
	UUID id,
	String normalizedText
) {
}
