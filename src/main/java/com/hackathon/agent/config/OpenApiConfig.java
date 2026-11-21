package com.hackathon.agent.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class OpenApiConfig {

    @Bean
    public OpenAPI agentOpenApi() {
        return new OpenAPI().info(new Info()
                .title("CI/CD Reliability AI Agent API")
                .version("v1")
                .description("Read-only API exposing Tekton PipelineRun / TaskRun execution history and "
                        + "reliability metrics collected from Kubernetes. All timestamps are ISO-8601 in UTC. "
                        + "List endpoints are paginated; page numbers are zero-based."));
    }
}
