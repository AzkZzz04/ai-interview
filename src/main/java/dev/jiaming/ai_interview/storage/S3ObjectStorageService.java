package dev.jiaming.ai_interview.storage;

import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

@Service
public class S3ObjectStorageService implements ObjectStorageService {

	private final S3Client s3Client;

	private final StorageProperties properties;

	private final AtomicBoolean bucketReady = new AtomicBoolean(false);

	public S3ObjectStorageService(S3Client s3Client, StorageProperties properties) {
		this.s3Client = s3Client;
		this.properties = properties;
	}

	@Override
	public StoredObject put(String key, byte[] content, String contentType, Map<String, String> metadata) {
		ensureBucket();
		try {
			PutObjectRequest request = PutObjectRequest.builder()
				.bucket(bucket())
				.key(key)
				.contentType(contentType == null || contentType.isBlank() ? "application/octet-stream" : contentType)
				.metadata(metadata == null ? Map.of() : metadata)
				.build();
			s3Client.putObject(request, RequestBody.fromBytes(content));
			return new StoredObject(bucket(), key, content.length);
		}
		catch (S3Exception exception) {
			throw storageUnavailable(exception);
		}
	}

	private void ensureBucket() {
		if (bucketReady.get()) {
			return;
		}
		try {
			s3Client.headBucket(HeadBucketRequest.builder().bucket(bucket()).build());
			bucketReady.set(true);
		}
		catch (NoSuchBucketException exception) {
			createBucket();
		}
		catch (S3Exception exception) {
			if (exception.statusCode() == 404) {
				createBucket();
				return;
			}
			throw storageUnavailable(exception);
		}
	}

	private void createBucket() {
		try {
			s3Client.createBucket(CreateBucketRequest.builder().bucket(bucket()).build());
			bucketReady.set(true);
		}
		catch (S3Exception exception) {
			throw storageUnavailable(exception);
		}
	}

	private String bucket() {
		String bucket = properties.bucket();
		if (bucket == null || bucket.isBlank()) {
			throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "S3 bucket is not configured");
		}
		return bucket;
	}

	private ResponseStatusException storageUnavailable(Exception exception) {
		return new ResponseStatusException(
			HttpStatus.BAD_GATEWAY,
			"Object storage is not reachable. Start LocalStack S3 or check S3_ENDPOINT/S3 credentials.",
			exception
		);
	}
}
