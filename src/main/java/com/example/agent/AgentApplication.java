package com.example.agent;

import com.example.agent.config.LlmProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * 应用启动类。
 *
 * <p>{@code @EnableConfigurationProperties} 让 {@link LlmProperties} 生效
 * （也可以用 {@code @ConfigurationPropertiesScan} 自动扫描整个包）。
 */
@SpringBootApplication
@EnableConfigurationProperties(LlmProperties.class)
public class AgentApplication {

	public static void main(String[] args) {
		SpringApplication.run(AgentApplication.class, args);
	}

}
