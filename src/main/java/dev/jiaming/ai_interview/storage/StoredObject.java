package dev.jiaming.ai_interview.storage;

public record StoredObject(
	String bucket,
	String key,
	long sizeBytes
) {
}
