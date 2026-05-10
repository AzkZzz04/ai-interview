package dev.jiaming.ai_interview.coach;

public record AiAnalysisRequest(
	String resumeText,
	String jobDescription,
	String targetRole,
	String seniority
) {
}
