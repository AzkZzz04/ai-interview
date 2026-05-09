package dev.jiaming.ai_interview.ai.coach;

import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import dev.jiaming.ai_interview.ai.gemini.GeminiClient;
import dev.jiaming.ai_interview.ai.gemini.GeminiException;
import dev.jiaming.ai_interview.resume.controller.ResumeUploadResponse;
import dev.jiaming.ai_interview.resume.parser.SectionAwareTextChunker;
import dev.jiaming.ai_interview.resume.parser.TextChunk;
import dev.jiaming.ai_interview.resume.service.ResumeUploadService;

@Service
public class AiResumeCoachService {

	private static final int RESUME_PROMPT_LIMIT = 16_000;

	private static final int JOB_DESCRIPTION_PROMPT_LIMIT = 8_000;

	private static final int ANSWER_PROMPT_LIMIT = 4_000;

	private final GeminiClient geminiClient;

	private final ObjectMapper objectMapper;

	private final ResumeUploadService resumeUploadService;

	private final SectionAwareTextChunker chunker;

	public AiResumeCoachService(
		GeminiClient geminiClient,
		ObjectMapper objectMapper,
		ResumeUploadService resumeUploadService,
		SectionAwareTextChunker chunker
	) {
		this.geminiClient = geminiClient;
		this.objectMapper = objectMapper;
		this.resumeUploadService = resumeUploadService;
		this.chunker = chunker;
	}

	public AssessmentResponse assess(AiAnalysisRequest request) {
		String prompt = buildAssessmentPrompt(request, resolveResumeText(request.resumeText()));
		AssessmentResponse response = parse(geminiClient.generateJson(prompt), AssessmentResponse.class);
		return normalizeAssessment(response);
	}

	public InterviewQuestionsResponse generateQuestions(AiAnalysisRequest request) {
		String resumeText = resolveResumeText(request.resumeText());
		String prompt = buildQuestionPrompt(request, resumeText, resumeContext(resumeText));
		InterviewQuestionsResponse response = parse(geminiClient.generateJson(prompt), InterviewQuestionsResponse.class);
		return normalizeQuestions(response);
	}

	public AnswerFeedbackResponse scoreAnswer(AnswerFeedbackRequest request) {
		if (blank(request.answerText())) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Answer text is required");
		}

