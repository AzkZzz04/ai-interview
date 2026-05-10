package dev.jiaming.ai_interview.coach;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import dev.jiaming.ai_interview.gemini.GeminiClient;
import dev.jiaming.ai_interview.gemini.GeminiException;
import dev.jiaming.ai_interview.rag.RagContextSnippet;
import dev.jiaming.ai_interview.rag.RagCorpus;
import dev.jiaming.ai_interview.rag.RagIndexingService;
import dev.jiaming.ai_interview.rag.RagRetrievalService;
import dev.jiaming.ai_interview.interview.InterviewPersistenceService;
import dev.jiaming.ai_interview.resume.ResumeUploadResponse;
import dev.jiaming.ai_interview.resume.SectionAwareTextChunker;
import dev.jiaming.ai_interview.resume.TextChunk;
import dev.jiaming.ai_interview.resume.ResumeUploadService;

@Service
public class AiResumeCoachService {

	private static final Logger log = LoggerFactory.getLogger(AiResumeCoachService.class);

	private static final int RESUME_PROMPT_LIMIT = 16_000;

	private static final int JOB_DESCRIPTION_PROMPT_LIMIT = 8_000;

	private static final int ANSWER_PROMPT_LIMIT = 4_000;

	private static final int RAG_TOP_K_PER_QUERY = 4;

	private final GeminiClient geminiClient;

	private final ObjectMapper objectMapper;

	private final ResumeUploadService resumeUploadService;

	private final SectionAwareTextChunker chunker;

	private final ObjectProvider<RagIndexingService> ragIndexingServiceProvider;

	private final ObjectProvider<RagRetrievalService> ragRetrievalServiceProvider;

	private final ObjectProvider<InterviewPersistenceService> interviewPersistenceServiceProvider;

	public AiResumeCoachService(
		GeminiClient geminiClient,
		ObjectMapper objectMapper,
		ResumeUploadService resumeUploadService,
		SectionAwareTextChunker chunker,
		ObjectProvider<RagIndexingService> ragIndexingServiceProvider,
		ObjectProvider<RagRetrievalService> ragRetrievalServiceProvider,
		ObjectProvider<InterviewPersistenceService> interviewPersistenceServiceProvider
	) {
		this.geminiClient = geminiClient;
		this.objectMapper = objectMapper;
		this.resumeUploadService = resumeUploadService;
		this.chunker = chunker;
		this.ragIndexingServiceProvider = ragIndexingServiceProvider;
		this.ragRetrievalServiceProvider = ragRetrievalServiceProvider;
		this.interviewPersistenceServiceProvider = interviewPersistenceServiceProvider;
	}

	public AssessmentResponse assess(AiAnalysisRequest request) {
		String resumeText = resolveResumeText(request.resumeText());
		RagPromptContext context = ragContext(
			request,
			resumeText,
			assessmentQueries(request),
			List.of("resume", "job_description"),
			14
		);
		String prompt = buildAssessmentPrompt(request, context);
		AssessmentResponse response = parse(geminiClient.generateJson(prompt), AssessmentResponse.class);
		AssessmentResponse normalizedResponse = normalizeAssessment(response, context.sourceContextIds());
		persistenceService().ifPresent(service -> service.saveAssessment(request, resumeText, normalizedResponse));
		return normalizedResponse;
	}

	public InterviewQuestionsResponse generateQuestions(AiAnalysisRequest request) {
		String resumeText = resolveResumeText(request.resumeText());
		RagPromptContext context = ragContext(
			request,
			resumeText,
			questionQueries(request),
			List.of("resume", "job_description"),
			16
		);
		String prompt = buildQuestionPrompt(request, context);
		InterviewQuestionsResponse response = parse(geminiClient.generateJson(prompt), InterviewQuestionsResponse.class);
		InterviewQuestionsResponse normalizedResponse = normalizeQuestions(response, context.sourceContextIds());
		persistenceService().ifPresent(service -> service.saveQuestions(request, resumeText, normalizedResponse));
		return normalizedResponse;
	}

	public AnswerFeedbackResponse scoreAnswer(AnswerFeedbackRequest request) {
		if (blank(request.answerText())) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Answer text is required");
		}

