package dev.jiaming.ai_interview.resume;

import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import jakarta.annotation.PreDestroy;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.tika.Tika;
import org.apache.tika.exception.TikaException;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;
import org.springframework.stereotype.Component;

@Component
public class ResumeTextExtractor {

	private static final int MAX_PARSE_CHARS = 250_000;

	private static final long EXTRACTION_TIMEOUT_SECONDS = 20;

	private final Tika tika;

	private final ExecutorService extractionExecutor = Executors.newFixedThreadPool(2, new ResumeExtractionThreadFactory());

	public ResumeTextExtractor() {
		this.tika = new Tika();
		this.tika.setMaxStringLength(MAX_PARSE_CHARS);
	}

	public String extract(ResumeFileContent fileContent) {
		CompletableFuture<String> extraction = CompletableFuture.supplyAsync(
			() -> extractWithoutTimeout(fileContent),
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

	@PreDestroy
	void shutdownExtractionExecutor() {
		extractionExecutor.shutdownNow();
	}

	private String extractWithoutTimeout(ResumeFileContent fileContent) {
		if ("pdf".equals(fileContent.extension()) || "application/pdf".equalsIgnoreCase(fileContent.detectedContentType())) {
			return extractPdf(fileContent.bytes());
		}

		Metadata metadata = new Metadata();
		if (fileContent.originalFilename() != null) {
			metadata.set(TikaCoreProperties.RESOURCE_NAME_KEY, fileContent.originalFilename());
		}
		if (fileContent.contentType() != null) {
			metadata.set(Metadata.CONTENT_TYPE, fileContent.contentType());
		}

		try (InputStream inputStream = new java.io.ByteArrayInputStream(fileContent.bytes())) {
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
