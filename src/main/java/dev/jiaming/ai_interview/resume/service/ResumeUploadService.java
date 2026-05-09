package dev.jiaming.ai_interview.resume.service;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

import jakarta.annotation.PreDestroy;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.tika.Tika;
import org.apache.tika.exception.TikaException;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import dev.jiaming.ai_interview.resume.controller.ResumeChunkResponse;
import dev.jiaming.ai_interview.resume.controller.ResumeUploadResponse;
import dev.jiaming.ai_interview.resume.parser.ResumeTextNormalizer;
import dev.jiaming.ai_interview.resume.parser.SectionAwareTextChunker;

@Service
public class ResumeUploadService {

	private static final Logger log = LoggerFactory.getLogger(ResumeUploadService.class);

	private static final int MAX_PARSE_CHARS = 250_000;

	private static final long MAX_FILE_BYTES = 10 * 1024 * 1024;

	private static final long EXTRACTION_TIMEOUT_SECONDS = 20;

	private static final Set<String> ALLOWED_EXTENSIONS = Set.of("pdf", "doc", "docx", "txt", "text", "md", "markdown");

	private final Tika tika;

	private final ResumeTextNormalizer normalizer;

	private final SectionAwareTextChunker chunker;

	private final AtomicReference<ResumeUploadResponse> currentResume = new AtomicReference<>();

	private final ExecutorService extractionExecutor = Executors.newFixedThreadPool(2, new ResumeExtractionThreadFactory());

	public ResumeUploadService(ResumeTextNormalizer normalizer, SectionAwareTextChunker chunker) {
		this.tika = new Tika();
		this.tika.setMaxStringLength(MAX_PARSE_CHARS);
		this.normalizer = normalizer;
		this.chunker = chunker;
	}

	public ResumeUploadResponse process(MultipartFile file) {
		validate(file);

		long startedAt = System.nanoTime();
		byte[] fileBytes = readBytes(file);
		String detectedContentType = detectContentType(file, fileBytes);
		String extension = extension(file.getOriginalFilename());
		log.info(
			"resume_upload_start filename={} sizeBytes={} contentType={} detectedContentType={}",
			file.getOriginalFilename(),
			file.getSize(),
			file.getContentType(),
			detectedContentType
		);

		long extractStartedAt = System.nanoTime();
		String rawText = extractWithTimeout(file, fileBytes, extension, detectedContentType);
		long extractMillis = elapsedMillis(extractStartedAt);

		long normalizeStartedAt = System.nanoTime();
		String normalizedText = normalizer.normalize(rawText);
		long normalizeMillis = elapsedMillis(normalizeStartedAt);
		if (normalizedText.isBlank()) {
			throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "No readable resume text was extracted");
		}

		long chunkStartedAt = System.nanoTime();
		List<ResumeChunkResponse> chunks = chunker.chunk(normalizedText).stream()
			.map(chunk -> new ResumeChunkResponse(
				chunk.index(),
				chunk.section(),
				chunk.content(),
				chunk.content().length()
			))
			.toList();
		long chunkMillis = elapsedMillis(chunkStartedAt);

