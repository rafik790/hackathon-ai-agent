package com.hackathon.agent.ai.llm;

import static org.assertj.core.api.Assertions.assertThat;

import com.hackathon.agent.ai.llm.anthropic.AnthropicLlmProvider;
import com.hackathon.agent.ai.llm.anthropic.AnthropicProperties;
import com.hackathon.agent.ai.llm.mock.MockLlmProvider;
import com.hackathon.agent.config.AiProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class LlmProviderConfigTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(LlmProviderConfig.class, Properties.class);

    @EnableConfigurationProperties({AiProperties.class, AnthropicProperties.class})
    static class Properties {
    }

    @Test
    void noProviderExistsWhileDisabled() {
        runner.withPropertyValues("ai.provider=anthropic", "ai.anthropic.api-key=placeholder", "ai.anthropic.model=m")
                .run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(LlmProvider.class));
    }

    @Test
    void selectsTheConfiguredProvider() {
        runner.withPropertyValues("ai.enabled=true", "ai.provider=mock")
                .run(context -> assertThat(context.getBean(LlmProvider.class)).isInstanceOf(MockLlmProvider.class));
        runner.withPropertyValues("ai.enabled=true", "ai.provider=Anthropic", "ai.anthropic.api-key=placeholder",
                        "ai.anthropic.model=some-model")
                .run(context -> {
                    assertThat(context.getBean(LlmProvider.class)).isInstanceOf(AnthropicLlmProvider.class);
                    assertThat(context.getBean(LlmProvider.class).model()).isEqualTo("some-model");
                });
    }

    @Test
    void unsupportedProviderFailsFast() {
        runner.withPropertyValues("ai.enabled=true", "ai.provider=other").run(context ->
                assertThat(context).hasFailed().getFailure().rootCause()
                        .hasMessageContaining("Unsupported AI provider 'other'").hasMessageContaining("anthropic, mock"));
    }

    @Test
    void anthropicRequiresKeyAndModel() {
        runner.withPropertyValues("ai.enabled=true", "ai.provider=anthropic", "ai.anthropic.model=m").run(context ->
                assertThat(context).hasFailed().getFailure().rootCause().hasMessageContaining("ANTHROPIC_API_KEY"));
        runner.withPropertyValues("ai.enabled=true", "ai.provider=anthropic", "ai.anthropic.api-key=placeholder")
                .run(context -> assertThat(context).hasFailed().getFailure().rootCause()
                        .hasMessageContaining("ANTHROPIC_MODEL").message().doesNotContain("placeholder"));
    }
}
