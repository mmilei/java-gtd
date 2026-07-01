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

    private final String vaultPath;
    private final Path inboxDir;
    private final Path somedayDir;
    private final Path resourcesDir;
    private final UndoStack undoStack;

    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final Set<String> CLASSIFIER_KEYS = Set.of("bucket", "title", "body", "due", "delegado_a", "tags", "message", "op");
    private static final Set<String> INACTIVE_STATUSES = Set.of("done", "dismissed");

    public VaultService(@Value("${gtd.vault.path}") String vaultPath, UndoStack undoStack) {
        this.vaultPath    = vaultPath;
        this.inboxDir     = Path.of(vaultPath, "brain/inbox");
        this.somedayDir   = Path.of(vaultPath, "brain/someday");
        this.resourcesDir = Path.of(vaultPath, "brain/resources");
        this.undoStack = undoStack;
        try {
            Files.createDirectories(inboxDir);
            Files.createDirectories(somedayDir);
            Files.createDirectories(resourcesDir);
        } catch (IOException e) {
            log.error("Could not create vault directories: {}", e.getMessage());
        }
        migrateTodaySince();
        migrateTimestamps();
        migrateBucketMismatch();
    }

    public String write(Map<String, Object> item) {
        String bucket = (String) item.get("bucket");
        Path dir = dirFor(bucket);

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
        List<String> tags = tagsFrom(item);
        normalizeTypeTags(tags, bucket);
        frontmatter.put("tags", tags);
        if ("today".equals(bucket)) frontmatter.put("today_since", LocalDate.now().toString());

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
        Path dir = dirFor(bucket);
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

    public void patchMeta(String filename, Map<String, Object> meta) {
        Set<String> allowed = Set.of("title", "tags", "due", "today_since", "markdownified", "delegado_a", "area");
        mutate(filename, item -> meta.forEach((k, v) -> {
            if (allowed.contains(k) && v != null) item.put(k, v);
        }));
    }

    public Map<String, Object> read(String filename) {
        Path file = resolveFile(filename);
        Map<String, Object> item = readFile(file);
        if (item == null) throw new IllegalArgumentException("File not found:" + filename);
        return item;
    }

    public String readContextFile(String relativePath) {
        Path file = Path.of(vaultPath, relativePath);
        if (!Files.exists(file)) return "";
        try {
            return Files.readString(file);
        } catch (IOException e) {
            log.warn("Could not read {}: {}", relativePath, e.getMessage());
            return "";
        }
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

    public List<Map<String, Object>> listStaleToday(int daysThreshold) {
        LocalDate cutoff = LocalDate.now().minusDays(daysThreshold);
        return list("today").stream()
            .filter(m -> {
                String since = String.valueOf(m.getOrDefault("today_since", ""));
                if (since.isBlank()) return false;
                try { return LocalDate.parse(since).isBefore(cutoff) || LocalDate.parse(since).isEqual(cutoff); }
                catch (Exception e) { return false; }
            })
            .collect(Collectors.toList());
    }

    public List<Map<String, Object>> listDueSoon(int days) {
        LocalDate today = LocalDate.now();
        LocalDate limit = today.plusDays(days);
        List<Map<String, Object>> result = new ArrayList<>();
        for (String bucket : List.of("today", "backlog", "waiting")) {
            list(bucket).stream()
                .filter(m -> {
                    String due = String.valueOf(m.getOrDefault("due", ""));
                    if (due.isBlank() || "null".equals(due)) return false;
                    try {
                        LocalDate d = LocalDate.parse(due);
                        return !d.isBefore(today) && !d.isAfter(limit);
                    } catch (Exception e) { return false; }
                })
                .forEach(result::add);
        }
        result.sort(Comparator.comparing(m -> String.valueOf(m.getOrDefault("due", ""))));
        return result;
    }

    public List<Map<String, Object>> listCompletedSince(int days) {
        LocalDate cutoff = LocalDate.now().minusDays(days);
        List<Map<String, Object>> all = new ArrayList<>();
        for (Path dir : List.of(inboxDir, somedayDir, resourcesDir)) {
            try (Stream<Path> files = Files.list(dir)) {
                files.filter(p -> p.toString().endsWith(".md"))
                     .map(this::readFile)
                     .filter(Objects::nonNull)
                     .filter(m -> INACTIVE_STATUSES.contains(String.valueOf(m.getOrDefault("status", ""))))
                     .filter(m -> {
                         String updated = String.valueOf(m.getOrDefault("updated", m.getOrDefault("created", "")));
                         try { return !LocalDate.parse(updated).isBefore(cutoff); }
                         catch (Exception e) { return false; }
                     })
                     .forEach(all::add);
            } catch (IOException e) { /* empty directory, skip */ }
        }
        all.sort(Comparator.comparing(
            m -> String.valueOf(m.getOrDefault("updated", "")),
            Comparator.reverseOrder()
        ));
        return all;
    }

    public List<Map<String, Object>> history(int limit) {
        List<Map<String, Object>> all = new ArrayList<>();
        for (Path dir : List.of(inboxDir, somedayDir, resourcesDir)) {
            try (Stream<Path> files = Files.list(dir)) {
                files.filter(p -> p.toString().endsWith(".md"))
                     .map(this::readFile)
                     .filter(Objects::nonNull)
                     .filter(m -> INACTIVE_STATUSES.contains(String.valueOf(m.getOrDefault("status", ""))))
                     .forEach(all::add);
            } catch (IOException e) { /* empty directory, skip */ }
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

            Map<String, Object> item = MarkdownSerializer.parse(previousContent, filename);
            String body = (String) item.remove("body");
            item.put("bucket", newBucket);
            if (due != null && !due.isBlank()) item.put("due", due);
            if ("reference".equals(newBucket)) {
                item.put("type", "reference");
            } else if ("reference".equals(String.valueOf(item.getOrDefault("type", "")))) {
                item.put("type", "action");
            }
            List<String> tags = tagsFrom(item);
            normalizeTypeTags(tags, newBucket);
            item.put("tags", tags);
            if ("today".equals(newBucket) && !item.containsKey("today_since")) {
                item.put("today_since", LocalDate.now().toString());
            }
            item.put("updated", LocalDate.now().toString());
            String newContent = MarkdownSerializer.serialize(item, body);

            Files.writeString(file, newContent);

            Path newDir = dirFor(newBucket);
            if (!file.getParent().equals(newDir)) {
                Path dest = newDir.resolve(file.getFileName());
                try {
                    Files.move(file, dest, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
                } catch (IOException e) {
                    log.error("moveBucket: atomic move failed for {} ({} -> {}), file stays at origin with updated content: {}",
                        filename, file.getParent(), newDir, e.getMessage());
                    throw e;
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public void logDiscard(String message, List<Map<String, Object>> ops) {
        Path logFile = Path.of(this.vaultPath).resolve(".vault-meta/discard-log.jsonl");
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

    private Path dirFor(String bucket) {
        return switch (bucket) {
            case "reference" -> resourcesDir;
            case "someday"   -> somedayDir;
            default          -> inboxDir;
        };
    }

    private void migrateTodaySince() {
        for (Path dir : List.of(inboxDir, somedayDir)) {
            try (Stream<Path> files = Files.list(dir)) {
                files.filter(p -> p.toString().endsWith(".md")).forEach(p -> {
                    try {
                        String content = Files.readString(p);
                        Map<String, Object> item = MarkdownSerializer.parse(content, p.getFileName().toString());
                        if ("today".equals(item.get("bucket")) && item.get("today_since") == null) {
                            String created = (String) item.get("created");
                            if (created != null) {
                                String body = (String) item.remove("body");
                                item.put("today_since", created);
                                Files.writeString(p, MarkdownSerializer.serialize(item, body));
                            }
                        }
                    } catch (Exception e) {
                        log.warn("migrateTodaySince: skipping {}: {}", p.getFileName(), e.getMessage());
                    }
                });
            } catch (IOException e) {
                log.warn("migrateTodaySince: could not list {}: {}", dir, e.getMessage());
            }
        }
    }

    /**
     * Self-heals filename duplicates and misplaced files left behind by moveBucket() failures
     * (write succeeded, move to the new directory didn't). Duplicate: same filename in both
     * inboxDir and somedayDir — keep the copy whose bucket matches the directory it's in
     * (tie/mismatch-both broken by most recent "updated"), delete the other. Misplaced-no-duplicate:
     * single copy sitting in a directory that doesn't match its own bucket field — relocate it.
     */
    private void migrateBucketMismatch() {
        Map<String, List<Path>> byFilename = new LinkedHashMap<>();
        for (Path dir : List.of(inboxDir, somedayDir)) {
            try (Stream<Path> files = Files.list(dir)) {
                files.filter(p -> p.toString().endsWith(".md"))
                     .forEach(p -> byFilename.computeIfAbsent(p.getFileName().toString(), k -> new ArrayList<>()).add(p));
            } catch (IOException e) {
                log.warn("migrateBucketMismatch: could not list {}: {}", dir, e.getMessage());
            }
        }
        for (Map.Entry<String, List<Path>> entry : byFilename.entrySet()) {
            List<Path> paths = entry.getValue();
            if (paths.size() > 1) {
                resolveDuplicate(entry.getKey(), paths);
            } else {
                relocateIfMismatched(paths.get(0));
            }
        }
    }

    private void resolveDuplicate(String filename, List<Path> paths) {
        Map<Path, Map<String, Object>> parsed = new LinkedHashMap<>();
        for (Path p : paths) {
            Map<String, Object> item = readFile(p);
            if (item != null) parsed.put(p, item);
        }
        if (parsed.size() < 2) return;

        Path keep = null;
        for (Map.Entry<Path, Map<String, Object>> e : parsed.entrySet()) {
            String bucket = String.valueOf(e.getValue().getOrDefault("bucket", ""));
            if (e.getKey().getParent().equals(dirFor(bucket))) {
                keep = e.getKey();
                break;
            }
        }
        if (keep == null) {
            keep = parsed.entrySet().stream()
                .max(Comparator.comparing(e -> String.valueOf(e.getValue().getOrDefault("updated", e.getValue().getOrDefault("created", "")))))
                .map(Map.Entry::getKey)
                .orElse(paths.get(0));
        }
        for (Path p : paths) {
            if (p.equals(keep)) continue;
            try {
                Files.delete(p);
                log.warn("migrateBucketMismatch: removed stale duplicate {} (kept {})", p, keep);
            } catch (IOException e) {
                log.warn("migrateBucketMismatch: could not delete stale duplicate {}: {}", p, e.getMessage());
            }
        }
    }

    private void relocateIfMismatched(Path path) {
        Map<String, Object> item = readFile(path);
        if (item == null) return;
        String bucket = String.valueOf(item.getOrDefault("bucket", ""));
        Path correctDir = dirFor(bucket);
        if (path.getParent().equals(correctDir)) return;
        Path dest = correctDir.resolve(path.getFileName());
        try {
            Files.move(path, dest, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            log.info("migrateBucketMismatch: relocated {} to {} (bucket: {})", path.getFileName(), correctDir, bucket);
        } catch (IOException e) {
            log.warn("migrateBucketMismatch: could not relocate {}: {}", path, e.getMessage());
        }
    }

    private synchronized void mutate(String filename, java.util.function.Consumer<Map<String, Object>> modifier) {
        Path file = resolveFile(filename);
        try {
            String previousContent = Files.readString(file);
            undoStack.push(new UndoStack.UndoEntry(filename, file, previousContent));

            Map<String, Object> item = MarkdownSerializer.parse(previousContent, filename);
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
            throw new IllegalArgumentException("Invalid filename: " + filename);
        }
        for (Path dir : List.of(inboxDir, somedayDir, resourcesDir)) {
            Path p = dir.resolve(filename);
            if (Files.exists(p)) return p;
        }
        throw new IllegalArgumentException("File not found:" + filename);
    }

    private Map<String, Object> readFile(Path file) {
        try {
            Map<String, Object> map = MarkdownSerializer.parse(Files.readString(file), file.getFileName().toString());
            map.put("file", file.getFileName().toString());
            return map;
        } catch (IOException e) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private static List<String> tagsFrom(Map<String, Object> item) {
        Object raw = item.get("tags");
        List<String> tags = (raw instanceof List<?>) ? new ArrayList<>((List<String>) raw) : new ArrayList<>();
        if (!tags.contains("gtd")) tags.add(0, "gtd");
        return tags;
    }

    private static void normalizeTypeTags(List<String> tags, String bucket) {
        if ("reference".equals(bucket)) {
            tags.remove("action");
            if (!tags.contains("reference")) tags.add("reference");
        } else {
            tags.remove("reference");
            if (!tags.contains("action")) tags.add("action");
        }
    }

    private void migrateTimestamps() {
        java.util.regex.Pattern TS_IN_YAML = java.util.regex.Pattern.compile("\\d{4}-\\d{2}-\\d{2}T");
        for (Path dir : List.of(inboxDir, somedayDir, resourcesDir)) {
            try (Stream<Path> files = Files.list(dir)) {
                files.filter(p -> p.toString().endsWith(".md")).forEach(p -> {
                    try {
                        String content = Files.readString(p);
                        int fmEnd = content.indexOf("\n---", content.indexOf('\n') + 1);
                        String frontmatter = fmEnd > 0 ? content.substring(0, fmEnd) : "";
                        if (!TS_IN_YAML.matcher(frontmatter).find()) return;

                        Map<String, Object> item = MarkdownSerializer.parse(content, p.getFileName().toString());
                        String body = (String) item.remove("body");
                        Files.writeString(p, MarkdownSerializer.serialize(item, body));
                        log.info("migrateTimestamps: fixed {}", p.getFileName());
                    } catch (Exception e) {
                        log.warn("migrateTimestamps: skipping {}: {}", p.getFileName(), e.getMessage());
                    }
                });
            } catch (IOException e) {
                log.warn("migrateTimestamps: could not list {}: {}", dir, e.getMessage());
            }
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
