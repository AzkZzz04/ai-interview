package dev.jiaming.ai_interview.interview;

import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import dev.jiaming.ai_interview.coach.AiAnalysisRequest;
import dev.jiaming.ai_interview.coach.AiResumeCoachService;
import dev.jiaming.ai_interview.coach.AnswerFeedbackRequest;
import dev.jiaming.ai_interview.coach.AnswerFeedbackResponse;
import dev.jiaming.ai_interview.coach.InterviewQuestionsResponse;

@RestController
@RequestMapping("/api/interview")
@CrossOrigin(origins = { "http://localhost:3000", "http://127.0.0.1:3000" })
public class InterviewController {

	private final AiResumeCoachService aiResumeCoachService;

	public InterviewController(AiResumeCoachService aiResumeCoachService) {
		this.aiResumeCoachService = aiResumeCoachService;
	}

	@PostMapping("/questions")
	public InterviewQuestionsResponse questions(@RequestBody AiAnalysisRequest request) {
		return aiResumeCoachService.generateQuestions(request);
	}

	@PostMapping("/feedback")
	public AnswerFeedbackResponse feedback(@RequestBody AnswerFeedbackRequest request) {
		return aiResumeCoachService.scoreAnswer(request);
	}
}
