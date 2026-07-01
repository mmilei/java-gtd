package ar.maxi.gtd.config;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Loads the real Spring context (unlike the @WebMvcTest slices and plain unit tests
 * elsewhere) to catch wiring regressions that only surface at boot: the
 * ChatClientAutoConfiguration exclusion in application.properties, the 2 qualified
 * ChatClient beans in LlmConfig, and the OpenAI/Ollama autoconfiguration all have to
 * agree, and nothing else in this test suite exercises that combination.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@TestPropertySource(properties = {
        "spring.ai.openai.api-key=dummy-test-key",
        "gtd.vault.path=target/test-vault-wiring",
        "ollama.enabled=false"
})
class LlmConfigWiringTest {

    @Autowired
    ApplicationContext context;

    @Test
    void contextLoadsWithExactlyOneChatClientBeanWhenOllamaDisabled() {
        assertThat(context.getBeansOfType(ChatClient.class)).hasSize(1);
        assertThat(context.containsBean("groqChatClient")).isTrue();
        assertThat(context.containsBean("ollamaChatClient")).isFalse();
    }
}
