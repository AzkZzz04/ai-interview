package dev.jiaming.ai_interview.resume;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

class ResumeControllerTests {

	private final ResumeUploadService service = new ResumeUploadService(
		new ResumeTextNormalizer(),
		new SectionAwareTextChunker()
	);

	private final MockMvc mockMvc = standaloneSetup(new ResumeController(service)).build();

	@Test
	void uploadsResumeAndReturnsExtractedText() throws Exception {
		MockMultipartFile file = new MockMultipartFile(
			"file",
			"resume.txt",
			"text/plain",
			"SKILLS\nJava Spring Boot".getBytes(StandardCharsets.UTF_8)
		);

		mockMvc.perform(multipart("/api/resumes").file(file))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.originalFilename").value("resume.txt"))
			.andExpect(jsonPath("$.normalizedText").value(containsString("Java Spring Boot")))
			.andExpect(jsonPath("$.chunks[0].section").value("Skills"));

		mockMvc.perform(get("/api/resumes/current"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.originalFilename").value("resume.txt"));
	}

	@Test
	void returnsNotFoundWhenNoResumeHasBeenUploaded() throws Exception {
		MockMvc emptyMockMvc = standaloneSetup(new ResumeController(new ResumeUploadService(
			new ResumeTextNormalizer(),
			new SectionAwareTextChunker()
		))).build();

		emptyMockMvc.perform(get("/api/resumes/current"))
			.andExpect(status().isNotFound());
	}
}
