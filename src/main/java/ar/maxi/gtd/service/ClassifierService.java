package ar.maxi.gtd.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@Service
public class ClassifierService {

    private final ChatClient chatClient;
    private final ObjectMapper objectMapper;
    private final String promptTemplate;

    public ClassifierService(ChatClient.Builder builder, ObjectMapper objectMapper) {
        this.chatClient = builder.build();
        this.objectMapper = objectMapper;
        try {
            this.promptTemplate = new ClassPathResource("prompts/classifier.st")
                .getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public List<Map<String, Object>> classifyAll(String message, List<Map<String, Object>> openTasks) {
        String openTasksJson;
        try {
            openTasksJson = openTasks.isEmpty() ? "[]" : objectMapper.writeValueAsString(openTasks);
        } catch (Exception e) {
            openTasksJson = "[]";
        }

        String promptText = promptTemplate
            .replace("{today}", LocalDate.now().toString())
            .replace("{open_tasks}", openTasksJson)
            .replace("{message}", message);

        String response = chatClient.prompt()
            .user(promptText)
            .call()
            .content();

        return parseJsonList(response);
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
}