		String resumeText = resolveResumeText(request.resumeText());
		String prompt = buildFeedbackPrompt(request, resumeContext(resumeText));
		AnswerFeedbackResponse response = parse(geminiClient.generateJson(prompt), AnswerFeedbackResponse.class);
		return normalizeFeedback(response);
	}

	private String buildAssessmentPrompt(AiAnalysisRequest request, String resumeText) {
		return """
			You are a senior technical recruiter and engineering interviewer.
			Assess this tech resume for the target role. Use the job description only if provided.
			Be direct, concrete, and evidence-based. Do not invent experience that is not in the resume.
			Return only valid JSON matching this shape:
			{
			  "overallScore": 0,
			  "scores": {
			    "technicalDepth": 0,
			    "impact": 0,
			    "clarity": 0,
			    "relevance": 0,
			    "ats": 0
			  },
			  "strengths": ["2-4 concise strengths"],
			  "weaknesses": ["2-4 concise gaps"],
			  "recommendations": [
			    {"section": "Experience", "priority": "high", "message": "specific rewrite guidance"}
			  ]
			}
			All scores must be integers from 0 to 100.

			Target role: %s
			Seniority: %s

			Resume:
			%s

			Job description:
			%s
			""".formatted(
			fallback(request.targetRole(), "Software Engineer"),
			fallback(request.seniority(), "Mid-level"),
			truncate(resumeText, RESUME_PROMPT_LIMIT),
			truncate(fallback(request.jobDescription(), ""), JOB_DESCRIPTION_PROMPT_LIMIT)
		);
	}

	private String buildQuestionPrompt(AiAnalysisRequest request, String resumeText, String context) {
		return """
			You are generating a technical interview set for a candidate.
			Use the resume as retrieval context. Create questions that test the candidate's actual claimed experience and the target role.
			Include a mix of resume deep dive, system design, debugging, and collaboration.
			Return only valid JSON matching this shape:
			{
			  "questions": [
			    {
			      "id": "stable-slug",
			      "category": "Resume Deep Dive",
			      "difficulty": "Warmup",
			      "questionText": "question",
			      "expectedSignals": ["signal 1", "signal 2", "signal 3"]
			    }
			  ]
			}
			Generate exactly 5 questions. Difficulty must be one of Warmup, Core, Deep Dive.

			Target role: %s
			Seniority: %s

			Retrieved resume context:
			%s

			Full resume:
			%s

			Job description:
			%s
			""".formatted(
			fallback(request.targetRole(), "Software Engineer"),
			fallback(request.seniority(), "Mid-level"),
			context,
			truncate(resumeText, RESUME_PROMPT_LIMIT),
			truncate(fallback(request.jobDescription(), ""), JOB_DESCRIPTION_PROMPT_LIMIT)
		);
	}

	private String buildFeedbackPrompt(AnswerFeedbackRequest request, String context) {
		return """
			You are coaching a candidate after one interview answer.
			Score the answer against the question and expected signals. Be specific and actionable.
			Do not reward claims that are not supported by the answer.
			Return only valid JSON matching this shape:
			{
			  "score": 0,
			  "summary": "one sentence",
			  "nextStep": "one concrete next practice step",
			  "strengths": ["1-3 strengths"],
			  "gaps": ["1-3 gaps"],
			  "betterAnswerOutline": ["context", "action", "tradeoff", "result"],
			  "followUpQuestion": "one follow-up question"
			}
			Score must be an integer from 0 to 100.

			Target role: %s
			Seniority: %s
			Question category: %s
			Question: %s
			Expected signals: %s

			Retrieved resume context:
			%s

			Job description:
			%s

			Candidate answer:
			%s
			""".formatted(
			fallback(request.targetRole(), "Software Engineer"),
			fallback(request.seniority(), "Mid-level"),
			fallback(request.category(), "Interview"),
			fallback(request.questionText(), ""),
			String.join(", ", safeList(request.expectedSignals())),
			context,
			truncate(fallback(request.jobDescription(), ""), JOB_DESCRIPTION_PROMPT_LIMIT),
			truncate(request.answerText(), ANSWER_PROMPT_LIMIT)
		);
	}

	private String resumeContext(String resumeText) {
		List<TextChunk> chunks = chunker.chunk(resumeText);
		return chunks.stream()
			.limit(6)
			.map(chunk -> "- [" + chunk.section() + "] " + truncate(chunk.content(), 900))
			.reduce((left, right) -> left + "\n" + right)
			.orElse(truncate(resumeText, 2_500));
	}

	private String resolveResumeText(String requestResumeText) {
		if (!blank(requestResumeText)) {
			return requestResumeText.trim();
		}
		return resumeUploadService.current()
			.map(ResumeUploadResponse::normalizedText)
			.filter(text -> !blank(text))
			.orElseThrow(() -> new ResponseStatusException(
				HttpStatus.BAD_REQUEST,
				"Resume text is required. Paste text or upload a resume first."
			));
	}

	private AssessmentResponse normalizeAssessment(AssessmentResponse response) {
		AssessmentScores scores = response.scores() == null
			? new AssessmentScores(0, 0, 0, 0, 0)
			: response.scores();
		AssessmentScores normalizedScores = new AssessmentScores(
			clampScore(scores.technicalDepth()),
			clampScore(scores.impact()),
			clampScore(scores.clarity()),
			clampScore(scores.relevance()),
			clampScore(scores.ats())
		);
		int overallScore = response.overallScore() > 0
			? clampScore(response.overallScore())
			: average(normalizedScores);
		return new AssessmentResponse(
			overallScore,
			normalizedScores,
			nonEmpty(response.strengths()),
			nonEmpty(response.weaknesses()),
			nonEmptyRecommendations(response.recommendations()),
			"gemini"
		);
	}

	private InterviewQuestionsResponse normalizeQuestions(InterviewQuestionsResponse response) {
		List<InterviewQuestionResponse> questions = safeList(response.questions()).stream()
			.filter(question -> !blank(question.questionText()))
			.limit(7)
			.map(question -> new InterviewQuestionResponse(
				fallback(question.id(), slug(question.category() + "-" + question.questionText())),
				fallback(question.category(), "Interview"),
				normalizeDifficulty(question.difficulty()),
				question.questionText(),
				nonEmpty(question.expectedSignals())
			))
			.toList();
		return new InterviewQuestionsResponse(questions, "gemini");
	}

	private AnswerFeedbackResponse normalizeFeedback(AnswerFeedbackResponse response) {
		return new AnswerFeedbackResponse(
			clampScore(response.score()),
			fallback(response.summary(), "The answer was scored, but Gemini did not provide a summary."),
			fallback(response.nextStep(), "Add clearer structure, technical detail, and measurable outcomes."),
			nonEmpty(response.strengths()),
			nonEmpty(response.gaps()),
			nonEmpty(response.betterAnswerOutline()),
			fallback(response.followUpQuestion(), ""),
			"gemini"
		);
	}

	private <T> T parse(String json, Class<T> responseType) {
		try {
			return objectMapper.readValue(json, responseType);
		}
		catch (IOException exception) {
			throw new GeminiException("Gemini returned JSON that did not match the expected AI contract", exception);
		}
	}

	private List<String> nonEmpty(List<String> values) {
		List<String> cleaned = safeList(values).stream()
			.filter(Objects::nonNull)
			.map(String::trim)
			.filter(value -> !value.isBlank())
			.limit(6)
			.toList();
		return cleaned.isEmpty() ? List.of("No specific evidence returned") : cleaned;
	}

	private List<RecommendationResponse> nonEmptyRecommendations(List<RecommendationResponse> recommendations) {
		List<RecommendationResponse> cleaned = safeList(recommendations).stream()
			.filter(Objects::nonNull)
			.filter(recommendation -> !blank(recommendation.message()))
			.limit(6)
			.map(recommendation -> new RecommendationResponse(
				fallback(recommendation.section(), "Resume"),
				normalizePriority(recommendation.priority()),
				recommendation.message()
			))
			.toList();
		return cleaned.isEmpty()
			? List.of(new RecommendationResponse("Resume", "high", "Add more specific evidence, scope, and measurable outcomes."))
			: cleaned;
	}

	private String normalizePriority(String priority) {
		String normalized = fallback(priority, "medium").toLowerCase(Locale.ROOT);
		if (normalized.equals("high") || normalized.equals("medium") || normalized.equals("low")) {
			return normalized;
		}
		return "medium";
	}

	private String normalizeDifficulty(String difficulty) {
		String normalized = fallback(difficulty, "Core").toLowerCase(Locale.ROOT);
		if (normalized.contains("warm")) {
			return "Warmup";
		}
		if (normalized.contains("deep")) {
			return "Deep Dive";
		}
		return "Core";
	}

	private int average(AssessmentScores scores) {
		return Math.round((scores.technicalDepth() + scores.impact() + scores.clarity() + scores.relevance() + scores.ats()) / 5.0f);
	}

	private int clampScore(int score) {
		return Math.max(0, Math.min(100, score));
	}

	private String slug(String value) {
		String slug = fallback(value, "question")
			.toLowerCase(Locale.ROOT)
			.replaceAll("[^a-z0-9]+", "-")
			.replaceAll("(^-|-$)", "");
		if (slug.isBlank()) {
			return UUID.randomUUID().toString();
		}
		return slug.length() > 44 ? slug.substring(0, 44) : slug;
	}

	private String truncate(String value, int limit) {
		String safeValue = fallback(value, "");
		if (safeValue.length() <= limit) {
			return safeValue;
		}
		return safeValue.substring(0, limit) + "\n[truncated]";
	}

	private String fallback(String value, String fallback) {
		return blank(value) ? fallback : value.trim();
	}

	private boolean blank(String value) {
		return value == null || value.isBlank();
	}

	private <T> List<T> safeList(List<T> values) {
		return values == null ? List.of() : values;
	}
}
