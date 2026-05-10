package dev.jiaming.ai_interview.coach;

public record RecommendationResponse(
	String section,
	String priority,
	String message
) {
}
