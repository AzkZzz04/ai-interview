package dev.jiaming.ai_interview.coach;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import dev.jiaming.ai_interview.resume.ResumeUploadResponse;
import dev.jiaming.ai_interview.resume.ResumeUploadService;

@Component
public class CoachResumeResolver {

	private final ResumeUploadService resumeUploadService;

	public CoachResumeResolver(ResumeUploadService resumeUploadService) {
		this.resumeUploadService = resumeUploadService;
	}

	public String resolve(String requestResumeText) {
		if (!blank(requestResumeText)) {
			return requestResumeText.trim();
		}
		return resumeUploadService.current()
			.map(ResumeUploadResponse::normalizedText)
			.filter(text -> !blank(text))
			.orElseThrow(() -> new ResponseStatusException(
				HttpStatus.BAD_REQUEST,
				"Resume text is required. Paste text or upload a resume first."
			));
	}

	private boolean blank(String value) {
		return value == null || value.isBlank();
	}
}
