package dev.jiaming.ai_interview.interview;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

@Component
public class PersistenceJsonSupport {

	private static final String PROMPT_NAME = "rag-grounded";

	private final ObjectMapper objectMapper;

	public PersistenceJsonSupport(ObjectMapper objectMapper) {
		this.objectMapper = objectMapper;
	}

	public String promptName() {
		return PROMPT_NAME;
	}

	public String json(Object value) {
		try {
			return objectMapper.writeValueAsString(value == null ? List.of() : value);
		}
		catch (JsonProcessingException exception) {
			throw new IllegalStateException("Could not serialize persistence payload", exception);
		}
	}

	public String model(String modelProvider) {
		return modelProvider == null || modelProvider.isBlank() ? "gemini" : modelProvider;
	}

	public String hash(String... values) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			for (String value : values) {
				digest.update((value == null ? "" : value).getBytes(StandardCharsets.UTF_8));
				digest.update((byte) 0);
			}
			return HexFormat.of().formatHex(digest.digest());
		}
		catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 is unavailable", exception);
		}
	}
}
