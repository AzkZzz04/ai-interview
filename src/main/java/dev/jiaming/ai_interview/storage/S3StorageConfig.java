package dev.jiaming.ai_interview.storage;

import java.net.URI;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;

@Configuration
public class S3StorageConfig {

	@Bean
	S3Client s3Client(StorageProperties properties) {
		var builder = S3Client.builder()
			.region(Region.of(nonBlank(properties.region(), "us-east-1")))
			.credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(
				nonBlank(properties.accessKey(), "test"),
				nonBlank(properties.secretKey(), "test")
			)))
			.serviceConfiguration(S3Configuration.builder()
				.pathStyleAccessEnabled(true)
				.build());

		if (!blank(properties.endpoint())) {
			builder.endpointOverride(URI.create(properties.endpoint()));
		}

		return builder.build();
	}

	private String nonBlank(String value, String fallback) {
		return blank(value) ? fallback : value;
	}

	private boolean blank(String value) {
		return value == null || value.isBlank();
	}
}
