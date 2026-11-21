package com.hackathon.agent.config;

import com.hackathon.agent.enums.ExecutionStatus;
import com.hackathon.agent.enums.TrendGranularity;
import java.util.Locale;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.format.FormatterRegistry;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration(proxyBeanMethods = false)
public class WebConfig implements WebMvcConfigurer {

    private final CorsProperties corsProperties;

    public WebConfig(CorsProperties corsProperties) {
        this.corsProperties = corsProperties;
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        if (corsProperties.allowedOrigins().isEmpty()) {
            return;
        }
        registry.addMapping("/api/**")
                .allowedOrigins(corsProperties.allowedOrigins().toArray(String[]::new))
                .allowedMethods("GET", "OPTIONS")
                .allowedHeaders("*")
                .maxAge(3600);
    }

    /** Accept enum query parameters case-insensitively, e.g. {@code status=failed}. */
    @Override
    public void addFormatters(FormatterRegistry registry) {
        registry.addConverter(String.class, ExecutionStatus.class, (Converter<String, ExecutionStatus>)
                source -> ExecutionStatus.valueOf(source.trim().toUpperCase(Locale.ROOT)));
        registry.addConverter(String.class, TrendGranularity.class, (Converter<String, TrendGranularity>)
                source -> TrendGranularity.valueOf(source.trim().toUpperCase(Locale.ROOT)));
    }
}
