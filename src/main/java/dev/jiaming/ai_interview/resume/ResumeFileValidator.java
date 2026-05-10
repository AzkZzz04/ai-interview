package dev.jiaming.ai_interview.resume;

import java.util.Locale;
import java.util.Set;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@Component
public class ResumeFileValidator {

	private static final long MAX_FILE_BYTES = 10 * 1024 * 1024;

	private static final Set<String> ALLOWED_EXTENSIONS = Set.of("pdf", "doc", "docx", "txt", "text", "md", "markdown");

	public void validate(MultipartFile file) {
		if (file == null || file.isEmpty()) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Resume file is required");
		}
		if (file.getSize() > MAX_FILE_BYTES) {
			throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "Resume file must be 10 MB or smaller");
		}

		if (!ALLOWED_EXTENSIONS.contains(extension(file.getOriginalFilename()))) {
			throw new ResponseStatusException(
				HttpStatus.UNSUPPORTED_MEDIA_TYPE,
				"Resume must be a PDF, DOC, DOCX, TXT, or Markdown file"
			);
		}
	}

	private String extension(String filename) {
		if (filename == null || !filename.contains(".")) {
			return "";
		}
		return filename.substring(filename.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
	}
}
