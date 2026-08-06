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
import java.util.concurrent.ConcurrentHashMap;

/**
 * Owns which LLM provider is active per {@link LlmAction} (in-memory, resets to GROQ on restart)
 * and dispatches completions to it. Each action (Triage / Enrichment / Resolver) routes
 * independently — switching Triage to Ollama leaves Enrichment/Resolver on whatever they were.
 * The user's manual choice stays authoritative while the provider responds: a GROQ runtime
 * failure still propagates to the caller (no auto-switch in that direction — the 2026-07-01
 * "user picks manually" decision holds). The one exception is infrastructure death: if OLLAMA is
 * selected for an action but its healthcheck fails, that single call is dispatched to Groq without
 * mutating the stored preference, so it's restored automatically the moment Ollama comes back.
 * ANTHROPIC follows the GROQ propagate-the-error path, not the OLLAMA auto-switch path — it's a
 * paid cloud API like Groq, not local infra that can be transiently down.
 */
@Service
public class LlmProviderService {

    private static final Logger log = LoggerFactory.getLogger(LlmProviderService.class);

    public enum LlmProvider { GROQ, OLLAMA, ANTHROPIC }

    /**
     * Keep the model resident in VRAM for 30s after each call so back-to-back pipeline
     * calls (Triage → Enrich/Resolve) don't each pay a cold-load. Reused by the on-startup
     * warmup below. Immutable/read-only, safe to share across calls.
     */
    static final OllamaOptions OLLAMA_OPTIONS = OllamaOptions.builder().keepAlive("30s").build();

    private final ChatClient groqChatClient;
    // Fully populated at construction (one entry per LlmAction), never structurally modified after —
    // only the values are reassigned via put(). ConcurrentHashMap makes those per-action get/put
    // safe under concurrent classify requests; no AtomicReference needed on top.
    private final Map<LlmAction, LlmProvider> activeByAction = new ConcurrentHashMap<>();

    @Autowired(required = false)
    @Qualifier("ollamaChatClient")
    ChatClient ollamaChatClient;

    @Autowired(required = false)
    @Qualifier("anthropicChatClient")
    ChatClient anthropicChatClient;

    @Value("${spring.ai.ollama.base-url:http://localhost:11434}")
    private String ollamaBaseUrl;

    @Value("${spring.ai.openai.api-key:}")
    private String groqApiKey;

    @Value("${spring.ai.anthropic.api-key:}")
    private String anthropicApiKey;

    public LlmProviderService(@Qualifier("groqChatClient") ChatClient groqChatClient) {
        this.groqChatClient = groqChatClient;
        for (LlmAction action : LlmAction.values()) {
            activeByAction.put(action, LlmProvider.GROQ);
        }
    }

    public String complete(LlmAction action, String prompt) {
        LlmProvider provider = activeByAction.get(action);
        // Infra fallback: Ollama selected for this action but down → send this one call to Groq
        // without mutating the stored preference, so it's restored as soon as Ollama is back.
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
                        throw new IllegalStateException("Ollama not configured");
                    }
                    yield ollamaChatClient.prompt().user(prompt).options(OLLAMA_OPTIONS).call().content();
                }
                case ANTHROPIC -> {
                    if (anthropicChatClient == null) {
                        throw new IllegalStateException("Anthropic not configured");
                    }
                    yield anthropicChatClient.prompt().user(prompt).call().content();
                }
            };
        } catch (Exception e) {
            log.error("LLM completion failed (provider={}): {}", provider, e.getMessage());
            throw e;
        }
    }

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
        boolean anthropic = anthropicAvailable();
        if (!groq && !ollama && !anthropic) {
            log.error("No LLM provider available at startup (Groq API key missing, Ollama healthcheck down, "
                + "Anthropic not configured). App started anyway, but every classification will fail until "
                + "a provider is configured.");
        }
    }

    /**
     * Fire a trivial Ollama call on startup so the model is resident in VRAM before the
     * user's first real capture (cold-load measured at ~42s). Best-effort: runs off the
     * boot thread (never blocks startup) and swallows failures (Ollama not running, etc.).
     * Only fires when the ollamaChatClient bean exists (ollama.enabled=true) AND the 800ms
     * healthcheck passes — a down/hung Ollama is skipped here instead of firing a warmup that
     * blocks forever. The thread is a daemon so a warmup that still hangs (Ollama answering
     * /api/tags but stuck mid-inference) can never block a clean JVM shutdown.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void warmupOllama() {
        if (ollamaChatClient == null || !ollamaAvailable()) return;
        Thread thread = new Thread(this::runWarmup, "ollama-warmup");
        thread.setDaemon(true);
        thread.start();
    }

    void runWarmup() {
        try {
            ollamaChatClient.prompt().user("ping").options(OLLAMA_OPTIONS).call().content();
            log.info("Ollama warmup complete — model resident in VRAM");
        } catch (Exception e) {
            log.warn("Ollama warmup failed (best-effort, ignoring): {}", e.getMessage());
        }
    }

    /**
     * New per-action shape (breaking change from the old {active, providers[]}): one entry per
     * LlmAction, each carrying its own active provider plus the shared provider-status list.
     * The UP/DOWN status is a pure healthcheck, identical across actions — so the two healthchecks
     * run once here, not once per action.
     */
    public Map<String, Object> describeAll() {
        boolean groqUp = groqAvailable();
        boolean ollamaUp = ollamaAvailable();
        boolean anthropicUp = anthropicAvailable();

        List<Map<String, Object>> actions = new ArrayList<>();
        for (LlmAction action : LlmAction.values()) {
            List<Map<String, Object>> providers = new ArrayList<>();
            providers.add(describe(LlmProvider.GROQ, "Groq", groqUp));
            providers.add(describe(LlmProvider.OLLAMA, "Ollama", ollamaUp));
            providers.add(describe(LlmProvider.ANTHROPIC, "Anthropic", anthropicUp));

            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("action", action.name());
            entry.put("active", activeByAction.get(action).name());
            entry.put("providers", providers);
            actions.add(entry);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("actions", actions);
        return result;
    }

    public boolean select(LlmAction action, String id) {
        if (action == null || id == null) return false;
        LlmProvider provider;
        try {
            provider = LlmProvider.valueOf(id.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return false;
        }
        if (provider == LlmProvider.OLLAMA && ollamaChatClient == null) {
            return false;
        }
        if (provider == LlmProvider.ANTHROPIC && anthropicChatClient == null) {
            return false;
        }
        activeByAction.put(action, provider);
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

    // No live healthcheck (unlike Ollama): Anthropic is a paid cloud API, so "available" is
    // just "the bean exists (anthropic.enabled=true) and a key is configured" — pinging it on
    // every /api/providers call would burn API credits for no benefit.
    private boolean anthropicAvailable() {
        return anthropicChatClient != null && anthropicApiKey != null && !anthropicApiKey.isBlank();
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
