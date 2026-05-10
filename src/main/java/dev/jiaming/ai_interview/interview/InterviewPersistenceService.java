package dev.jiaming.ai_interview.interview;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import dev.jiaming.ai_interview.coach.AiAnalysisRequest;
import dev.jiaming.ai_interview.coach.AnswerFeedbackRequest;
import dev.jiaming.ai_interview.coach.AnswerFeedbackResponse;
import dev.jiaming.ai_interview.coach.AssessmentResponse;
import dev.jiaming.ai_interview.coach.InterviewQuestionResponse;
import dev.jiaming.ai_interview.coach.InterviewQuestionsResponse;
import dev.jiaming.ai_interview.common.LocalUserService;
import dev.jiaming.ai_interview.resume.ResumeChunkResponse;
import dev.jiaming.ai_interview.resume.ResumeTextNormalizer;
import dev.jiaming.ai_interview.resume.SectionAwareTextChunker;
import dev.jiaming.ai_interview.resume.PersistedResume;
import dev.jiaming.ai_interview.resume.ResumePersistenceService;

@Service
public class InterviewPersistenceService {

	private static final String PROMPT_NAME = "rag-grounded";

	private final JdbcTemplate jdbcTemplate;

	private final ObjectMapper objectMapper;

	private final LocalUserService localUserService;

	private final ResumePersistenceService resumePersistenceService;

	private final ResumeTextNormalizer normalizer;

	private final SectionAwareTextChunker chunker;

	public InterviewPersistenceService(
		JdbcTemplate jdbcTemplate,
		ObjectMapper objectMapper,
		LocalUserService localUserService,
		ResumePersistenceService resumePersistenceService,
		ResumeTextNormalizer normalizer,
		SectionAwareTextChunker chunker
	) {
		this.jdbcTemplate = jdbcTemplate;
		this.objectMapper = objectMapper;
		this.localUserService = localUserService;
		this.resumePersistenceService = resumePersistenceService;
		this.normalizer = normalizer;
		this.chunker = chunker;
	}

	@Transactional
	public void saveAssessment(AiAnalysisRequest request, String resumeText, AssessmentResponse response) {
		UUID userId = localUserService.localUserId();
		PersistedResume resume = resumeFor(resumeText);
		UUID jobDescriptionId = saveJobDescription(userId, request.jobDescription()).orElse(null);
		jdbcTemplate.update(
			"""
				INSERT INTO ai_interview_app.resume_assessments (
					id, user_id, resume_id, job_description_id, overall_score,
					technical_depth_score, impact_score, clarity_score, relevance_score, ats_score,
					strengths, weaknesses, recommendations, model_name, prompt_name, input_hash
				)
				VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?::jsonb, ?, ?, ?)
				""",
			UUID.randomUUID(),
			userId,
			resume.id(),
			jobDescriptionId,
			response.overallScore(),
			response.scores().technicalDepth(),
			response.scores().impact(),
			response.scores().clarity(),
			response.scores().relevance(),
			response.scores().ats(),
			json(response.strengths()),
			json(response.weaknesses()),
			json(response.recommendations()),
			model(response.modelProvider()),
			PROMPT_NAME,
			hash(request.resumeText(), request.jobDescription(), request.targetRole(), request.seniority())
		);
	}

	@Transactional
	public void saveQuestions(AiAnalysisRequest request, String resumeText, InterviewQuestionsResponse response) {
		UUID userId = localUserService.localUserId();
		PersistedResume resume = resumeFor(resumeText);
		UUID jobDescriptionId = saveJobDescription(userId, request.jobDescription()).orElse(null);
		UUID sessionId = UUID.randomUUID();
		jdbcTemplate.update(
			"""
				INSERT INTO ai_interview_app.interview_sessions (
					id, user_id, resume_id, job_description_id, target_role, seniority, status
				)
				VALUES (?, ?, ?, ?, ?, ?, 'READY')
				""",
			sessionId,
			userId,
			resume.id(),
			jobDescriptionId,
			request.targetRole(),
			request.seniority()
		);

		int index = 0;
		for (InterviewQuestionResponse question : response.questions()) {
			saveQuestion(sessionId, question, index++);
		}
	}

	@Transactional
	public void saveAnswer(AnswerFeedbackRequest request, String resumeText, AnswerFeedbackResponse response) {
		UUID userId = localUserService.localUserId();
		UUID questionId = findLatestQuestion(userId, request.questionText())
			.orElseGet(() -> createQuestionForAnswer(userId, request, resumeText));
		jdbcTemplate.update(
			"""
				INSERT INTO ai_interview_app.interview_answers (
					id, question_id, answer_text, score, feedback, model_name, prompt_name
				)
				VALUES (?, ?, ?, ?, ?::jsonb, ?, ?)
				""",
			UUID.randomUUID(),
			questionId,
			request.answerText(),
			response.score(),
			json(Map.of(
				"summary", response.summary(),
				"nextStep", response.nextStep(),
				"strengths", response.strengths(),
				"gaps", response.gaps(),
				"betterAnswerOutline", response.betterAnswerOutline(),
				"followUpQuestion", response.followUpQuestion(),
				"sourceContextIds", response.sourceContextIds()
			)),
			model(response.modelProvider()),
			PROMPT_NAME
		);
	}

