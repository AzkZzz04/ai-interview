package dev.jiaming.ai_interview.ai.coach;

import java.util.List;

public record InterviewQuestionsResponse(
	List<InterviewQuestionResponse> questions,
	String modelProvider
) {
}
