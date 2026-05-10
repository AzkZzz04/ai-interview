package dev.jiaming.ai_interview.resume;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import dev.jiaming.ai_interview.common.LocalUserService;

@Service
public class ResumePersistenceService {

	private final JdbcTemplate jdbcTemplate;

	private final LocalUserService localUserService;

	public ResumePersistenceService(JdbcTemplate jdbcTemplate, LocalUserService localUserService) {
		this.jdbcTemplate = jdbcTemplate;
		this.localUserService = localUserService;
	}

	@Transactional
	public ResumeUploadResponse save(
		String originalFilename,
		String contentType,
		String detectedContentType,
		long sizeBytes,
		String storageKey,
		String rawText,
		String normalizedText,
		List<ResumeChunkResponse> chunks
	) {
		UUID resumeId = UUID.randomUUID();
		UUID userId = localUserService.localUserId();
		jdbcTemplate.update(
			"""
				INSERT INTO ai_interview_app.resumes (
					id, user_id, original_filename, content_type, detected_content_type,
					size_bytes, storage_key, raw_text, normalized_text, parsed_skills
				)
				VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, '[]'::jsonb)
				""",
			resumeId,
			userId,
			originalFilename,
			contentType,
			detectedContentType,
			sizeBytes,
			storageKey,
			rawText,
			normalizedText
		);

		for (ResumeChunkResponse chunk : chunks) {
			jdbcTemplate.update(
				"""
					INSERT INTO ai_interview_app.resume_chunks (id, resume_id, chunk_index, section, content, metadata)
					VALUES (?, ?, ?, ?, ?, jsonb_build_object('sourceType', 'resume', 'contextId', ?))
					""",
				UUID.randomUUID(),
				resumeId,
				chunk.index(),
				chunk.section(),
				chunk.content(),
				"resume:" + chunk.index()
			);
		}

		return new ResumeUploadResponse(
			resumeId.toString(),
			originalFilename,
			contentType,
			detectedContentType,
			sizeBytes,
			rawText.length(),
			normalizedText.length(),
			normalizedText,
			chunks,
			Instant.now()
		);
	}

	public Optional<ResumeUploadResponse> findLatest() {
		UUID userId = localUserService.localUserId();
		List<ResumeUploadResponse> resumes = jdbcTemplate.query(
			"""
				SELECT id, original_filename, content_type, detected_content_type, size_bytes,
				       raw_text, normalized_text, created_at
				FROM ai_interview_app.resumes
				WHERE user_id = ?
				ORDER BY created_at DESC
				LIMIT 1
				""",
			(rs, rowNum) -> toUploadResponse(rs),
			userId
		);
		return resumes.stream().findFirst();
	}

	public Optional<PersistedResume> findLatestSummary() {
		UUID userId = localUserService.localUserId();
		List<PersistedResume> resumes = jdbcTemplate.query(
			"""
				SELECT id, normalized_text
				FROM ai_interview_app.resumes
				WHERE user_id = ?
				ORDER BY created_at DESC
				LIMIT 1
				""",
			(rs, rowNum) -> new PersistedResume(
				rs.getObject("id", UUID.class),
				rs.getString("normalized_text")
			),
			userId
		);
		return resumes.stream().findFirst();
	}

	@Transactional
	public PersistedResume findOrCreateTextResume(String resumeText, List<ResumeChunkResponse> chunks) {
		String normalizedText = resumeText == null ? "" : resumeText.trim();
		Optional<PersistedResume> latest = findLatestSummary()
			.filter(resume -> normalizedText.equals(resume.normalizedText()));
		if (latest.isPresent()) {
			return latest.get();
		}

		ResumeUploadResponse saved = save(
			"pasted-resume.txt",
			"text/plain",
			"text/plain",
			normalizedText.getBytes(java.nio.charset.StandardCharsets.UTF_8).length,
			null,
			normalizedText,
			normalizedText,
			chunks
		);
		return new PersistedResume(UUID.fromString(saved.id()), saved.normalizedText());
	}

	private ResumeUploadResponse toUploadResponse(ResultSet rs) throws SQLException {
		UUID resumeId = rs.getObject("id", UUID.class);
		String rawText = value(rs.getString("raw_text"));
		String normalizedText = value(rs.getString("normalized_text"));
		return new ResumeUploadResponse(
			resumeId.toString(),
			rs.getString("original_filename"),
			rs.getString("content_type"),
			rs.getString("detected_content_type"),
			rs.getLong("size_bytes"),
			rawText.length(),
			normalizedText.length(),
			normalizedText,
			findChunks(resumeId),
			rs.getTimestamp("created_at").toInstant()
		);
	}

	private List<ResumeChunkResponse> findChunks(UUID resumeId) {
		return jdbcTemplate.query(
			"""
				SELECT chunk_index, section, content
				FROM ai_interview_app.resume_chunks
				WHERE resume_id = ?
				ORDER BY chunk_index
				""",
			(rs, rowNum) -> new ResumeChunkResponse(
				rs.getInt("chunk_index"),
				rs.getString("section"),
				rs.getString("content"),
				rs.getString("content").length()
			),
			resumeId
		);
	}

	private String value(String value) {
		return value == null ? "" : value;
	}
}
