package ar.maxi.gtd.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

@Service
public class MarkdownifyService {

    private static final Logger log = LoggerFactory.getLogger(MarkdownifyService.class);

    private final ChatClient chatClient;
    private final ObjectMapper objectMapper;
    private final String promptTemplate;
    private final String userContext;

    public MarkdownifyService(@Qualifier("groqChatClient") ChatClient chatClient, ObjectMapper objectMapper, VaultService vault) {
        this.chatClient = chatClient;
        this.objectMapper = objectMapper;
        try {
            this.promptTemplate = new ClassPathResource("prompts/markdownify.st")
                .getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        String ctx = vault.readContextFile("_context/user-profile.md");
        this.userContext = ctx.isBlank() ? "(sin perfil de usuario cargado)" : ctx;
    }

    public record EnrichResult(String body, List<String> tags) {}

    public EnrichResult enrich(String title, String body, String bucket, List<String> tags) {
        String tagsStr = (tags == null || tags.isEmpty()) ? "" : String.join(", ", tags);
        String prompt = promptTemplate
            .replace("{title}",        title  != null ? title  : "")
            .replace("{body}",         body   != null ? body   : "")
            .replace("{bucket}",       bucket != null ? bucket : "backlog")
            .replace("{tags}",         tagsStr)
            .replace("{user_context}", userContext);

        log.debug("markdownify prompt length: {} chars", prompt.length());

        String raw = chatClient.prompt().user(prompt).call().content();

        try {
            String json = raw.strip();
            if (json.startsWith("```")) {
                int start = json.indexOf('\n') + 1;
                int end   = json.lastIndexOf("```");
                json = json.substring(start, end).strip();
            }
            Map<String, Object> result = objectMapper.readValue(json, new TypeReference<>() {});
            String newBody = (String) result.getOrDefault("body", body);
            @SuppressWarnings("unchecked")
            List<String> newTags = (List<String>) result.getOrDefault("tags", List.of("gtd"));
            return new EnrichResult(newBody, newTags);
        } catch (Exception e) {
            log.error("markdownify: failed to parse LLM response: {}", e.getMessage());
            return new EnrichResult(body, List.of("gtd"));
        }
    }
}
