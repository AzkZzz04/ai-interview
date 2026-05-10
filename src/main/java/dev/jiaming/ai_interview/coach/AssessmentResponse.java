package dev.jiaming.ai_interview.coach;

import java.util.List;

public record AssessmentResponse(
	int overallScore,
	AssessmentScores scores,
	List<String> strengths,
	List<String> weaknesses,
	List<RecommendationResponse> recommendations,
	String modelProvider,
	List<String> sourceContextIds
) {
}
