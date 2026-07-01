package ar.maxi.gtd.service;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class LlmProviderServiceTest {

    private final ChatClient groqChatClient = mock(ChatClient.class);

    private LlmProviderService newService() {
        return new LlmProviderService(groqChatClient);
    }

    @Test
    void defaultActiveProviderIsGroq() {
        LlmProviderService service = newService();
        Map<String, Object> described = service.describeAll();
        assertThat(described.get("active")).isEqualTo("GROQ");
    }

    @Test
    void selectKnownProviderSwitchesActive() {
        LlmProviderService service = newService();
        // ollamaChatClient is package-private (@Autowired(required = false)) — inject a mock
        // directly to exercise the real "provider available" path without a Spring context.
        service.ollamaChatClient = mock(ChatClient.class);

        assertThat(service.select("OLLAMA")).isTrue();
        assertThat(service.describeAll().get("active")).isEqualTo("OLLAMA");
    }

    @Test
    void selectUnknownProviderReturnsFalse() {
        LlmProviderService service = newService();
        assertThat(service.select("BOGUS")).isFalse();
        // active provider unchanged
        assertThat(service.describeAll().get("active")).isEqualTo("GROQ");
    }

    @Test
    void selectOllamaReturnsFalseWhenNotConfigured() {
        // ollamaChatClient field stays null: no Spring context wiring it in a plain unit test,
        // which mirrors "Ollama not installed" (the @ConditionalOnProperty bean never created).
        LlmProviderService service = newService();
        assertThat(service.select("OLLAMA")).isFalse();
    }

    @Test
    void selectIsCaseInsensitive() {
        LlmProviderService service = newService();
        assertThat(service.select("groq")).isTrue();
    }
}
