package dev.jiaming.ai_interview.coach;

import java.util.List;

record CoachRagContext(
	String corpusId,
	String context,
	List<String> sourceContextIds,
	boolean vectorBacked
) {
}