		ResumeUploadResponse response = new ResumeUploadResponse(
			UUID.randomUUID().toString(),
			file.getOriginalFilename(),
			file.getContentType(),
			detectedContentType,
			file.getSize(),
			rawText.length(),
			normalizedText.length(),
			normalizedText,
			chunks,
			Instant.now()
		);
		currentResume.set(response);
		log.info(
			"resume_upload_complete filename={} extractMs={} normalizeMs={} chunkMs={} totalMs={} chunks={} rawChars={} normalizedChars={}",
			file.getOriginalFilename(),
			extractMillis,
			normalizeMillis,
			chunkMillis,
			elapsedMillis(startedAt),
			chunks.size(),
			rawText.length(),
			normalizedText.length()
		);
		return response;
	}

	public Optional<ResumeUploadResponse> current() {
		return Optional.ofNullable(currentResume.get());
	}

	private void validate(MultipartFile file) {
		if (file == null || file.isEmpty()) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Resume file is required");
		}
		if (file.getSize() > MAX_FILE_BYTES) {
			throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "Resume file must be 10 MB or smaller");
		}

		String extension = extension(file.getOriginalFilename());
		if (!ALLOWED_EXTENSIONS.contains(extension)) {
			throw new ResponseStatusException(
				HttpStatus.UNSUPPORTED_MEDIA_TYPE,
				"Resume must be a PDF, DOC, DOCX, TXT, or Markdown file"
			);
		}
	}

	@PreDestroy
	void shutdownExtractionExecutor() {
		extractionExecutor.shutdownNow();
	}

	private byte[] readBytes(MultipartFile file) {
		try {
			return file.getBytes();
		}
		catch (IOException exception) {
			throw new ResumeExtractionException("Failed to read resume file", exception);
		}
	}

	private String extractWithTimeout(MultipartFile file, byte[] fileBytes, String extension, String detectedContentType) {
		CompletableFuture<String> extraction = CompletableFuture.supplyAsync(
			() -> extract(file, fileBytes, extension, detectedContentType),
			extractionExecutor
		);

		try {
			return extraction.get(EXTRACTION_TIMEOUT_SECONDS, TimeUnit.SECONDS);
		}
		catch (TimeoutException exception) {
			extraction.cancel(true);
			throw new ResumeExtractionException(
				"Resume text extraction timed out after " + EXTRACTION_TIMEOUT_SECONDS
					+ " seconds. This PDF may be scanned, encrypted, or malformed.",
				exception
			);
		}
		catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			throw new ResumeExtractionException("Resume text extraction was interrupted", exception);
		}
		catch (Exception exception) {
			Throwable cause = exception.getCause() == null ? exception : exception.getCause();
			if (cause instanceof ResumeExtractionException resumeExtractionException) {
				throw resumeExtractionException;
			}
			throw new ResumeExtractionException("Failed to extract resume text", cause);
		}
	}

	private String extract(MultipartFile file, byte[] fileBytes, String extension, String detectedContentType) {
		if ("pdf".equals(extension) || "application/pdf".equalsIgnoreCase(detectedContentType)) {
			return extractPdf(fileBytes);
		}

		Metadata metadata = new Metadata();
		if (file.getOriginalFilename() != null) {
			metadata.set(TikaCoreProperties.RESOURCE_NAME_KEY, file.getOriginalFilename());
		}
		if (file.getContentType() != null) {
			metadata.set(Metadata.CONTENT_TYPE, file.getContentType());
		}

		try (InputStream inputStream = new java.io.ByteArrayInputStream(fileBytes)) {
			return tika.parseToString(inputStream, metadata, MAX_PARSE_CHARS);
		}
		catch (IOException | TikaException exception) {
			throw new ResumeExtractionException("Failed to extract resume text", exception);
		}
	}

	private String extractPdf(byte[] fileBytes) {
		try (PDDocument document = Loader.loadPDF(fileBytes)) {
			if (document.isEncrypted()) {
				throw new ResumeExtractionException("Encrypted PDFs are not supported");
			}
			PDFTextStripper stripper = new PDFTextStripper();
			stripper.setSortByPosition(true);
			stripper.setSuppressDuplicateOverlappingText(true);
			return stripper.getText(document);
		}
		catch (IOException exception) {
			throw new ResumeExtractionException("Failed to extract PDF text", exception);
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

	private long elapsedMillis(long startedAt) {
		return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
	}

	private String extension(String filename) {
		if (filename == null || !filename.contains(".")) {
			return "";
		}
		return filename.substring(filename.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
	}

	private static final class ResumeExtractionThreadFactory implements ThreadFactory {

		private int threadNumber = 1;

		@Override
		public Thread newThread(Runnable runnable) {
			Thread thread = new Thread(runnable, "resume-extraction-" + threadNumber++);
			thread.setDaemon(true);
			return thread;
		}
	}
}
