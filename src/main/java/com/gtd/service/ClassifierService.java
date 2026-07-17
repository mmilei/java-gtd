package com.gtd.service;

import com.gtd.util.MarkdownSerializer;
import com.gtd.util.TextNormalizer;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class ClassifierService {

    private static final Logger log = LoggerFactory.getLogger(ClassifierService.class);

    private final LlmProviderService llmProviders;
    private final ObjectMapper objectMapper;
    private final VaultService vault;
    private final String promptTemplate;
    private final String fallbackTemplate;
    private final String userContext;

    private static final Set<String> NON_FILING_BUCKETS = Set.of("now", "discard");
    // Cap on how many open tasks are sent to the LLM as context on each classify request. Runs
    // before serializeTasks()'s coarse 6000-char/80-item safety net — keyword pre-filtering keeps
    // the task the user is actually referring to in context instead of relying on list order.
    private static final int RELEVANT_TASK_LIMIT = 15;

    public ClassifierService(
            LlmProviderService llmProviders,
            ObjectMapper objectMapper,
            VaultService vault,
            @Value("${classifier.template:sample}") String classifierTemplate) {
        this.llmProviders = llmProviders;
        this.objectMapper = objectMapper;
        this.vault = vault;
        try {
            this.promptTemplate = new ClassPathResource(templateResourcePath(classifierTemplate, false))
                .getContentAsString(StandardCharsets.UTF_8);
            this.fallbackTemplate = new ClassPathResource(templateResourcePath(classifierTemplate, true))
                .getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        String ctx = vault.readContextFile("_context/user-profile.md");
        this.userContext = ctx.isBlank() ? "(no user profile loaded)" : ctx;
        log.info("user-profile loaded: {} chars", this.userContext.length());
    }

    /**
     * Pure resource-path resolution, no IO — directly unit-testable. "custom" loads the
     * gitignored, Argentinized personal templates (classifier_custom.st /
     * classifier-fallback-custom.st); any other value — including the "sample" default and
     * unrecognized input — falls back to the committed English sample templates, since those
     * are the only ones guaranteed to exist in a public checkout (the custom files are
     * local-only and absent from CI / anyone else's clone).
     */
    static String templateResourcePath(String classifierTemplate, boolean fallback) {
        boolean custom = "custom".equals(classifierTemplate);
        if (fallback) {
            return custom ? "prompts/classifier-fallback-custom.st" : "prompts/classifier-fallback.st";
        }
        return custom ? "prompts/classifier_custom.st" : "prompts/classifier.st";
    }

    /**
     * Classifies the message with automatic retry:
     * Level 1 → lightweight prompt. If parsing fails or all ops are now/discard → Level 2.
     */
    public ClassifyResult classifyAll(String message, List<Map<String, Object>> openTasks) {
        // Pre-filter to the tasks most likely relevant to this message before serializing — trims
        // the token budget while keeping the target task (for done/edit/move/dismiss ops) in context.
        List<Map<String, Object>> relevantTasks = filterRelevantTasks(openTasks, message, RELEVANT_TASK_LIMIT);
        String openTasksJson = serializeTasks(relevantTasks);
        String today = LocalDate.now().toString();
        // Read fresh each call — the vault gains projects/tags over time, so these lists must
        // not be cached at startup or the classifier would keep classifying against a stale set.
        String knownProjects = formatCsvOrNoneYet(vault.knownProjects());
        String knownTags = formatCsvOrNoneYet(vault.knownTags());
        String validAreas = String.join(", ", vault.validAreas());

        List<Map<String, Object>> ops = null;
        boolean usedFallback = false;

        // Level 1
        String level1 = buildPrompt(promptTemplate, today, userContext, openTasksJson, knownProjects, knownTags, validAreas, message);
        String response1 = call(level1);
        try {
            ops = parseJsonList(response1);
        } catch (Exception e) {
            log.warn("Level 1 classification failed to parse, falling back to level 2: {}", e.getMessage());
        }

        if (ops == null || allNonFiling(ops)) {
            // Level 2
            String level2 = buildPrompt(fallbackTemplate, today, userContext, openTasksJson, knownProjects, knownTags, validAreas, message);
            String response2 = call(level2);
            try {
                ops = parseJsonList(response2);
            } catch (Exception e) {
                log.error("Fallback also failed to parse JSON: {}", e.getMessage());
                ops = List.of();
            }
            usedFallback = true;
        }

        resolveTargets(ops, openTasks);
        return new ClassifyResult(ops, usedFallback);
    }

    /**
     * Replaces each op's target_title with the real target_file resolved against openTasks,
     * or drops target_file entirely if no confident match exists. The LLM is never trusted to
     * reproduce an exact filename — it identifies tasks by title, which it does reliably;
     * matching that title back to a real file is deterministic code, not model output.
     */
    private void resolveTargets(List<Map<String, Object>> ops, List<Map<String, Object>> openTasks) {
        for (Map<String, Object> op : ops) {
            String opType = (String) op.get("op");
            if (opType == null || "create".equals(opType)) continue;
            String resolved = resolveTargetFile(op, openTasks);
            op.remove("target_title");
            if (resolved != null) {
                op.put("target_file", resolved);
            } else {
                op.remove("target_file");
            }
        }
    }

    /**
     * Pure matching logic, no dependencies — directly unit-testable. Tries an exact
     * (case-insensitive, trimmed) title match first, then a unique substring match. Ambiguous
     * (multiple candidates) or no match returns null. Falls back to a legacy target_file only if
     * it's verified present in openTasks, in case the model ignores the target_title contract.
     */
    static String resolveTargetFile(Map<String, Object> op, List<Map<String, Object>> openTasks) {
        Object titleObj = op.get("target_title");
        String targetTitle = titleObj != null ? String.valueOf(titleObj).strip() : null;

        if (targetTitle != null && !targetTitle.isBlank()) {
            String norm = TextNormalizer.normalize(targetTitle);

            // Tasks with a missing/blank title are excluded entirely: a blank normalized title
            // would otherwise substring-match every query (""  is a substring of anything),
            // causing exactly the false-positive mistargeting this method exists to prevent.
            List<Map<String, Object>> candidates = openTasks.stream()
                .filter(t -> {
                    Object titleField = t.get("title");
                    return titleField != null && !String.valueOf(titleField).isBlank();
                })
                .toList();

            List<String> exact = candidates.stream()
                .filter(t -> norm.equals(TextNormalizer.normalize(String.valueOf(t.get("title")))))
                .map(t -> (String) t.get("file"))
                .distinct()
                .toList();
            if (exact.size() == 1) return exact.get(0);

            List<String> partial = candidates.stream()
                .filter(t -> {
                    String title = TextNormalizer.normalize(String.valueOf(t.get("title")));
                    return title.contains(norm) || norm.contains(title);
                })
                .map(t -> (String) t.get("file"))
                .distinct()
                .toList();
            if (partial.size() == 1) return partial.get(0);

            return null;
        }

        Object fileObj = op.get("target_file");
        if (fileObj != null) {
            String file = String.valueOf(fileObj);
            boolean known = openTasks.stream().anyMatch(t -> file.equals(t.get("file")));
            if (known) return file;
        }
        return null;
    }

    private boolean allNonFiling(List<Map<String, Object>> ops) {
        if (ops.isEmpty()) return true;
        return ops.stream().allMatch(op -> {
            String opType = (String) op.get("op");
            // done/update/move/edit/dismiss are always actionable — do not trigger fallback
            if ("done".equals(opType) || "update".equals(opType) || "move".equals(opType)
                    || "edit".equals(opType) || "dismiss".equals(opType)) return false;
            String bucket = (String) op.get("bucket");
            return bucket == null || NON_FILING_BUCKETS.contains(bucket);
        });
    }

    /**
     * Pure placeholder substitution, no IO or state — directly unit-testable (mirrors the
     * static/testable convention already used by resolveTargetFile and templateResourcePath).
     */
    static String buildPrompt(String template, String today, String userContext,
                              String openTasksJson, String knownProjects, String knownTags,
                              String validAreas, String message) {
        return template
            .replace("{today}", today)
            .replace("{user_context}", userContext)
            .replace("{open_tasks}", openTasksJson)
            .replace("{known_projects}", knownProjects)
            .replace("{known_tags}", knownTags)
            .replace("{valid_areas}", validAreas)
            .replace("{message}", message);
    }

    /** Comma-separated list for the prompt, or a clear "none yet" marker when the vault has nothing yet — shared by known_projects and known_tags. */
    static String formatCsvOrNoneYet(List<String> values) {
        return values.isEmpty() ? "(none yet)" : String.join(", ", values);
    }

    /**
     * Pre-filters the open-tasks context sent to the LLM down to the `limit` most relevant tasks,
     * scored by word-overlap between the message and each task's title. Pure, no dependencies —
     * directly unit-testable, same convention as resolveTargetFile/buildPrompt/formatKnownProjects.
     *
     * When the vault is small (tasks.size() <= limit) the whole list passes through untouched.
     * Above that, tasks whose titles share words with the message come first (highest overlap
     * first), and any remaining slots up to `limit` are padded with the other tasks in original
     * order. The padding matters: a done/edit/move/dismiss op needs its target task's title
     * present to resolve, and the user's wording may share no words with that title — a handful
     * of incidental matches on generic words must never evict every non-matching task.
     */
    static List<Map<String, Object>> filterRelevantTasks(List<Map<String, Object>> tasks, String message, int limit) {
        if (tasks.size() <= limit) return tasks;

        Set<String> messageTokens = tokenize(message);
        List<Map<String, Object>> matched = tasks.stream()
            .filter(t -> overlapScore(messageTokens, t) > 0)
            .sorted(Comparator.comparingInt(
                (Map<String, Object> t) -> overlapScore(messageTokens, t)).reversed())
            .toList();
        if (matched.size() >= limit) return matched.subList(0, limit);

        List<Map<String, Object>> result = new ArrayList<>(matched);
        for (Map<String, Object> task : tasks) {
            if (result.size() >= limit) break;
            if (overlapScore(messageTokens, task) == 0) result.add(task);
        }
        return result;
    }

    private static int overlapScore(Set<String> messageTokens, Map<String, Object> task) {
        Object title = task.get("title");
        if (title == null) return 0;
        int score = 0;
        for (String token : tokenize(String.valueOf(title))) {
            if (messageTokens.contains(token)) score++;
        }
        return score;
    }

    /**
     * Canonical words of a string — normalized first (lowercase, diacritics stripped) so that
     * "colchón" in a title and "colchon" in a message produce the same token. Without the
     * normalization step, Java's ASCII-only \W would split accented words apart ("colchón" →
     * "colch", "n") and the overlap scoring would miss exactly the Spanish titles this vault
     * is full of.
     */
    private static Set<String> tokenize(String s) {
        Set<String> tokens = new java.util.HashSet<>();
        for (String token : TextNormalizer.normalize(s).split("\\W+")) {
            if (!token.isBlank()) tokens.add(token);
        }
        return tokens;
    }

    private String call(String promptText) {
        return llmProviders.complete(promptText);
    }

    private String serializeTasks(List<Map<String, Object>> openTasks) {
        try {
            if (openTasks.isEmpty()) return "[]";
            String full = objectMapper.writeValueAsString(openTasks);
            if (full.length() <= 6000) return full;
            // Vault too large: truncate to stay within Groq rate limits
            List<Map<String, Object>> trimmed = openTasks.subList(0, Math.min(80, openTasks.size()));
            log.warn("open_tasks truncated to {} items (original {} chars)", trimmed.size(), full.length());
            return objectMapper.writeValueAsString(trimmed);
        } catch (Exception e) {
            return "[]";
        }
    }

    private List<Map<String, Object>> parseJsonList(String raw) {
        try {
            String json = MarkdownSerializer.stripFences(raw);
            return objectMapper.readValue(json, new TypeReference<List<Map<String, Object>>>() {});
        } catch (Exception e) {
            throw new RuntimeException("LLM returned invalid JSON: " + raw, e);
        }
    }

    public record ClassifyResult(List<Map<String, Object>> ops, boolean usedFallback) {}
}
