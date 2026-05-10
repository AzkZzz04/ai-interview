package dev.jiaming.ai_interview.coach;

import java.util.Optional;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import dev.jiaming.ai_interview.gemini.GeminiClient;
import dev.jiaming.ai_interview.interview.InterviewPersistenceService;

@Service
public class AiResumeCoachService {

	private final GeminiClient geminiClient;

	private final CoachResumeResolver resumeResolver;

	private final CoachRagContextService ragContextService;

	private final CoachPromptBuilder promptBuilder;

	private final CoachResponseMapper responseMapper;

	private final ObjectProvider<InterviewPersistenceService> interviewPersistenceServiceProvider;

	public AiResumeCoachService(
		GeminiClient geminiClient,
		CoachResumeResolver resumeResolver,
		CoachRagContextService ragContextService,
		CoachPromptBuilder promptBuilder,
		CoachResponseMapper responseMapper,
		ObjectProvider<InterviewPersistenceService> interviewPersistenceServiceProvider
	) {
		this.geminiClient = geminiClient;
		this.resumeResolver = resumeResolver;
		this.ragContextService = ragContextService;
		this.promptBuilder = promptBuilder;
		this.responseMapper = responseMapper;
		this.interviewPersistenceServiceProvider = interviewPersistenceServiceProvider;
	}

	public AssessmentResponse assess(AiAnalysisRequest request) {
		String resumeText = resumeResolver.resolve(request.resumeText());
		CoachRagContext context = ragContextService.assessmentContext(request, resumeText);
		String prompt = promptBuilder.buildAssessmentPrompt(request, context);
		AssessmentResponse response = responseMapper.parse(geminiClient.generateJson(prompt), AssessmentResponse.class);
		AssessmentResponse normalizedResponse = responseMapper.normalizeAssessment(response, context.sourceContextIds());
		persistenceService().ifPresent(service -> service.saveAssessment(request, resumeText, normalizedResponse));
		return normalizedResponse;
	}

	public InterviewQuestionsResponse generateQuestions(AiAnalysisRequest request) {
		String resumeText = resumeResolver.resolve(request.resumeText());
		CoachRagContext context = ragContextService.questionContext(request, resumeText);
		String prompt = promptBuilder.buildQuestionPrompt(request, context);
		InterviewQuestionsResponse response = responseMapper.parse(
			geminiClient.generateJson(prompt),
			InterviewQuestionsResponse.class
		);
		InterviewQuestionsResponse normalizedResponse = responseMapper.normalizeQuestions(response, context.sourceContextIds());
		persistenceService().ifPresent(service -> service.saveQuestions(request, resumeText, normalizedResponse));
		return normalizedResponse;
	}

	public AnswerFeedbackResponse scoreAnswer(AnswerFeedbackRequest request) {
		if (blank(request.answerText())) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Answer text is required");
		}

		String resumeText = resumeResolver.resolve(request.resumeText());
		CoachRagContext context = ragContextService.feedbackContext(request, resumeText);
		String prompt = promptBuilder.buildFeedbackPrompt(request, context);
		AnswerFeedbackResponse response = responseMapper.parse(geminiClient.generateJson(prompt), AnswerFeedbackResponse.class);
		AnswerFeedbackResponse normalizedResponse = responseMapper.normalizeFeedback(response, context.sourceContextIds());
		persistenceService().ifPresent(service -> service.saveAnswer(request, resumeText, normalizedResponse));
		return normalizedResponse;
	}

	private Optional<InterviewPersistenceService> persistenceService() {
		return Optional.ofNullable(interviewPersistenceServiceProvider.getIfAvailable());
	}

	private boolean blank(String value) {
		return value == null || value.isBlank();
	}
}
