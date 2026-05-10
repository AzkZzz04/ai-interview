package dev.jiaming.ai_interview.interview;

import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import dev.jiaming.ai_interview.coach.AiAnalysisRequest;
import dev.jiaming.ai_interview.coach.AnswerFeedbackRequest;
import dev.jiaming.ai_interview.coach.AnswerFeedbackResponse;
import dev.jiaming.ai_interview.coach.AssessmentResponse;
import dev.jiaming.ai_interview.coach.InterviewQuestionsResponse;
import dev.jiaming.ai_interview.common.LocalUserService;

@Service
public class InterviewPersistenceService {

	private final LocalUserService localUserService;

	private final AssessmentPersistenceService assessmentPersistenceService;

	private final InterviewSessionPersistenceService interviewSessionPersistenceService;

	private final AnswerPersistenceService answerPersistenceService;

	public InterviewPersistenceService(
		LocalUserService localUserService,
		AssessmentPersistenceService assessmentPersistenceService,
		InterviewSessionPersistenceService interviewSessionPersistenceService,
		AnswerPersistenceService answerPersistenceService
	) {
		this.localUserService = localUserService;
		this.assessmentPersistenceService = assessmentPersistenceService;
		this.interviewSessionPersistenceService = interviewSessionPersistenceService;
		this.answerPersistenceService = answerPersistenceService;
	}

	@Transactional
	public void saveAssessment(AiAnalysisRequest request, String resumeText, AssessmentResponse response) {
		assessmentPersistenceService.save(localUserService.localUserId(), request, resumeText, response);
	}

	@Transactional
	public void saveQuestions(AiAnalysisRequest request, String resumeText, InterviewQuestionsResponse response) {
		interviewSessionPersistenceService.saveQuestions(localUserService.localUserId(), request, resumeText, response);
	}

	@Transactional
	public void saveAnswer(AnswerFeedbackRequest request, String resumeText, AnswerFeedbackResponse response) {
		UUID userId = localUserService.localUserId();
		UUID questionId = interviewSessionPersistenceService.findLatestQuestion(userId, request.questionText())
			.orElseGet(() -> interviewSessionPersistenceService.createQuestionForAnswer(userId, request, resumeText));
		answerPersistenceService.save(questionId, request, response);
	}
}
