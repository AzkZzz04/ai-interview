package dev.jiaming.ai_interview.coach;

import java.util.List;

public record InterviewQuestionResponse(
	String id,
	String category,
	String difficulty,
	String questionText,
	List<String> expectedSignals,
	List<String> sourceContextIds
) {
}
