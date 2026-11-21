package com.hackathon.agent.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Tekton monitoring settings ({@code monitoring.*}).
 *
 * @param enabled            whether the scheduled collector runs
 * @param pollingIntervalMs  delay between the end of one collection cycle and the start of the next
 * @param initialDelayMs     delay before the first collection cycle after startup
 * @param namespaces         namespaces to collect PipelineRuns and TaskRuns from
 * @param labelSelector      optional label selector applied to every list call (empty = no filter)
 * @param pageSize           Kubernetes list page size (limit/continue pagination)
 * @param tekton             Tekton API coordinates
 */
@Validated
@ConfigurationProperties(prefix = "monitoring")
public record MonitoringProperties(
        boolean enabled,
        @Min(1000) long pollingIntervalMs,
        @Min(0) long initialDelayMs,
        @NotEmpty List<@NotBlank String> namespaces,
        String labelSelector,
        @Min(1) int pageSize,
        Tekton tekton) {

    public MonitoringProperties {
        namespaces = namespaces == null ? List.of() : namespaces.stream().map(String::trim).filter(s -> !s.isEmpty()).toList();
        tekton = tekton == null ? new Tekton(null, null) : tekton;
    }

    /**
     * @param group      Tekton API group, normally {@code tekton.dev}
     * @param apiVersion Tekton API version, {@code v1} for Tekton Pipelines >= 0.44
     */
    public record Tekton(String group, String apiVersion) {
        public Tekton {
            group = (group == null || group.isBlank()) ? "tekton.dev" : group;
            apiVersion = (apiVersion == null || apiVersion.isBlank()) ? "v1" : apiVersion;
        }
    }
}
