package com.hackathon.agent.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.hackathon.agent.controller.DashboardController;
import com.hackathon.agent.service.DashboardService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.converter.json.AbstractJackson2HttpMessageConverter;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerAdapter;

@WebMvcTest(DashboardController.class)
@Import(ClockConfig.class)
@EnableConfigurationProperties(CorsProperties.class)
class MessageConverterTest {

    @Autowired
    private RequestMappingHandlerAdapter handlerAdapter;

    @MockitoBean
    private DashboardService dashboardService;

    /** Jackson 2 with its Kotlin module can block all virtual threads, so that no request is answered any more. */
    @Test
    @SuppressWarnings("removal")
    void jackson2IsNotUsedForResponses() {
        assertThat(handlerAdapter.getMessageConverters())
                .hasAtLeastOneElementOfType(JacksonJsonHttpMessageConverter.class)
                .noneMatch(converter -> converter instanceof AbstractJackson2HttpMessageConverter);
    }
}
