package ar.maxi.gtd.service;

import ar.maxi.gtd.util.MarkdownSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Service
public class VaultService {

    private final Path actionsDir;
    private final Path referenceDir;

    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final Set<String> CLASSIFIER_KEYS = Set.of("bucket", "title", "body", "due", "delegado_a", "tags", "message");

    public VaultService(@Value("${gtd.vault.path}") String vaultPath) {
        this.actionsDir = Path.of(vaultPath, "wiki/gtd/actions");
        this.referenceDir = Path.of(vaultPath, "wiki/references");
    }

    public String write(Map<String, Object> item) {
        String bucket = (String) item.get("bucket");
        Path dir = "reference".equals(bucket) ? referenceDir : actionsDir;

        String timestamp = TIMESTAMP.format(LocalDateTime.now());
        String slug = toSlug((String) item.getOrDefault("title", "item"));
        String filename = timestamp + "-" + slug + ".md";

        Map<String, Object> frontmatter = new LinkedHashMap<>();
        frontmatter.put("type", "reference".equals(bucket) ? "reference" : "action");
        frontmatter.put("title", item.get("title"));
        frontmatter.put("bucket", bucket);
        frontmatter.put("status", "open");
        frontmatter.put("created", LocalDate.now().toString());
        if (item.get("due") != null) frontmatter.put("due", item.get("due"));
        if (item.get("delegado_a") != null) frontmatter.put("delegado_a", item.get("delegado_a"));
        frontmatter.put("tags", item.getOrDefault("tags", List.of("gtd")));

        // absorb any extra fields the LLM added — schema extensible sin tocar código
        item.entrySet().stream()
            .filter(e -> !CLASSIFIER_KEYS.contains(e.getKey()) && !frontmatter.containsKey(e.getKey()))
            .forEach(e -> frontmatter.put(e.getKey(), e.getValue()));

        String body = (String) item.getOrDefault("body", "");
        String content = MarkdownSerializer.serialize(frontmatter, body);

        try {
            Files.writeString(dir.resolve(filename), content);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return filename;
    }

    public List<Map<String, Object>> list(String bucket) {
        Path dir = "reference".equals(bucket) ? referenceDir : actionsDir;
        try (Stream<Path> files = Files.list(dir)) {
            return files
                .filter(p -> p.toString().endsWith(".md"))
                .map(this::readFile)
                .filter(Objects::nonNull)
                .filter(m -> bucket == null || bucket.equals(m.get("bucket")))
                .filter(m -> !"done".equals(m.get("status")))
                .sorted(Comparator.comparing(m -> String.valueOf(m.getOrDefault("file", ""))))
                .collect(Collectors.toList());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public Map<String, List<Map<String, Object>>> listAll() {
        Map<String, List<Map<String, Object>>> result = new LinkedHashMap<>();
        for (String bucket : List.of("today", "backlog", "waiting", "someday")) {
            result.put(bucket, list(bucket));
        }
        return result;
    }

    public void markDone(String filename) {
        Path file = actionsDir.resolve(filename);
        if (!Files.exists(file)) file = referenceDir.resolve(filename);
        try {
            String content = Files.readString(file);
            Map<String, Object> item = MarkdownSerializer.parse(content);
            String body = (String) item.remove("body");
            item.put("status", "done");
            item.put("updated", LocalDate.now().toString());
            Files.writeString(file, MarkdownSerializer.serialize(item, body));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private Map<String, Object> readFile(Path file) {
        try {
            Map<String, Object> map = MarkdownSerializer.parse(Files.readString(file));
            map.put("file", file.getFileName().toString());
            return map;
        } catch (IOException e) {
            return null;
        }
    }

    private static String toSlug(String title) {
        String s = Normalizer.normalize(title, Normalizer.Form.NFD)
            .replaceAll("[^\\p{ASCII}]", "")
            .toLowerCase()
            .replaceAll("[^a-z0-9\\s-]", "")
            .trim()
            .replaceAll("\\s+", "-")
            .replaceAll("-+", "-");
        return s.length() > 50 ? s.substring(0, 50) : s;
    }
}
