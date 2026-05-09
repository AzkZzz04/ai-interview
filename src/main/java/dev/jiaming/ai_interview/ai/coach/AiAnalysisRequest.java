package dev.jiaming.ai_interview.ai.coach;

public record AiAnalysisRequest(
	String resumeText,
	String jobDescription,
	String targetRole,
	String seniority
) {
}
