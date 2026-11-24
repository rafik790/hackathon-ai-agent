package com.hackathon.agent.config;

import com.hackathon.agent.ai.model.AnalysisStatus;
import com.hackathon.agent.ai.model.FailureCategory;
import com.hackathon.agent.ai.model.Severity;
import java.util.Locale;
import org.springframework.context.annotation.Configuration;
import org.springframework.format.FormatterRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Accepts the enum filters of the analysis endpoints case-insensitively.
 */
@Configuration(proxyBeanMethods = false)
public class AiWebConfig implements WebMvcConfigurer {

    @Override
    public void addFormatters(FormatterRegistry registry) {
        addEnum(registry, AnalysisStatus.class);
        addEnum(registry, FailureCategory.class);
        addEnum(registry, Severity.class);
    }

    private static <E extends Enum<E>> void addEnum(FormatterRegistry registry, Class<E> type) {
        registry.addConverter(String.class, type, source -> Enum.valueOf(type, source.trim().toUpperCase(Locale.ROOT)));
    }
}
