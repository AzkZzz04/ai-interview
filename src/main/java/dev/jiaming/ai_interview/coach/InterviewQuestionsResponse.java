package dev.jiaming.ai_interview.coach;

import java.util.List;

public record InterviewQuestionsResponse(
	List<InterviewQuestionResponse> questions,
	String modelProvider
) {
}
