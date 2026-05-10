package dev.jiaming.ai_interview.resume;

import java.util.Arrays;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

@Component
public class ResumeTextNormalizer {

	public String normalize(String rawText) {
		if (rawText == null || rawText.isBlank()) {
			return "";
		}

		String normalizedLines = Arrays.stream(rawText.replace("\r\n", "\n").replace('\r', '\n').split("\n"))
			.map(this::normalizeLine)
			.collect(Collectors.joining("\n"));

		return normalizedLines
			.replaceAll("\\n{3,}", "\n\n")
			.trim();
	}

	private String normalizeLine(String line) {
		return line
			.replace('\t', ' ')
			.replaceAll("\\s{2,}", " ")
			.trim();
	}
}
