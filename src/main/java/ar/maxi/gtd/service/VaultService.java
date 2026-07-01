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
    private final Path archiveDuplicatesDir;
    private final UndoStack undoStack;

    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final Set<String> CLASSIFIER_KEYS = Set.of("bucket", "title", "body", "due", "delegado_a", "tags", "message", "op");
    private static final Set<String> INACTIVE_STATUSES = Set.of("done", "dismissed");
    private static final List<String> ALL_BUCKETS = List.of("today", "backlog", "waiting", "someday", "reference");

    public VaultService(
            @Value("${gtd.vault.path}") String vaultPath,
            UndoStack undoStack,
            @Value("${gtd.vault.migrate-today-since:true}") boolean migrateTodaySinceEnabled,
            @Value("${gtd.vault.migrate-timestamps:true}") boolean migrateTimestampsEnabled,
            @Value("${gtd.vault.migrate-bucket-mismatch:true}") boolean migrateBucketMismatchEnabled,
            @Value("${gtd.vault.migrate-delegado-list:true}") boolean migrateDelegadoListEnabled) {
        this.vaultPath    = vaultPath;
        this.inboxDir     = Path.of(vaultPath, "brain/inbox");
        this.somedayDir   = Path.of(vaultPath, "brain/someday");
        this.resourcesDir = Path.of(vaultPath, "brain/resources");
        this.archiveDuplicatesDir = Path.of(vaultPath, "brain/.archive/duplicates");
        this.undoStack = undoStack;
        try {
            Files.createDirectories(inboxDir);
            Files.createDirectories(somedayDir);
            Files.createDirectories(resourcesDir);
            Files.createDirectories(archiveDuplicatesDir);
        } catch (IOException e) {
            log.error("Could not create vault directories: {}", e.getMessage());
        }
        if (migrateTodaySinceEnabled) migrateTodaySince();
        if (migrateTimestampsEnabled) migrateTimestamps();
        if (migrateBucketMismatchEnabled) migrateBucketMismatch();
        if (migrateDelegadoListEnabled) migrateDelegadoToList();
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
        List<String> delegados = delegadoAsList(item.get("delegado_a"));
        if (!delegados.isEmpty()) frontmatter.put("delegado_a", delegados);
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
        for (String bucket : ALL_BUCKETS) {
            result.put(bucket, list(bucket));
        }
        return result;
    }

    public List<Map<String, Object>> listAllFlat() {
        List<Map<String, Object>> all = new ArrayList<>();
        for (String bucket : ALL_BUCKETS) {
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

    /** Unique tags across the vault with their count per bucket, e.g. {"shopping": {"today": 1, "backlog": 2, ...}}. */
    public Map<String, Map<String, Integer>> tagCounts() {
        Map<String, Map<String, Integer>> counts = new TreeMap<>();
        listAll().forEach((bucket, items) -> items.forEach(item -> {
            if (!(item.get("tags") instanceof List<?> tags)) return;
            for (Object t : tags) {
                counts.computeIfAbsent(String.valueOf(t), k -> {
                    Map<String, Integer> m = new LinkedHashMap<>();
                    ALL_BUCKETS.forEach(b -> m.put(b, 0));
                    return m;
                }).merge(bucket, 1, Integer::sum);
            }
        }));
        return counts;
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
            if (!allowed.contains(k) || v == null) return;
            item.put(k, "delegado_a".equals(k) ? delegadoAsList(v) : v);
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
        for (String bucket : ALL_BUCKETS) {
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

            Path newDir = dirFor(newBucket);
            if (!isInOwnDir(file, newBucket)) {
                Path dest = newDir.resolve(file.getFileName());
                try {
                    moveAtomically(file, dest);
                } catch (IOException e) {
                    log.error("moveBucket: atomic move failed for {} ({} -> {}), file untouched at origin: {}",
                        filename, file.getParent(), newDir, e.getMessage());
                    throw e;
                }
                Files.writeString(dest, newContent);
            } else {
                Files.writeString(file, newContent);
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

    /** Rewrites legacy scalar delegado_a ("Juan") as a single-element list (["Juan"]) on disk. */
    private void migrateDelegadoToList() {
        for (Path dir : List.of(inboxDir, somedayDir, resourcesDir)) {
            try (Stream<Path> files = Files.list(dir)) {
                files.filter(p -> p.toString().endsWith(".md")).forEach(p -> {
                    try {
                        String content = Files.readString(p);
                        Map<String, Object> item = MarkdownSerializer.parse(content, p.getFileName().toString());
                        if (item.get("delegado_a") instanceof String) {
                            String body = (String) item.remove("body");
                            item.put("delegado_a", delegadoAsList(item.get("delegado_a")));
                            Files.writeString(p, MarkdownSerializer.serialize(item, body));
                        }
                    } catch (Exception e) {
                        log.warn("migrateDelegadoToList: skipping {}: {}", p.getFileName(), e.getMessage());
                    }
                });
            } catch (IOException e) {
                log.warn("migrateDelegadoToList: could not list {}: {}", dir, e.getMessage());
            }
        }
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
     * Self-heals filename duplicates and misplaced files left behind by moveBucket() failures.
     * Duplicate: same filename in more than one of inboxDir/somedayDir/resourcesDir — keep the
     * copy whose bucket matches the directory it's in, breaking ties (or mismatch-both) by most
     * recent "updated"; quarantine the other (see quarantineDuplicate — never deleted outright).
     * Misplaced-no-duplicate: single copy sitting in a directory that doesn't match its own
     * bucket field — relocate it.
     *
     * Only touches files that actually have a "bucket" key — brain/inbox and brain/someday also
     * hold non-GTD notes (meta index pages, freeform ideas/someday-maybe entries with their own
     * type/status schema) that were never written by VaultService and must not be moved or
     * treated as duplicates just because the same filename convention happens to collide.
     */
    private void migrateBucketMismatch() {
        Map<String, List<Map.Entry<Path, Map<String, Object>>>> byFilename = new LinkedHashMap<>();
        for (Path dir : List.of(inboxDir, somedayDir, resourcesDir)) {
            try (Stream<Path> files = Files.list(dir)) {
                files.filter(p -> p.toString().endsWith(".md")).forEach(p -> {
                    Map<String, Object> item = readFile(p);
                    if (item != null && item.get("bucket") != null) {
                        byFilename.computeIfAbsent(p.getFileName().toString(), k -> new ArrayList<>())
                            .add(Map.entry(p, item));
                    }
                });
            } catch (IOException e) {
                log.warn("migrateBucketMismatch: could not list {}: {}", dir, e.getMessage());
            }
        }
        for (List<Map.Entry<Path, Map<String, Object>>> entries : byFilename.values()) {
            if (entries.size() > 1) {
                resolveDuplicate(entries);
            } else {
                relocateIfMismatched(entries.get(0));
            }
        }
    }

    private boolean isInOwnDir(Path path, String bucket) {
        return path.getParent().equals(dirFor(bucket));
    }

    private void resolveDuplicate(List<Map.Entry<Path, Map<String, Object>>> entries) {
        Comparator<Map.Entry<Path, Map<String, Object>>> byOwnDirMatch = Comparator.comparing(e ->
            isInOwnDir(e.getKey(), String.valueOf(e.getValue().getOrDefault("bucket", ""))));
        Map.Entry<Path, Map<String, Object>> keep = entries.stream()
            .max(byOwnDirMatch.thenComparing(e -> String.valueOf(e.getValue().getOrDefault("updated", e.getValue().getOrDefault("created", "")))))
            .orElseThrow();

        for (Map.Entry<Path, Map<String, Object>> e : entries) {
            if (e.getKey().equals(keep.getKey())) continue;
            quarantineDuplicate(e.getKey(), e.getValue(), keep.getKey());
        }
    }

    /**
     * A duplicate "loser" is never deleted outright — it's marked dismissed and moved to
     * brain/.archive/duplicates/, out of the directories VaultService actively searches, so a
     * wrong keep/discard decision here is always recoverable by hand instead of silent data loss.
     * Moves first, with the file's original bytes untouched, and only rewrites the dismissed
     * status at the destination afterwards — mirrors moveBucket()'s ordering so a failed move
     * can never leave the loser mutated (and thus wrongly "freshest") back at its origin.
     */
    private void quarantineDuplicate(Path loser, Map<String, Object> item, Path keptAt) {
        Path dest = archiveDuplicatesDir.resolve(loser.getFileName());
        try {
            moveAtomically(loser, dest);
        } catch (IOException e) {
            log.warn("migrateBucketMismatch: could not quarantine stale duplicate {}: {}", loser, e.getMessage());
            return;
        }
        try {
            String body = (String) item.remove("body");
            item.put("status", "dismissed");
            item.put("dismiss_reason", "duplicate, kept " + keptAt.getFileName());
            item.put("updated", LocalDate.now().toString());
            Files.writeString(dest, MarkdownSerializer.serialize(item, body));
            log.warn("migrateBucketMismatch: quarantined stale duplicate {} (kept {})", loser.getFileName(), keptAt);
        } catch (IOException e) {
            log.warn("migrateBucketMismatch: quarantined {} but failed to stamp dismissed status: {}", loser.getFileName(), e.getMessage());
        }
    }

    private void relocateIfMismatched(Map.Entry<Path, Map<String, Object>> entry) {
        Path path = entry.getKey();
        String bucket = String.valueOf(entry.getValue().getOrDefault("bucket", ""));
        if (isInOwnDir(path, bucket)) return;
        Path correctDir = dirFor(bucket);
        Path dest = correctDir.resolve(path.getFileName());
        try {
            moveAtomically(path, dest);
            log.info("migrateBucketMismatch: relocated {} to {} (bucket: {})", path.getFileName(), correctDir, bucket);
        } catch (IOException e) {
            log.warn("migrateBucketMismatch: could not relocate {}: {}", path, e.getMessage());
        }
    }

    /**
     * Files.move(..., ATOMIC_MOVE) on Windows silently overwrites an existing destination
     * instead of throwing (unlike POSIX filesystems) — explicit existence check makes the
     * "never silently clobber a file" guarantee hold on every platform.
     */
    private void moveAtomically(Path src, Path dest) throws IOException {
        if (Files.exists(dest)) {
            throw new java.nio.file.FileAlreadyExistsException(dest.toString());
        }
        Files.move(src, dest, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
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

    /**
     * Normalizes delegado_a ("related people") to a List<String> regardless of whether the
     * caller sent a list (frontend, new format) or a bare string (legacy data, old classifier
     * output) — never both shapes coexist past this point.
     */
    private static List<String> delegadoAsList(Object raw) {
        if (raw instanceof List<?> list) {
            return list.stream().filter(Objects::nonNull).map(String::valueOf).map(String::strip)
                .filter(s -> !s.isBlank()).distinct().toList();
        }
        if (raw instanceof String s && !s.isBlank()) {
            return List.of(s.strip());
        }
        return List.of();
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
