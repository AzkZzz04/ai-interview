package dev.jiaming.ai_interview.coach;

import java.util.List;

public record AnswerFeedbackResponse(
	int score,
	String summary,
	String nextStep,
	List<String> strengths,
	List<String> gaps,
	List<String> betterAnswerOutline,
	String followUpQuestion,
	String modelProvider,
	List<String> sourceContextIds
) {
}
