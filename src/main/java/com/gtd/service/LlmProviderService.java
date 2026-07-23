package com.gtd.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.ollama.api.OllamaOptions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Owns which LLM provider is active (in-memory, resets to GROQ on restart) and
 * dispatches completions to it. The user's manual choice stays authoritative while
 * the provider responds: a GROQ runtime failure still propagates to the caller (no
 * auto-switch in that direction — the 2026-07-01 "user picks manually" decision holds).
 * The one exception is infrastructure death: if OLLAMA is selected but its healthcheck
 * fails, that single call is dispatched to Groq without mutating {@code active}, so the
 * preference is restored automatically the moment Ollama comes back.
 */
@Service
public class LlmProviderService {

    private static final Logger log = LoggerFactory.getLogger(LlmProviderService.class);

    public enum LlmProvider { GROQ, OLLAMA }

    /**
     * Keep the model resident in VRAM for 30s after each call so back-to-back pipeline
     * calls (Triage → Enrich/Resolve) don't each pay a cold-load. Reused by the on-startup
     * warmup below. Immutable/read-only, safe to share across calls.
     */
    static final OllamaOptions OLLAMA_OPTIONS = OllamaOptions.builder().keepAlive("30s").build();

    private final ChatClient groqChatClient;
    private final AtomicReference<LlmProvider> active = new AtomicReference<>(LlmProvider.GROQ);

    @Autowired(required = false)
    @Qualifier("ollamaChatClient")
    ChatClient ollamaChatClient;

    @Value("${spring.ai.ollama.base-url:http://localhost:11434}")
    private String ollamaBaseUrl;

    @Value("${spring.ai.openai.api-key:}")
    private String groqApiKey;

    public LlmProviderService(@Qualifier("groqChatClient") ChatClient groqChatClient) {
        this.groqChatClient = groqChatClient;
    }

    public String complete(String prompt) {
        LlmProvider provider = active.get();
        // Infra fallback: Ollama selected but down → send this one call to Groq without
        // mutating `active`, so the preference is restored as soon as Ollama is back.
        // If Groq is also down, dispatching to it below fails and the error propagates
        // as always — there's nowhere else to fall.
        // ponytail: per-call 800ms healthcheck, no cache. It hits local /api/tags in ~ms
        // when up (the 800ms is only the down/hung ceiling), negligible vs multi-second
        // inference. Add a short TTL cache only if profiling shows it matters.
        if (provider == LlmProvider.OLLAMA && !ollamaAvailable()) {
            log.warn("Ollama down, falling back to Groq for this call (preference unchanged)");
            provider = LlmProvider.GROQ;
        }
        try {
            return switch (provider) {
                case GROQ -> groqChatClient.prompt().user(prompt).call().content();
                case OLLAMA -> {
                    if (ollamaChatClient == null) {
                        throw new IllegalStateException("Ollama no configurado");
                    }
                    yield ollamaChatClient.prompt().user(prompt).options(OLLAMA_OPTIONS).call().content();
                }
            };
        } catch (Exception e) {
            log.error("LLM completion failed (provider={}): {}", provider, e.getMessage());
            throw e;
        }
    }

    /**
     * Fire a trivial Ollama call on startup so the model is resident in VRAM before the
     * user's first real capture (cold-load measured at ~42s). Best-effort: runs off the
     * boot thread (never blocks startup) and swallows failures (Ollama not running, etc.).
     * Only fires when the ollamaChatClient bean exists (ollama.enabled=true).
     */
    /**
     * Surface a dead deployment at boot instead of at the first real request. If neither
     * Groq (API key) nor Ollama (healthcheck) is available, every classification will throw
     * IllegalStateException later — logging it here makes the misconfiguration visible in the
     * startup logs. Non-blocking: the app still starts (a provider may be configured after boot).
     */
    @EventListener(ApplicationReadyEvent.class)
    public void checkProvidersOnStartup() {
        boolean groq = groqAvailable();
        boolean ollama = ollamaAvailable();
        if (!groq && !ollama) {
            log.error("No LLM provider available at startup (Groq API key missing, Ollama healthcheck down). "
                + "App started anyway, but every classification will fail until a provider is configured.");
        }
    }

    @EventListener(ApplicationReadyEvent.class)
    public void warmupOllama() {
        if (ollamaChatClient == null) return;
        new Thread(this::runWarmup, "ollama-warmup").start();
    }

    void runWarmup() {
        try {
            ollamaChatClient.prompt().user("ping").options(OLLAMA_OPTIONS).call().content();
            log.info("Ollama warmup complete — model resident in VRAM");
        } catch (Exception e) {
            log.warn("Ollama warmup failed (best-effort, ignoring): {}", e.getMessage());
        }
    }

    public Map<String, Object> describeAll() {
        List<Map<String, Object>> providers = new ArrayList<>();
        providers.add(describe(LlmProvider.GROQ, "Groq", groqAvailable()));
        providers.add(describe(LlmProvider.OLLAMA, "Ollama", ollamaAvailable()));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("active", active.get().name());
        result.put("providers", providers);
        return result;
    }

    public boolean select(String id) {
        if (id == null) return false;
        LlmProvider provider;
        try {
            provider = LlmProvider.valueOf(id.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return false;
        }
        if (provider == LlmProvider.OLLAMA && ollamaChatClient == null) {
            return false;
        }
        active.set(provider);
        return true;
    }

    private Map<String, Object> describe(LlmProvider id, String label, boolean up) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id.name());
        m.put("label", label);
        m.put("status", up ? "UP" : "DOWN");
        return m;
    }

    private boolean groqAvailable() {
        return groqApiKey != null && !groqApiKey.isBlank();
    }

    // package-private so unit tests can stub the healthcheck without a live Ollama.
    boolean ollamaAvailable() {
        if (ollamaChatClient == null) return false;
        try {
            HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(800))
                .build();
            HttpRequest req = HttpRequest.newBuilder(URI.create(ollamaBaseUrl + "/api/tags"))
                .timeout(Duration.ofMillis(800))
                .GET()
                .build();
            HttpResponse<Void> res = client.send(req, HttpResponse.BodyHandlers.discarding());
            return res.statusCode() == 200;
        } catch (Exception e) {
            return false;
        }
    }
}
