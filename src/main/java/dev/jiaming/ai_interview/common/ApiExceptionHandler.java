package dev.jiaming.ai_interview.common;

import java.time.Instant;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import dev.jiaming.ai_interview.gemini.GeminiException;
import dev.jiaming.ai_interview.resume.ResumeExtractionException;

@RestControllerAdvice
public class ApiExceptionHandler {

	@ExceptionHandler(ResumeExtractionException.class)
	@ResponseStatus(HttpStatus.UNPROCESSABLE_ENTITY)
	public Map<String, Object> handleResumeExtractionException(ResumeExtractionException exception) {
		return Map.of(
			"timestamp", Instant.now().toString(),
			"status", HttpStatus.UNPROCESSABLE_ENTITY.value(),
			"error", "Resume extraction failed",
			"message", exception.getMessage()
		);
	}

	@ExceptionHandler(GeminiException.class)
	@ResponseStatus(HttpStatus.BAD_GATEWAY)
	public Map<String, Object> handleGeminiException(GeminiException exception) {
		return Map.of(
			"timestamp", Instant.now().toString(),
			"status", HttpStatus.BAD_GATEWAY.value(),
			"error", "Gemini request failed",
			"message", exception.getMessage()
		);
	}
}
