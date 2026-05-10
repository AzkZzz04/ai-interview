package dev.jiaming.ai_interview.resume;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ResumeTextNormalizerTests {

	private final ResumeTextNormalizer normalizer = new ResumeTextNormalizer();

	@Test
	void normalizesWhitespaceAndBlankLines() {
		String raw = "  Jane   Doe\r\n\r\n\r\nSkills\tJava   Spring\r\n  ";

		String normalized = normalizer.normalize(raw);

		assertThat(normalized).isEqualTo("Jane Doe\n\nSkills Java Spring");
	}

	@Test
	void returnsEmptyStringForBlankInput() {
		assertThat(normalizer.normalize("   ")).isEmpty();
		assertThat(normalizer.normalize(null)).isEmpty();
	}
}
