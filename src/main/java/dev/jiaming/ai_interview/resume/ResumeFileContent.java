package dev.jiaming.ai_interview.resume;

record ResumeFileContent(
	String originalFilename,
	String contentType,
	long sizeBytes,
	byte[] bytes,
	String detectedContentType,
	String extension
) {
}
