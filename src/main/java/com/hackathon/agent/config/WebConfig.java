package com.hackathon.agent.config;

import com.hackathon.agent.enums.ExecutionStatus;
import com.hackathon.agent.enums.TrendGranularity;
import java.util.Locale;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.format.FormatterRegistry;
import org.springframework.http.converter.HttpMessageConverters.ServerBuilder;
import org.springframework.http.converter.json.AbstractJackson2HttpMessageConverter;
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
        /*if (corsProperties.allowedOrigins().isEmpty()) {
            return;
        }
        registry.addMapping("/**")
                .allowedOrigins(corsProperties.allowedOrigins().toArray(String[]::new))
                .allowedMethods("GET", "OPTIONS")
                .allowedHeaders("*")
                .maxAge(3600);*/

        registry.addMapping("/**")
                .allowedOriginPatterns("*")
                .allowedMethods("*")
                .allowedHeaders("*")
                .allowCredentials(false)
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

    /**
     * Responses are written with Jackson 3. Spring also adds a Jackson 2 converter for YAML, because other libraries
     * need Jackson 2. It is asked on every request and uses Kotlin reflection, whose locks can block all virtual
     * threads, so that no request is answered any more.
     */
    @Override
    @SuppressWarnings("removal")
    public void configureMessageConverters(ServerBuilder builder) {
        builder.configureMessageConvertersList(converters ->
                converters.removeIf(AbstractJackson2HttpMessageConverter.class::isInstance));
    }
}
