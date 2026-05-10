package dev.jiaming.ai_interview.common;

import java.time.Instant;
import java.util.Map;

import org.springframework.boot.info.BuildProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.Environment;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class ApiStatusController {

	private final Environment environment;

	private final BuildProperties buildProperties;

	public ApiStatusController(Environment environment, ObjectProvider<BuildProperties> buildProperties) {
		this.environment = environment;
		this.buildProperties = buildProperties.getIfAvailable();
	}

	@GetMapping("/status")
	public Map<String, Object> status() {
		return Map.of(
			"service", environment.getProperty("spring.application.name", "ai_interview"),
			"build", buildProperties == null ? "dev" : buildProperties.getVersion(),
			"timestamp", Instant.now().toString()
		);
	}
}