		String resumeText = resolveResumeText(request.resumeText());
		RagPromptContext context = ragContext(
			analysisRequest(request),
			resumeText,
			feedbackQueries(request),
			List.of("resume", "job_description"),
			10
		);
		String prompt = buildFeedbackPrompt(request, context);
		AnswerFeedbackResponse response = parse(geminiClient.generateJson(prompt), AnswerFeedbackResponse.class);
		AnswerFeedbackResponse normalizedResponse = normalizeFeedback(response, context.sourceContextIds());
		persistenceService().ifPresent(service -> service.saveAnswer(request, resumeText, normalizedResponse));
		return normalizedResponse;
	}

	private java.util.Optional<InterviewPersistenceService> persistenceService() {
		return java.util.Optional.ofNullable(interviewPersistenceServiceProvider.getIfAvailable());
	}

	private String buildAssessmentPrompt(AiAnalysisRequest request, RagPromptContext context) {
		return """
			You are a senior technical recruiter and engineering interviewer.
			Assess this tech resume for the target role using only the retrieved context below.
			Use job description context only if it appears in the retrieved context.
			Be direct, concrete, and evidence-based. Do not invent experience that is not in the retrieved context.
			If evidence is missing, mark it as a gap.
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
			  ],
			  "sourceContextIds": ["resume:0"]
			}
			All scores must be integers from 0 to 100.

			Target role: %s
			Seniority: %s

			Retrieved context:
			%s
			""".formatted(
			fallback(request.targetRole(), "Software Engineer"),
			fallback(request.seniority(), "Mid-level"),
			context.context()
		);
	}

	private String buildQuestionPrompt(AiAnalysisRequest request, RagPromptContext context) {
		return """
			You are generating a technical interview set for a candidate.
			Use only the retrieved resume and job-description context below.
			Create questions that test the candidate's actual claimed experience and the target role.
			Do not assume facts outside the retrieved context.
			Include a mix of resume deep dive, technical fundamentals, system design, project architecture, debugging, collaboration, and role-specific tooling.
			Return only valid JSON matching this shape:
			{
			  "questions": [
			    {
			      "id": "stable-slug",
			      "category": "Resume Deep Dive",
			      "difficulty": "Warmup",
			      "questionText": "question",
			      "expectedSignals": ["signal 1", "signal 2", "signal 3"],
			      "sourceContextIds": ["resume:0"]
			    }
			  ]
			}
			Generate exactly 8 questions. Difficulty must be one of Warmup, Core, Deep Dive.

			Target role: %s
			Seniority: %s

			Retrieved context:
			%s
			""".formatted(
			fallback(request.targetRole(), "Software Engineer"),
			fallback(request.seniority(), "Mid-level"),
			context.context()
		);
	}

	private String buildFeedbackPrompt(AnswerFeedbackRequest request, RagPromptContext context) {
		return """
			You are coaching a candidate after one interview answer.
			Score the answer against the question, expected signals, and retrieved context. Be specific and actionable.
			Do not reward claims that are not supported by the answer.
			Do not invent resume details beyond the retrieved context.
			Return only valid JSON matching this shape:
			{
			  "score": 0,
			  "summary": "one sentence",
			  "nextStep": "one concrete next practice step",
			  "strengths": ["1-3 strengths"],
			  "gaps": ["1-3 gaps"],
			  "betterAnswerOutline": ["context", "action", "tradeoff", "result"],
			  "followUpQuestion": "one follow-up question",
			  "sourceContextIds": ["resume:0"]
			}
			Score must be an integer from 0 to 100.

			Target role: %s
			Seniority: %s
			Question category: %s
			Question: %s
			Expected signals: %s

			Retrieved context:
			%s

			Candidate answer:
			%s
			""".formatted(
			fallback(request.targetRole(), "Software Engineer"),
			fallback(request.seniority(), "Mid-level"),
			fallback(request.category(), "Interview"),
			fallback(request.questionText(), ""),
			String.join(", ", safeList(request.expectedSignals())),
			context.context(),
			truncate(request.answerText(), ANSWER_PROMPT_LIMIT)
		);
	}

	private RagPromptContext ragContext(
		AiAnalysisRequest request,
		String resumeText,
		List<String> queries,
		List<String> sourceTypes,
		int maxSnippets
	) {
		RagIndexingService indexingService = ragIndexingServiceProvider.getIfAvailable();
		RagRetrievalService retrievalService = ragRetrievalServiceProvider.getIfAvailable();
		if (indexingService != null && retrievalService != null) {
			try {
				RagCorpus corpus = indexingService.index(
					resumeText,
					fallback(request.jobDescription(), ""),
					fallback(request.targetRole(), "Software Engineer"),
					fallback(request.seniority(), "Mid-level")
				);
				List<RagContextSnippet> snippets = retrieveSnippets(retrievalService, corpus, queries, sourceTypes, maxSnippets);
				if (!snippets.isEmpty()) {
					log.info(
						"rag_context_ready corpusId={} vectorBacked=true snippets={} sourceContextIds={}",
						corpus.id(),
						snippets.size(),
						sourceContextIds(snippets)
					);
					return new RagPromptContext(
						corpus.id(),
						formatSnippets(snippets, maxSnippets),
						sourceContextIds(snippets),
						true
					);
				}
			}
			catch (RuntimeException exception) {
				log.warn("rag_context_fallback reason={}", exception.getMessage());
			}
		}

		List<RagContextSnippet> fallbackSnippets = fallbackSnippets(resumeText, request.jobDescription(), maxSnippets);
		log.info("rag_context_ready corpusId=local-chunks vectorBacked=false snippets={}", fallbackSnippets.size());
		return new RagPromptContext(
			"local-chunks",
			formatSnippets(fallbackSnippets, maxSnippets),
			sourceContextIds(fallbackSnippets),
			false
		);
	}

	private List<RagContextSnippet> retrieveSnippets(
		RagRetrievalService retrievalService,
		RagCorpus corpus,
		List<String> queries,
		List<String> sourceTypes,
		int maxSnippets
	) {
		Map<String, RagContextSnippet> snippetsByContextId = new LinkedHashMap<>();
		for (String query : queries) {
			String retrievalQuery = fallback(query, "").trim();
			if (blank(retrievalQuery)) {
				continue;
			}
			List<RagContextSnippet> snippets = retrievalService.retrieve(
				retrievalQuery,
				corpus.id(),
				sourceTypes,
				RAG_TOP_K_PER_QUERY
			);
			for (RagContextSnippet snippet : snippets) {
				snippetsByContextId.putIfAbsent(snippet.sourceContextId(), snippet);
				if (snippetsByContextId.size() >= maxSnippets) {
					return new ArrayList<>(snippetsByContextId.values());
				}
			}
		}
		return new ArrayList<>(snippetsByContextId.values());
	}

	private List<String> assessmentQueries(AiAnalysisRequest request) {
		String role = fallback(request.targetRole(), "Software Engineer");
		String seniority = fallback(request.seniority(), "Mid-level");
		String jobDescription = fallback(request.jobDescription(), "");
		return List.of(
			"technical depth systems ownership architecture complexity " + role + " " + seniority,
			"measurable impact metrics scale latency reliability cost adoption outcomes",
			"role alignment required skills must have requirements " + role + " " + jobDescription,
			"resume gaps missing evidence weak bullets seniority signal " + role + " " + seniority
		);
	}

	private List<String> questionQueries(AiAnalysisRequest request) {
		String role = fallback(request.targetRole(), "Software Engineer");
		String seniority = fallback(request.seniority(), "Mid-level");
		String jobDescription = fallback(request.jobDescription(), "");
		return List.of(
			"strongest projects ownership technical complexity " + role + " " + seniority,
			"weakest resume areas missing detail interview probe " + role,
			"system design architecture scaling data flow production tradeoffs",
			"debugging incident response observability database cache production",
			"collaboration leadership stakeholder tradeoff communication",
			"job description requirements role specific tooling " + jobDescription
		);
	}

	private List<String> feedbackQueries(AnswerFeedbackRequest request) {
		return List.of(
			fallback(request.questionText(), ""),
			String.join(" ", safeList(request.expectedSignals())),
			fallback(request.category(), "") + " " + fallback(request.targetRole(), ""),
			"source project context expected evidence answer evaluation"
		);
	}

	private AiAnalysisRequest analysisRequest(AnswerFeedbackRequest request) {
		return new AiAnalysisRequest(
			request.resumeText(),
			request.jobDescription(),
			request.targetRole(),
			request.seniority()
		);
	}

	private List<RagContextSnippet> fallbackSnippets(String resumeText, String jobDescription, int maxSnippets) {
		List<RagContextSnippet> snippets = new ArrayList<>();
		for (TextChunk chunk : chunker.chunk(resumeText)) {
			snippets.add(new RagContextSnippet(
				"local-resume-" + chunk.index(),
				chunk.content(),
				Map.of(
					"contextId", "resume:" + chunk.index(),
					"sourceType", "resume",
					"section", chunk.section(),
					"chunkIndex", chunk.index()
				),
				null
			));
			if (snippets.size() >= maxSnippets) {
				return snippets;
			}
		}

		for (TextChunk chunk : chunker.chunk(fallback(jobDescription, ""))) {
			snippets.add(new RagContextSnippet(
				"local-job-description-" + chunk.index(),
				chunk.content(),
				Map.of(
					"contextId", "job_description:" + chunk.index(),
					"sourceType", "job_description",
					"section", chunk.section(),
					"chunkIndex", chunk.index()
				),
				null
			));
			if (snippets.size() >= maxSnippets) {
				return snippets;
			}
		}

		return snippets;
	}

	private String formatSnippets(List<RagContextSnippet> snippets, int maxSnippets) {
		if (snippets.isEmpty()) {
			return "No retrieved context was available.";
		}
		return snippets.stream()
			.limit(maxSnippets)
			.map(this::formatSnippet)
			.reduce((left, right) -> left + "\n\n" + right)
			.orElse("No retrieved context was available.");
	}

	private String formatSnippet(RagContextSnippet snippet) {
		Map<String, Object> metadata = snippet.metadata() == null ? Map.of() : snippet.metadata();
		return """
			[contextId=%s source=%s section=%s score=%s]
			%s
			""".formatted(
			snippet.sourceContextId(),
			metadataValue(metadata, "sourceType"),
			metadataValue(metadata, "section"),
			snippet.score() == null ? "n/a" : "%.4f".formatted(snippet.score()),
			truncate(snippet.content(), 1_200)
		).trim();
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

	private AssessmentResponse normalizeAssessment(AssessmentResponse response, List<String> fallbackSourceContextIds) {
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
			"gemini",
			sourceContextIds(response.sourceContextIds(), fallbackSourceContextIds)
		);
	}

	private InterviewQuestionsResponse normalizeQuestions(InterviewQuestionsResponse response, List<String> fallbackSourceContextIds) {
		List<InterviewQuestionResponse> questions = safeList(response.questions()).stream()
			.filter(question -> !blank(question.questionText()))
			.limit(12)
			.map(question -> new InterviewQuestionResponse(
				fallback(question.id(), slug(question.category() + "-" + question.questionText())),
				fallback(question.category(), "Interview"),
				normalizeDifficulty(question.difficulty()),
				question.questionText(),
				nonEmpty(question.expectedSignals()),
				sourceContextIds(question.sourceContextIds(), fallbackSourceContextIds)
			))
			.toList();
		return new InterviewQuestionsResponse(questions, "gemini");
	}

	private AnswerFeedbackResponse normalizeFeedback(AnswerFeedbackResponse response, List<String> fallbackSourceContextIds) {
		return new AnswerFeedbackResponse(
			clampScore(response.score()),
			fallback(response.summary(), "The answer was scored, but Gemini did not provide a summary."),
			fallback(response.nextStep(), "Add clearer structure, technical detail, and measurable outcomes."),
			nonEmpty(response.strengths()),
			nonEmpty(response.gaps()),
			nonEmpty(response.betterAnswerOutline()),
			fallback(response.followUpQuestion(), ""),
			"gemini",
			sourceContextIds(response.sourceContextIds(), fallbackSourceContextIds)
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

	private List<String> sourceContextIds(List<String> responseContextIds, List<String> fallbackContextIds) {
		List<String> cleaned = safeList(responseContextIds).stream()
			.filter(Objects::nonNull)
			.map(String::trim)
			.filter(value -> !value.isBlank())
			.distinct()
			.limit(12)
			.toList();
		if (!cleaned.isEmpty()) {
			return cleaned;
		}
		return safeList(fallbackContextIds).stream()
			.filter(Objects::nonNull)
			.map(String::trim)
			.filter(value -> !value.isBlank())
			.distinct()
			.limit(12)
			.toList();
	}

	private List<String> sourceContextIds(List<RagContextSnippet> snippets) {
		return snippets.stream()
			.map(RagContextSnippet::sourceContextId)
			.filter(value -> !blank(value))
			.distinct()
			.limit(12)
			.toList();
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

	private String metadataValue(Map<String, Object> metadata, String key) {
		Object value = metadata.get(key);
		return value == null ? "unknown" : value.toString();
	}

	private record RagPromptContext(
		String corpusId,
		String context,
		List<String> sourceContextIds,
		boolean vectorBacked
	) {
	}
}
