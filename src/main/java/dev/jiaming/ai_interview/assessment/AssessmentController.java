package dev.jiaming.ai_interview.assessment;

import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import dev.jiaming.ai_interview.coach.AiAnalysisRequest;
import dev.jiaming.ai_interview.coach.AiResumeCoachService;
import dev.jiaming.ai_interview.coach.AssessmentResponse;

@RestController
@RequestMapping("/api/assessments")
@CrossOrigin(origins = { "http://localhost:3000", "http://127.0.0.1:3000" })
public class AssessmentController {

	private final AiResumeCoachService aiResumeCoachService;

	public AssessmentController(AiResumeCoachService aiResumeCoachService) {
		this.aiResumeCoachService = aiResumeCoachService;
	}

	@PostMapping
	public AssessmentResponse assess(@RequestBody AiAnalysisRequest request) {
		return aiResumeCoachService.assess(request);
	}
}
