package dev.jiaming.ai_interview.ai.coach;

import java.util.List;

public record AssessmentResponse(
	int overallScore,
	AssessmentScores scores,
	List<String> strengths,
	List<String> weaknesses,
	List<RecommendationResponse> recommendations,
	String modelProvider
) {
}
