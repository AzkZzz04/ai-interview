package dev.jiaming.ai_interview.coach;

import java.util.Optional;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import dev.jiaming.ai_interview.common.RedisRequestGuard;
import dev.jiaming.ai_interview.gemini.GeminiClient;
import dev.jiaming.ai_interview.interview.InterviewPersistenceService;

@Service
public class AiResumeCoachService {

	private final GeminiClient geminiClient;

	private final CoachResumeResolver resumeResolver;

	private final CoachRagContextService ragContextService;

	private final CoachPromptBuilder promptBuilder;

	private final CoachResponseMapper responseMapper;

	private final RedisRequestGuard redisRequestGuard;

	private final ObjectProvider<InterviewPersistenceService> interviewPersistenceServiceProvider;

	public AiResumeCoachService(
		GeminiClient geminiClient,
		CoachResumeResolver resumeResolver,
		CoachRagContextService ragContextService,
		CoachPromptBuilder promptBuilder,
		CoachResponseMapper responseMapper,
		RedisRequestGuard redisRequestGuard,
		ObjectProvider<InterviewPersistenceService> interviewPersistenceServiceProvider
	) {
		this.geminiClient = geminiClient;
		this.resumeResolver = resumeResolver;
		this.ragContextService = ragContextService;
		this.promptBuilder = promptBuilder;
		this.responseMapper = responseMapper;
		this.redisRequestGuard = redisRequestGuard;
		this.interviewPersistenceServiceProvider = interviewPersistenceServiceProvider;
	}

	public AssessmentResponse assess(AiAnalysisRequest request) {
		String resumeText = resumeResolver.resolve(request.resumeText());
		Object fingerprintSource = fingerprintSource(resumeText, request.jobDescription(), request.targetRole(), request.seniority());
		return redisRequestGuard.withIdempotentRetryCache(
			"assessment",
			fingerprintSource,
			AssessmentResponse.class,
			() -> {
				redisRequestGuard.assertAiAllowed("assessment");
				return redisRequestGuard.withInFlightLock("assessment", fingerprintSource, () -> {
					CoachRagContext context = ragContextService.assessmentContext(request, resumeText);
					String prompt = promptBuilder.buildAssessmentPrompt(request, context);
					AssessmentResponse response = responseMapper.parse(geminiClient.generateJson(prompt), AssessmentResponse.class);
					AssessmentResponse normalizedResponse = responseMapper.normalizeAssessment(response, context.sourceContextIds());
					persistenceService().ifPresent(service -> service.saveAssessment(request, resumeText, normalizedResponse));
					return normalizedResponse;
				});
			}
		);
	}

	public InterviewQuestionsResponse generateQuestions(AiAnalysisRequest request) {
		String resumeText = resumeResolver.resolve(request.resumeText());
		Object fingerprintSource = fingerprintSource(resumeText, request.jobDescription(), request.targetRole(), request.seniority());
		return redisRequestGuard.withIdempotentRetryCache(
			"questions",
			fingerprintSource,
			InterviewQuestionsResponse.class,
			() -> {
				redisRequestGuard.assertAiAllowed("questions");
				return redisRequestGuard.withInFlightLock("questions", fingerprintSource, () -> {
					CoachRagContext context = ragContextService.questionContext(request, resumeText);
					String prompt = promptBuilder.buildQuestionPrompt(request, context);
					InterviewQuestionsResponse response = responseMapper.parse(
						geminiClient.generateJson(prompt),
						InterviewQuestionsResponse.class
					);
					InterviewQuestionsResponse normalizedResponse = responseMapper.normalizeQuestions(response, context.sourceContextIds());
					persistenceService().ifPresent(service -> service.saveQuestions(request, resumeText, normalizedResponse));
					return normalizedResponse;
				});
			}
		);
	}

	public AnswerFeedbackResponse scoreAnswer(AnswerFeedbackRequest request) {
		if (blank(request.answerText())) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Answer text is required");
		}

		String resumeText = resumeResolver.resolve(request.resumeText());
		Object fingerprintSource = fingerprintSource(
			resumeText,
			request.jobDescription(),
			request.targetRole(),
			request.seniority(),
			request.questionText(),
			request.category(),
			request.expectedSignals(),
			request.answerText()
		);
		return redisRequestGuard.withIdempotentRetryCache(
			"feedback",
			fingerprintSource,
			AnswerFeedbackResponse.class,
			() -> {
				redisRequestGuard.assertAiAllowed("feedback");
				return redisRequestGuard.withInFlightLock("feedback", fingerprintSource, () -> {
					CoachRagContext context = ragContextService.feedbackContext(request, resumeText);
					String prompt = promptBuilder.buildFeedbackPrompt(request, context);
					AnswerFeedbackResponse response = responseMapper.parse(geminiClient.generateJson(prompt), AnswerFeedbackResponse.class);
					AnswerFeedbackResponse normalizedResponse = responseMapper.normalizeFeedback(response, context.sourceContextIds());
					persistenceService().ifPresent(service -> service.saveAnswer(request, resumeText, normalizedResponse));
					return normalizedResponse;
				});
			}
		);
	}

	private Optional<InterviewPersistenceService> persistenceService() {
		return Optional.ofNullable(interviewPersistenceServiceProvider.getIfAvailable());
	}

	private boolean blank(String value) {
		return value == null || value.isBlank();
	}

	private Object fingerprintSource(Object... values) {
		return java.util.Arrays.asList(values);
	}
}
