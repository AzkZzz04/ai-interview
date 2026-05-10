package dev.jiaming.ai_interview.resume;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/resumes")
@CrossOrigin(origins = { "http://localhost:3000", "http://127.0.0.1:3000" })
public class ResumeController {

	private final ResumeUploadService resumeUploadService;

	public ResumeController(ResumeUploadService resumeUploadService) {
		this.resumeUploadService = resumeUploadService;
	}

	@PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	@ResponseStatus(HttpStatus.CREATED)
	public ResumeUploadResponse upload(@RequestPart("file") MultipartFile file) {
		return resumeUploadService.process(file);
	}

	@GetMapping("/current")
	public ResumeUploadResponse current() {
		return resumeUploadService.current()
			.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No resume has been uploaded"));
	}
}
