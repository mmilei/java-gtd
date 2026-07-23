package com.gtd.service;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.ollama.api.OllamaOptions;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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

    @Test
    void warmupIsNoOpWhenOllamaNotConfigured() {
        // null client (ollama.enabled=false) → listener must not spawn a thread or throw.
        LlmProviderService service = newService();
        assertThatCode(service::warmupOllama).doesNotThrowAnyException();
    }

    @Test
    void warmupCallsOllamaWith30sKeepAlive() {
        LlmProviderService service = newService();
        ChatClient ollama = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec spec = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.CallResponseSpec resp = mock(ChatClient.CallResponseSpec.class);
        when(ollama.prompt()).thenReturn(spec);
        when(spec.user(anyString())).thenReturn(spec);
        when(spec.options(any())).thenReturn(spec);
        when(spec.call()).thenReturn(resp);
        when(resp.content()).thenReturn("ok");
        service.ollamaChatClient = ollama;

        service.runWarmup();

        ArgumentCaptor<OllamaOptions> opts = ArgumentCaptor.forClass(OllamaOptions.class);
        verify(spec).options(opts.capture());
        assertThat(opts.getValue().getKeepAlive()).isEqualTo("30s");
    }

    @Test
    void warmupSwallowsFailures() {
        // Ollama down → warmup logs and returns, never propagates (best-effort).
        LlmProviderService service = newService();
        ChatClient ollama = mock(ChatClient.class);
        when(ollama.prompt()).thenThrow(new RuntimeException("connection refused"));
        service.ollamaChatClient = ollama;

        assertThatCode(service::runWarmup).doesNotThrowAnyException();
    }
}
