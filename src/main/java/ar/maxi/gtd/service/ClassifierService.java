package ar.maxi.gtd.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class ClassifierService {

    private static final Logger log = LoggerFactory.getLogger(ClassifierService.class);

    private final ChatClient chatClient;
    private final ObjectMapper objectMapper;
    private final String promptTemplate;
    private final String fallbackTemplate;
    private final String userContext;

    private static final Set<String> NON_FILING_BUCKETS = Set.of("now", "discard");

    public ClassifierService(ChatClient.Builder builder, ObjectMapper objectMapper, VaultService vault) {
        this.chatClient = builder.build();
        this.objectMapper = objectMapper;
        try {
            this.promptTemplate = new ClassPathResource("prompts/classifier.st")
                .getContentAsString(StandardCharsets.UTF_8);
            this.fallbackTemplate = new ClassPathResource("prompts/classifier-fallback.st")
                .getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        String ctx = vault.readContextFile("_context/user-profile.md");
        this.userContext = ctx.isBlank() ? "(sin perfil de usuario cargado)" : ctx;
        log.info("user-profile loaded: {} chars", this.userContext.length());
    }

    /**
     * Clasifica el mensaje con retry automático:
     * Nivel 1 → prompt liviano. Si falla el parse o todos los ops son now/discard → Nivel 2.
     */
    public ClassifyResult classifyAll(String message, List<Map<String, Object>> openTasks) {
        String openTasksJson = serializeTasks(openTasks);
        String today = LocalDate.now().toString();

        List<Map<String, Object>> ops = null;
        boolean usedFallback = false;

        // Nivel 1
        String level1 = buildPrompt(promptTemplate, today, openTasksJson, message);
        String response1 = call(level1);
        try {
            ops = parseJsonList(response1);
        } catch (Exception ignored) {
            // parse falló → ir al fallback
        }

        if (ops == null || allNonFiling(ops)) {
            // Nivel 2
            String level2 = buildPrompt(fallbackTemplate, today, openTasksJson, message);
            String response2 = call(level2);
            try {
                ops = parseJsonList(response2);
            } catch (Exception e) {
                log.error("Fallback también falló al parsear JSON: {}", e.getMessage());
                ops = List.of();
            }
            usedFallback = true;
        }

        return new ClassifyResult(ops, usedFallback);
    }

    private boolean allNonFiling(List<Map<String, Object>> ops) {
        if (ops.isEmpty()) return true;
        return ops.stream().allMatch(op -> {
            String opType = (String) op.get("op");
            // done/update son siempre útiles — no triggean el fallback
            if ("done".equals(opType) || "update".equals(opType) || "move".equals(opType)
                    || "edit".equals(opType) || "dismiss".equals(opType)) return false;
            String bucket = (String) op.get("bucket");
            return bucket == null || NON_FILING_BUCKETS.contains(bucket);
        });
    }

    private String buildPrompt(String template, String today, String openTasksJson, String message) {
        return template
            .replace("{today}", today)
            .replace("{user_context}", userContext)
            .replace("{open_tasks}", openTasksJson)
            .replace("{message}", message);
    }

    private String call(String promptText) {
        return chatClient.prompt().user(promptText).call().content();
    }

    private String serializeTasks(List<Map<String, Object>> openTasks) {
        try {
            if (openTasks.isEmpty()) return "[]";
            String full = objectMapper.writeValueAsString(openTasks);
            if (full.length() <= 6000) return full;
            // Vault demasiado grande: truncar para no explotar el rate limit de Groq
            List<Map<String, Object>> trimmed = openTasks.subList(0, Math.min(80, openTasks.size()));
            log.warn("open_tasks truncated to {} items (original {} chars)", trimmed.size(), full.length());
            return objectMapper.writeValueAsString(trimmed);
        } catch (Exception e) {
            return "[]";
        }
    }

    private List<Map<String, Object>> parseJsonList(String raw) {
        try {
            String json = raw.strip();
            if (json.startsWith("```")) {
                int start = json.indexOf('\n') + 1;
                int end = json.lastIndexOf("```");
                json = json.substring(start, end).strip();
            }
            return objectMapper.readValue(json, new TypeReference<List<Map<String, Object>>>() {});
        } catch (Exception e) {
            throw new RuntimeException("El LLM devolvió JSON inválido: " + raw, e);
        }
    }

    public record ClassifyResult(List<Map<String, Object>> ops, boolean usedFallback) {}
}
