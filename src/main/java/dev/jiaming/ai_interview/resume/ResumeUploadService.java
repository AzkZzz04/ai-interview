package dev.jiaming.ai_interview.resume;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import dev.jiaming.ai_interview.common.RedisRequestGuard;

@Service
public class ResumeUploadService {

	private static final Logger log = LoggerFactory.getLogger(ResumeUploadService.class);

	private final ResumeFileValidator validator;

	private final ResumeFileReader fileReader;

	private final ResumeTextExtractor textExtractor;

	private final ResumeTextNormalizer normalizer;

	private final SectionAwareTextChunker chunker;

	private final ResumeStorageService storageService;

	private final RedisRequestGuard redisRequestGuard;

	private final ResumePersistenceService resumePersistenceService;

	private final AtomicReference<ResumeUploadResponse> currentResume = new AtomicReference<>();

	@Autowired
	public ResumeUploadService(
		ResumeFileValidator validator,
		ResumeFileReader fileReader,
		ResumeTextExtractor textExtractor,
		ResumeTextNormalizer normalizer,
		SectionAwareTextChunker chunker,
		ResumeStorageService storageService,
		RedisRequestGuard redisRequestGuard,
		ObjectProvider<ResumePersistenceService> resumePersistenceServiceProvider
	) {
		this.validator = validator;
		this.fileReader = fileReader;
		this.textExtractor = textExtractor;
		this.normalizer = normalizer;
		this.chunker = chunker;
		this.storageService = storageService;
		this.redisRequestGuard = redisRequestGuard;
		this.resumePersistenceService = resumePersistenceServiceProvider.getIfAvailable();
	}

	public ResumeUploadService(ResumeTextNormalizer normalizer, SectionAwareTextChunker chunker) {
		this.validator = new ResumeFileValidator();
		this.fileReader = new ResumeFileReader();
		this.textExtractor = new ResumeTextExtractor();
		this.normalizer = normalizer;
		this.chunker = chunker;
		this.storageService = null;
		this.redisRequestGuard = null;
		this.resumePersistenceService = null;
	}

	public ResumeUploadResponse process(MultipartFile file) {
		validator.validate(file);
		long startedAt = System.nanoTime();
		ResumeFileContent fileContent = fileReader.read(file);
		if (redisRequestGuard != null) {
			ResumeUploadResponse response = redisRequestGuard.withIdempotentRetryCache(
				"resume-upload",
				uploadFingerprint(fileContent),
				ResumeUploadResponse.class,
				() -> {
					redisRequestGuard.assertUploadAllowed();
					return process(fileContent, startedAt);
				}
			);
			currentResume.set(response);
			return response;
		}

		ResumeUploadResponse response = process(fileContent, startedAt);
		currentResume.set(response);
		return response;
	}

	private ResumeUploadResponse process(ResumeFileContent fileContent, long startedAt) {
		log.info(
			"resume_upload_start filename={} sizeBytes={} contentType={} detectedContentType={}",
			fileContent.originalFilename(),
			fileContent.sizeBytes(),
			fileContent.contentType(),
			fileContent.detectedContentType()
		);

		long extractStartedAt = System.nanoTime();
		String rawText = textExtractor.extract(fileContent);
		long extractMillis = elapsedMillis(extractStartedAt);

		long normalizeStartedAt = System.nanoTime();
		String normalizedText = normalizer.normalize(rawText);
		long normalizeMillis = elapsedMillis(normalizeStartedAt);
		if (normalizedText.isBlank()) {
			throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "No readable resume text was extracted");
		}

		long chunkStartedAt = System.nanoTime();
		List<ResumeChunkResponse> chunks = chunksFor(normalizedText);
		long chunkMillis = elapsedMillis(chunkStartedAt);

		String storageKey = storageService == null ? null : storageService.store(fileContent);
		ResumeUploadResponse response = resumePersistenceService == null
			? new ResumeUploadResponse(
				UUID.randomUUID().toString(),
				fileContent.originalFilename(),
				fileContent.contentType(),
				fileContent.detectedContentType(),
				fileContent.sizeBytes(),
				rawText.length(),
				normalizedText.length(),
				normalizedText,
				chunks,
				Instant.now()
			)
			: resumePersistenceService.save(
				fileContent.originalFilename(),
				fileContent.contentType(),
				fileContent.detectedContentType(),
				fileContent.sizeBytes(),
				storageKey,
				rawText,
				normalizedText,
				chunks
			);
		log.info(
			"resume_upload_complete filename={} storageKey={} extractMs={} normalizeMs={} chunkMs={} totalMs={} chunks={} rawChars={} normalizedChars={}",
			fileContent.originalFilename(),
			storageKey,
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
		ResumeUploadResponse cachedResume = currentResume.get();
		if (cachedResume != null) {
			return Optional.of(cachedResume);
		}
		if (resumePersistenceService == null) {
			return Optional.empty();
		}
		Optional<ResumeUploadResponse> latest = resumePersistenceService.findLatest();
		latest.ifPresent(currentResume::set);
		return latest;
	}

	public List<ResumeChunkResponse> chunksFor(String normalizedText) {
		return chunker.chunk(normalizedText).stream()
			.map(chunk -> new ResumeChunkResponse(
				chunk.index(),
				chunk.section(),
				chunk.content(),
				chunk.content().length()
			))
			.toList();
	}

	private long elapsedMillis(long startedAt) {
		return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
	}

	private Object uploadFingerprint(ResumeFileContent fileContent) {
		return new UploadFingerprint(
			fileContent.originalFilename(),
			fileContent.contentType(),
			fileContent.detectedContentType(),
			fileContent.sizeBytes(),
			sha256(fileContent.bytes())
		);
	}

	private String sha256(byte[] bytes) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			return HexFormat.of().formatHex(digest.digest(bytes));
		}
		catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 is unavailable", exception);
		}
	}

	private record UploadFingerprint(
		String originalFilename,
		String contentType,
		String detectedContentType,
		long sizeBytes,
		String contentHash
	) {
	}
}
