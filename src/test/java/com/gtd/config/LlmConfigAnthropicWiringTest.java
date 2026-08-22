package com.gtd.config;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Companion to {@link LlmConfigWiringTest}: proves the Anthropic provider is registered by
 * config alone (anthropic.enabled=true), no code change required — the acceptance criterion
 * for G4 (add Anthropic as a third swappable provider). Ollama stays off here so this test
 * isolates the Anthropic wiring path instead of re-covering Ollama's own wiring test.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@TestPropertySource(properties = {
        "spring.ai.openai.api-key=dummy-test-key",
        "spring.ai.anthropic.api-key=dummy-test-key",
        "gtd.vault.path=target/test-vault-wiring-anthropic",
        "ollama.enabled=false",
        "anthropic.enabled=true"
})
class LlmConfigAnthropicWiringTest {

    @Autowired
    ApplicationContext context;

    @Test
    void anthropicChatClientBeanRegistersWhenEnabledByConfig() {
        assertThat(context.getBeansOfType(ChatClient.class)).hasSize(2);
        assertThat(context.containsBean("groqChatClient")).isTrue();
        assertThat(context.containsBean("anthropicChatClient")).isTrue();
        assertThat(context.containsBean("ollamaChatClient")).isFalse();
    }
}
