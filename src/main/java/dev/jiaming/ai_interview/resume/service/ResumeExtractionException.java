package dev.jiaming.ai_interview.resume.service;

public class ResumeExtractionException extends RuntimeException {

	public ResumeExtractionException(String message) {
		super(message);
	}

	public ResumeExtractionException(String message, Throwable cause) {
		super(message, cause);
	}
}
