package com.hackathon.agent.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Scheduling is only enabled when the collector is enabled, so tests and API-only instances
 * never poll Kubernetes.
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnBooleanProperty(name = "monitoring.enabled", matchIfMissing = true)
public class SchedulingConfig {
}
