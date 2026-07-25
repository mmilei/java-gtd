package com.gtd.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.ollama.api.OllamaOptions;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LlmProviderServiceTest {

    private final ChatClient groqChatClient = mock(ChatClient.class);

    private LlmProviderService newService() {
        return new LlmProviderService(groqChatClient);
    }

    /** Pulls the active provider for one action out of the per-action describeAll() shape. */
    @SuppressWarnings("unchecked")
    private static String activeFor(LlmProviderService service, LlmAction action) {
        List<Map<String, Object>> actions =
            (List<Map<String, Object>>) service.describeAll().get("actions");
        return actions.stream()
            .filter(a -> action.name().equals(a.get("action")))
            .map(a -> (String) a.get("active"))
            .findFirst().orElseThrow();
    }

    @Test
    void defaultActiveProviderIsGroqForEveryAction() {
        LlmProviderService service = newService();
        for (LlmAction action : LlmAction.values()) {
            assertThat(activeFor(service, action)).isEqualTo("GROQ");
        }
    }

    @Test
    void selectSwitchesOnlyThatActionLeavingOthersUntouched() {
        LlmProviderService service = newService();
        // ollamaChatClient is package-private (@Autowired(required = false)) — inject a mock
        // directly to exercise the real "provider available" path without a Spring context.
        service.ollamaChatClient = mock(ChatClient.class);

        assertThat(service.select(LlmAction.TRIAGE, "OLLAMA")).isTrue();
        assertThat(activeFor(service, LlmAction.TRIAGE)).isEqualTo("OLLAMA");
        // per-action independence: switching Triage must not touch Enrichment/Resolver
        assertThat(activeFor(service, LlmAction.ENRICHMENT)).isEqualTo("GROQ");
        assertThat(activeFor(service, LlmAction.RESOLVER)).isEqualTo("GROQ");
    }

    @Test
    void selectUnknownProviderReturnsFalse() {
        LlmProviderService service = newService();
        assertThat(service.select(LlmAction.TRIAGE, "BOGUS")).isFalse();
        // active provider unchanged
        assertThat(activeFor(service, LlmAction.TRIAGE)).isEqualTo("GROQ");
    }

    @Test
    void selectOllamaReturnsFalseWhenNotConfigured() {
        // ollamaChatClient field stays null: no Spring context wiring it in a plain unit test,
        // which mirrors "Ollama not installed" (the @ConditionalOnProperty bean never created).
        LlmProviderService service = newService();
        assertThat(service.select(LlmAction.TRIAGE, "OLLAMA")).isFalse();
    }

    @Test
    void selectIsCaseInsensitive() {
        LlmProviderService service = newService();
        assertThat(service.select(LlmAction.TRIAGE, "groq")).isTrue();
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

    // --- Auto-switch Ollama → Groq (infra fallback) ---

    @Test
    void ollamaSelectedButDownFallsBackToGroqWithoutMutatingActive() {
        LlmProviderService service = spy(newService());
        ChatClient ollama = mock(ChatClient.class);
        service.ollamaChatClient = ollama;
        service.select(LlmAction.TRIAGE, "OLLAMA");
        doReturn(false).when(service).ollamaAvailable(); // Ollama down
        stubContent(groqChatClient, "groq-result");

        String out = service.complete(LlmAction.TRIAGE, "classify this");

        assertThat(out).isEqualTo("groq-result");
        verify(ollama, never()).prompt();                              // Ollama never dispatched
        assertThat(activeFor(service, LlmAction.TRIAGE)).isEqualTo("OLLAMA"); // preference preserved
    }

    @Test
    void autoSwitchIsPerActionIndependent() {
        // Triage on a downed Ollama falls back to Groq for its call; Enrichment (still on Groq)
        // is completely independent — proves the fallback routes per-action, not globally.
        LlmProviderService service = spy(newService());
        ChatClient ollama = mock(ChatClient.class);
        service.ollamaChatClient = ollama;
        service.select(LlmAction.TRIAGE, "OLLAMA");   // only Triage on Ollama
        doReturn(false).when(service).ollamaAvailable(); // Ollama down
        stubContent(groqChatClient, "groq-result");

        assertThat(service.complete(LlmAction.TRIAGE, "triage this")).isEqualTo("groq-result");
        assertThat(service.complete(LlmAction.ENRICHMENT, "enrich this")).isEqualTo("groq-result");

        verify(ollama, never()).prompt();
        assertThat(activeFor(service, LlmAction.TRIAGE)).isEqualTo("OLLAMA"); // Triage preference held
        assertThat(activeFor(service, LlmAction.ENRICHMENT)).isEqualTo("GROQ"); // Enrichment untouched
    }

    @Test
    void ollamaAndGroqBothDownPropagatesOriginalError() {
        LlmProviderService service = spy(newService());
        service.ollamaChatClient = mock(ChatClient.class);
        service.select(LlmAction.TRIAGE, "OLLAMA");
        doReturn(false).when(service).ollamaAvailable(); // Ollama down
        when(groqChatClient.prompt()).thenThrow(new RuntimeException("groq 503")); // Groq down too

        assertThatThrownBy(() -> service.complete(LlmAction.TRIAGE, "classify this"))
            .isInstanceOf(RuntimeException.class)
            .hasMessage("groq 503"); // clean propagation, not a confusing wrapped error
    }

    @Test
    void ollamaSelectedAndUpDispatchesToOllama() {
        LlmProviderService service = spy(newService());
        ChatClient ollama = mock(ChatClient.class);
        service.ollamaChatClient = ollama;
        service.select(LlmAction.TRIAGE, "OLLAMA");
        doReturn(true).when(service).ollamaAvailable(); // Ollama up
        stubContent(ollama, "ollama-result");

        String out = service.complete(LlmAction.TRIAGE, "classify this");

        assertThat(out).isEqualTo("ollama-result");
        verify(groqChatClient, never()).prompt(); // no fallback, Groq untouched
    }

    // --- Startup provider-availability check ---

    @Test
    void startupCheckLogsErrorWhenNoProviderAvailableWithoutThrowing() {
        // groqApiKey blank + ollamaChatClient null → both unavailable. Must log ERROR and
        // NOT throw (the Spring context keeps starting; the app boots regardless).
        LlmProviderService service = newService();

        ch.qos.logback.classic.Logger logger = (ch.qos.logback.classic.Logger)
            org.slf4j.LoggerFactory.getLogger(LlmProviderService.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);

        assertThatCode(service::checkProvidersOnStartup).doesNotThrowAnyException();

        logger.detachAppender(appender);
        assertThat(appender.list).anyMatch(e ->
            e.getLevel() == Level.ERROR && e.getFormattedMessage().contains("No LLM provider available"));
    }

    private void stubContent(ChatClient client, String content) {
        ChatClient.ChatClientRequestSpec spec = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.CallResponseSpec resp = mock(ChatClient.CallResponseSpec.class);
        when(client.prompt()).thenReturn(spec);
        when(spec.user(anyString())).thenReturn(spec);
        when(spec.options(any())).thenReturn(spec);
        when(spec.call()).thenReturn(resp);
        when(resp.content()).thenReturn(content);
    }
}
