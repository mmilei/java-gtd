package ar.maxi.gtd.service;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
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
 * dispatches completions to it. No automatic fallback chain: if the selected
 * provider fails, the caller sees the error and picks another manually.
 */
@Service
public class LlmProviderService {

    public enum LlmProvider { GROQ, OLLAMA }

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
        return switch (active.get()) {
            case GROQ -> groqChatClient.prompt().user(prompt).call().content();
            case OLLAMA -> {
                if (ollamaChatClient == null) {
                    throw new IllegalStateException("Ollama no configurado");
                }
                yield ollamaChatClient.prompt().user(prompt).call().content();
            }
        };
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

    private boolean ollamaAvailable() {
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
