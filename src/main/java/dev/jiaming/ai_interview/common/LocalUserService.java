package dev.jiaming.ai_interview.common;

import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class LocalUserService {

	private static final String LOCAL_USER_EMAIL = "local@ai-interview.dev";

	private final JdbcTemplate jdbcTemplate;

	public LocalUserService(JdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	public UUID localUserId() {
		return jdbcTemplate.queryForObject(
			"""
				INSERT INTO ai_interview_app.app_users (id, email, display_name)
				VALUES (?, ?, ?)
				ON CONFLICT (email) DO UPDATE
				SET updated_at = now()
				RETURNING id
				""",
			UUID.class,
			UUID.nameUUIDFromBytes(LOCAL_USER_EMAIL.getBytes()),
			LOCAL_USER_EMAIL,
			"Local Candidate"
		);
	}
}
