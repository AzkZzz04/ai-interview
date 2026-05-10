package dev.jiaming.ai_interview.coach;

import java.util.List;

public record AnswerFeedbackRequest(
	String resumeText,
	String jobDescription,
	String targetRole,
	String seniority,
	String questionText,
	String category,
	List<String> expectedSignals,
	String answerText
) {
}
