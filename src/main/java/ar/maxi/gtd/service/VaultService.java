package ar.maxi.gtd.service;

import ar.maxi.gtd.util.MarkdownSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    private static final Logger log = LoggerFactory.getLogger(VaultService.class);

    private final Path actionsDir;
    private final Path referenceDir;
    private final UndoStack undoStack;

    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final Set<String> CLASSIFIER_KEYS = Set.of("bucket", "title", "body", "due", "delegado_a", "tags", "message");
    private static final Set<String> INACTIVE_STATUSES = Set.of("done", "dismissed");

    public VaultService(@Value("${gtd.vault.path}") String vaultPath, UndoStack undoStack) {
        this.actionsDir = Path.of(vaultPath, "wiki/gtd/actions");
        this.referenceDir = Path.of(vaultPath, "wiki/references");
        this.undoStack = undoStack;
        try {
            Files.createDirectories(actionsDir);
            Files.createDirectories(referenceDir);
        } catch (IOException e) {
            log.error("Could not create vault directories: {}", e.getMessage());
        }
    }

    public String write(Map<String, Object> item) {
        String bucket = (String) item.get("bucket");
        Path dir = "reference".equals(bucket) ? referenceDir : actionsDir;

        String timestamp = TIMESTAMP.format(LocalDateTime.now());
        String slug = toSlug((String) item.getOrDefault("title", "item"));
        String filename = timestamp + "-" + slug + ".md";
        Path dest = dir.resolve(filename);

        Map<String, Object> frontmatter = new LinkedHashMap<>();
        frontmatter.put("type", "reference".equals(bucket) ? "reference" : "action");
        frontmatter.put("title", item.get("title"));
        frontmatter.put("bucket", bucket);
        frontmatter.put("status", "open");
        frontmatter.put("created", LocalDate.now().toString());
        if (item.get("due") != null) frontmatter.put("due", item.get("due"));
        if (item.get("delegado_a") != null) frontmatter.put("delegado_a", item.get("delegado_a"));
        frontmatter.put("tags", item.getOrDefault("tags", List.of("gtd")));

        item.entrySet().stream()
            .filter(e -> !CLASSIFIER_KEYS.contains(e.getKey()) && !frontmatter.containsKey(e.getKey()))
            .forEach(e -> frontmatter.put(e.getKey(), e.getValue()));

        String body = (String) item.getOrDefault("body", "");
        String content = MarkdownSerializer.serialize(frontmatter, body);

        try {
            Files.writeString(dest, content);
            // undo: previousContent null = delete on undo
            undoStack.push(new UndoStack.UndoEntry(filename, dest, null));
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
                .filter(m -> !INACTIVE_STATUSES.contains(String.valueOf(m.getOrDefault("status", ""))))
                .sorted(Comparator.comparing(m -> String.valueOf(m.getOrDefault("file", ""))))
                .collect(Collectors.toList());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public Map<String, List<Map<String, Object>>> listAll() {
        Map<String, List<Map<String, Object>>> result = new LinkedHashMap<>();
        for (String bucket : List.of("today", "backlog", "waiting", "someday", "reference")) {
            result.put(bucket, list(bucket));
        }
        return result;
    }

    public List<Map<String, Object>> listAllFlat() {
        List<Map<String, Object>> all = new ArrayList<>();
        for (String bucket : List.of("today", "backlog", "waiting", "someday", "reference")) {
            for (Map<String, Object> item : list(bucket)) {
                Map<String, Object> slim = new LinkedHashMap<>();
                slim.put("file", item.get("file"));
                slim.put("title", item.get("title"));
                slim.put("bucket", item.get("bucket"));
                all.add(slim);
            }
        }
        return all;
    }

    public void markDone(String filename) {
        mutate(filename, item -> item.put("status", "done"));
    }

    public void dismissItem(String filename) {
        mutate(filename, item -> item.put("status", "dismissed"));
    }

    public void appendToTask(String filename, String append) {
        mutate(filename, item -> {
            String body = (String) item.remove("body");
            String newBody = (body == null || body.isBlank()) ? append : body + "\n" + append;
            item.put("_body_override", newBody);
        });
    }

    public void replaceBody(String filename, String newBody) {
        mutate(filename, item -> item.put("_body_override", newBody));
    }

    public Map<String, Object> read(String filename) {
        Path file = resolveFile(filename);
        Map<String, Object> item = readFile(file);
        if (item == null) throw new IllegalArgumentException("Archivo no encontrado: " + filename);
        return item;
    }

    public Map<String, Object> stats() {
        Map<String, Integer> counts = new LinkedHashMap<>();
        int total = 0;
        for (String bucket : List.of("today", "backlog", "waiting", "someday", "reference")) {
            int count = list(bucket).size();
            counts.put(bucket, count);
            total += count;
        }
        return Map.of("counts", counts, "total", total);
    }

    public List<Map<String, Object>> history(int limit) {
        List<Map<String, Object>> all = new ArrayList<>();
        for (Path dir : List.of(actionsDir, referenceDir)) {
            try (Stream<Path> files = Files.list(dir)) {
                files.filter(p -> p.toString().endsWith(".md"))
                     .map(this::readFile)
                     .filter(Objects::nonNull)
                     .filter(m -> INACTIVE_STATUSES.contains(String.valueOf(m.getOrDefault("status", ""))))
                     .forEach(all::add);
            } catch (IOException e) { /* directorio vacío, ignorar */ }
        }
        all.sort(Comparator.comparing(
            m -> String.valueOf(m.getOrDefault("updated", m.getOrDefault("created", ""))),
            Comparator.reverseOrder()
        ));
        return limit > 0 ? all.subList(0, Math.min(limit, all.size())) : all;
    }

    public synchronized void moveBucket(String filename, String newBucket, String due) {
        Path file = resolveFile(filename);
        try {
            String previousContent = Files.readString(file);
            undoStack.push(new UndoStack.UndoEntry(filename, file, previousContent));

            Map<String, Object> item = MarkdownSerializer.parse(previousContent);
            String body = (String) item.remove("body");
            item.put("bucket", newBucket);
            if (due != null && !due.isBlank()) item.put("due", due);
            if ("reference".equals(newBucket)) item.put("type", "reference");
            item.put("updated", LocalDate.now().toString());
            String newContent = MarkdownSerializer.serialize(item, body);

            if ("reference".equals(newBucket) && file.getParent().equals(actionsDir)) {
                Path dest = referenceDir.resolve(file.getFileName());
                Files.writeString(dest, newContent);
                Files.delete(file);
            } else {
                Files.writeString(file, newContent);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public void logDiscard(String message, List<Map<String, Object>> ops) {
        Path logFile = actionsDir.getParent().getParent().getParent()
            .resolve(".vault-meta/discard-log.jsonl");
        try {
            Files.createDirectories(logFile.getParent());
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("ts", java.time.Instant.now().toString());
            entry.put("message", message);
            entry.put("ops", ops);
            String line = new com.fasterxml.jackson.databind.ObjectMapper()
                .writeValueAsString(entry) + "\n";
            Files.writeString(logFile, line,
                java.nio.file.StandardOpenOption.CREATE,
                java.nio.file.StandardOpenOption.APPEND);
        } catch (Exception e) {
            log.warn("logDiscard failed, entry not persisted", e);
        }
    }

    // ─── helpers ────────────────────────────────────────────────────────────

    private synchronized void mutate(String filename, java.util.function.Consumer<Map<String, Object>> modifier) {
        Path file = resolveFile(filename);
        try {
            String previousContent = Files.readString(file);
            undoStack.push(new UndoStack.UndoEntry(filename, file, previousContent));

            Map<String, Object> item = MarkdownSerializer.parse(previousContent);
            String body = (String) item.remove("body");
            modifier.accept(item);

            // _body_override permite que appendToTask/replaceBody cambien el body
            String newBody = (String) item.remove("_body_override");
            if (newBody == null) newBody = body;

            item.put("updated", LocalDate.now().toString());
            Files.writeString(file, MarkdownSerializer.serialize(item, newBody));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private Path resolveFile(String filename) {
        if (!filename.matches("[\\w.\\-]+\\.md")) {
            throw new IllegalArgumentException("Filename inválido: " + filename);
        }
        Path inActions = actionsDir.resolve(filename);
        if (Files.exists(inActions)) return inActions;
        Path inRef = referenceDir.resolve(filename);
        if (Files.exists(inRef)) return inRef;
        throw new IllegalArgumentException("Archivo no encontrado: " + filename);
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
