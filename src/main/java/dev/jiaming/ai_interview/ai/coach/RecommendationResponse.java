package dev.jiaming.ai_interview.ai.coach;

public record RecommendationResponse(
	String section,
	String priority,
	String message
) {
}