	private PersistedResume resumeFor(String resumeText) {
		return resumePersistenceService.findOrCreateTextResume(
			resumeText,
			chunker.chunk(resumeText).stream()
				.map(chunk -> new ResumeChunkResponse(
					chunk.index(),
					chunk.section(),
					chunk.content(),
					chunk.content().length()
				))
				.toList()
		);
	}

	private Optional<UUID> saveJobDescription(UUID userId, String jobDescription) {
		if (jobDescription == null || jobDescription.isBlank()) {
			return Optional.empty();
		}
		String normalizedText = normalizer.normalize(jobDescription);
		if (normalizedText.isBlank()) {
			return Optional.empty();
		}

		UUID jobDescriptionId = UUID.randomUUID();
		jdbcTemplate.update(
			"""
				INSERT INTO ai_interview_app.job_descriptions (
					id, user_id, raw_text, normalized_text, parsed_requirements
				)
				VALUES (?, ?, ?, ?, '[]'::jsonb)
				""",
			jobDescriptionId,
			userId,
			jobDescription,
			normalizedText
		);

		chunker.chunk(normalizedText).forEach(chunk -> jdbcTemplate.update(
			"""
				INSERT INTO ai_interview_app.job_description_chunks (
					id, job_description_id, chunk_index, section, content, metadata
				)
				VALUES (?, ?, ?, ?, ?, jsonb_build_object('sourceType', 'job_description', 'contextId', ?))
				""",
			UUID.randomUUID(),
			jobDescriptionId,
			chunk.index(),
			chunk.section(),
			chunk.content(),
			"job_description:" + chunk.index()
		));

		return Optional.of(jobDescriptionId);
	}

	private void saveQuestion(UUID sessionId, InterviewQuestionResponse question, int orderIndex) {
		jdbcTemplate.update(
			"""
				INSERT INTO ai_interview_app.interview_questions (
					id, session_id, question_text, category, difficulty,
					expected_signals, source_context, order_index
				)
				VALUES (?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?)
				""",
			UUID.randomUUID(),
			sessionId,
			question.questionText(),
			question.category(),
			question.difficulty(),
			json(question.expectedSignals()),
			json(question.sourceContextIds()),
			orderIndex
		);
	}

	private Optional<UUID> findLatestQuestion(UUID userId, String questionText) {
		if (questionText == null || questionText.isBlank()) {
			return Optional.empty();
		}
		List<UUID> questionIds = jdbcTemplate.query(
			"""
				SELECT q.id
				FROM ai_interview_app.interview_questions q
				JOIN ai_interview_app.interview_sessions s ON s.id = q.session_id
				WHERE s.user_id = ? AND q.question_text = ?
				ORDER BY q.created_at DESC
				LIMIT 1
				""",
			(rs, rowNum) -> rs.getObject("id", UUID.class),
			userId,
			questionText
		);
		return questionIds.stream().findFirst();
	}

	private UUID createQuestionForAnswer(UUID userId, AnswerFeedbackRequest request, String resumeText) {
		PersistedResume resume = resumeFor(resumeText);
		UUID jobDescriptionId = saveJobDescription(userId, request.jobDescription()).orElse(null);
		UUID sessionId = UUID.randomUUID();
		jdbcTemplate.update(
			"""
				INSERT INTO ai_interview_app.interview_sessions (
					id, user_id, resume_id, job_description_id, target_role, seniority, status
				)
				VALUES (?, ?, ?, ?, ?, ?, 'READY')
				""",
			sessionId,
			userId,
			resume.id(),
			jobDescriptionId,
			request.targetRole(),
			request.seniority()
		);
		UUID questionId = UUID.randomUUID();
		jdbcTemplate.update(
			"""
				INSERT INTO ai_interview_app.interview_questions (
					id, session_id, question_text, category, difficulty,
					expected_signals, source_context, order_index
				)
				VALUES (?, ?, ?, ?, ?, ?::jsonb, '[]'::jsonb, 0)
				""",
			questionId,
			sessionId,
			request.questionText(),
			request.category() == null || request.category().isBlank() ? "Interview" : request.category(),
			"Core",
			json(request.expectedSignals())
		);
		return questionId;
	}

	private String json(Object value) {
		try {
			return objectMapper.writeValueAsString(value == null ? List.of() : value);
		}
		catch (JsonProcessingException exception) {
			throw new IllegalStateException("Could not serialize persistence payload", exception);
		}
	}

	private String model(String modelProvider) {
		return modelProvider == null || modelProvider.isBlank() ? "gemini" : modelProvider;
	}

	private String hash(String... values) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			for (String value : values) {
				digest.update((value == null ? "" : value).getBytes(StandardCharsets.UTF_8));
				digest.update((byte) 0);
			}
			return HexFormat.of().formatHex(digest.digest());
		}
		catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 is unavailable", exception);
		}
	}
}
