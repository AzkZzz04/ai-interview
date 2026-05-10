package dev.jiaming.ai_interview.resume;

import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;

import org.apache.tika.Tika;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

@Component
public class ResumeFileReader {

	private final Tika tika = new Tika();

	public ResumeFileContent read(MultipartFile file) {
		byte[] bytes = readBytes(file);
		return new ResumeFileContent(
			file.getOriginalFilename(),
			file.getContentType(),
			file.getSize(),
			bytes,
			detectContentType(file, bytes),
			extension(file.getOriginalFilename())
		);
	}

	private byte[] readBytes(MultipartFile file) {
		try {
			return file.getBytes();
		}
		catch (IOException exception) {
			throw new ResumeExtractionException("Failed to read resume file", exception);
		}
	}

	private String detectContentType(MultipartFile file, byte[] fileBytes) {
		Metadata metadata = new Metadata();
		if (file.getOriginalFilename() != null) {
			metadata.set(TikaCoreProperties.RESOURCE_NAME_KEY, file.getOriginalFilename());
		}

		try (InputStream inputStream = new java.io.ByteArrayInputStream(fileBytes)) {
			return tika.detect(inputStream, metadata);
		}
		catch (IOException exception) {
			return file.getContentType();
		}
	}

	private String extension(String filename) {
		if (filename == null || !filename.contains(".")) {
			return "";
		}
		return filename.substring(filename.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
	}
}
