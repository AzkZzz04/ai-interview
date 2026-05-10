package dev.jiaming.ai_interview.storage;

import java.util.Map;

public interface ObjectStorageService {

	StoredObject put(String key, byte[] content, String contentType, Map<String, String> metadata);
}
