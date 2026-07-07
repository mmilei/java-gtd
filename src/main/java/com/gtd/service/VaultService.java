package com.gtd.service;

import com.gtd.util.MarkdownSerializer;
import com.fasterxml.jackson.databind.ObjectMapper;
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
    private final Path todayDir;
    private final Path backlogDir;
    private final Path waitingDir;
    private final Path somedayDir;
    private final Path resourcesDir;
    private final Path doneDir;
    private final Path discardDir;
    private final Path legacyInboxDir;
    private final List<Path> allDirs;
    private final Path archiveDuplicatesDir;
    private final EventLog eventLog;
    private final ObjectMapper mapper;

    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final Set<String> CLASSIFIER_KEYS = Set.of("bucket", "title", "body", "due", "delegado_a", "tags", "message", "op", "project");
    private static final Set<String> INACTIVE_STATUSES = Set.of("done", "dismissed");
    private static final List<String> ALL_BUCKETS = List.of("today", "backlog", "waiting", "someday", "reference");

    public VaultService(
            @Value("${gtd.vault.path}") String vaultPath,
            EventLog eventLog,
            ObjectMapper mapper,
            @Value("${gtd.vault.migrate-today-since:true}") boolean migrateTodaySinceEnabled,
            @Value("${gtd.vault.migrate-timestamps:true}") boolean migrateTimestampsEnabled,
            @Value("${gtd.vault.migrate-bucket-mismatch:true}") boolean migrateBucketMismatchEnabled,
            @Value("${gtd.vault.migrate-delegado-list:true}") boolean migrateDelegadoListEnabled,
            @Value("${gtd.vault.migrate-folder-split:true}") boolean migrateFolderSplitEnabled) {
        this.vaultPath      = vaultPath;
        this.mapper         = mapper;
        this.todayDir       = Path.of(vaultPath, "brain/today");
        this.backlogDir     = Path.of(vaultPath, "brain/backlog");
        this.waitingDir     = Path.of(vaultPath, "brain/waiting");
        this.somedayDir     = Path.of(vaultPath, "brain/someday");
        this.resourcesDir   = Path.of(vaultPath, "brain/resources");
        this.doneDir        = Path.of(vaultPath, "brain/done");
        this.discardDir     = Path.of(vaultPath, "brain/discard");
        this.legacyInboxDir = Path.of(vaultPath, "brain/inbox");
        this.allDirs = List.of(todayDir, backlogDir, waitingDir, somedayDir, resourcesDir, doneDir, discardDir);
        this.archiveDuplicatesDir = Path.of(vaultPath, "brain/.archive/duplicates");
        this.eventLog = eventLog;
        try {
            for (Path dir : allDirs) Files.createDirectories(dir);
            Files.createDirectories(archiveDuplicatesDir);
        } catch (IOException e) {
            log.error("Could not create vault directories: {}", e.getMessage());
        }
        if (migrateFolderSplitEnabled) migrateFolderSplit();
        if (migrateTodaySinceEnabled) migrateTodaySince();
        if (migrateTimestampsEnabled) migrateTimestamps();
        if (migrateBucketMismatchEnabled) migrateBucketMismatch();
        if (migrateDelegadoListEnabled) migrateDelegadoToList();
    }

    public String write(Map<String, Object> item, Actor actor) {
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
        // project is in CLASSIFIER_KEYS (excluded from the generic passthrough below), so it needs
        // explicit handling here — and doing it explicitly lets us drop blank/null values the LLM
        // may emit instead of persisting an empty project field.
        Object projectRaw = item.get("project");
        if (projectRaw != null && !String.valueOf(projectRaw).isBlank()) {
            frontmatter.put("project", String.valueOf(projectRaw).strip());
        }
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
            eventLog.append(Event.mutation(actor, "create", filename, String.valueOf(frontmatter.get("title")),
                null, dest.toString(), null, "none", null));
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
                .filter(m -> bucket.equals(m.get("bucket")))
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

    /**
     * Sorted, deduplicated list of the non-blank `project` field values found across all buckets,
     * including done/discarded tasks — an established project shouldn't drop out of the classifier's
     * known-projects context just because its tasks are finished; that's exactly when continuity
     * (recognizing the same project on the next task) matters most.
     * Fed to the classifier so it can tag a new task with a project the vault already knows about
     * instead of guessing blind. Returns an empty list when no item has a project yet — no
     * invented fallback values, since a bad project name pollutes the field for every later task.
     */
    public List<String> knownProjects() {
        Set<String> projects = new TreeSet<>();
        listAll().forEach((bucket, items) -> items.forEach(item -> addProject(projects, item)));
        for (Path dir : List.of(doneDir, discardDir)) {
            try (Stream<Path> files = Files.list(dir)) {
                files.filter(p -> p.toString().endsWith(".md"))
                     .map(this::readFile)
                     .filter(Objects::nonNull)
                     .forEach(item -> addProject(projects, item));
            } catch (IOException e) { /* empty directory, skip */ }
        }
        return new ArrayList<>(projects);
    }

    private static void addProject(Set<String> projects, Map<String, Object> item) {
        Object raw = item.get("project");
        if (raw == null) return;
        String project = String.valueOf(raw).strip();
        if (!project.isEmpty()) projects.add(project);
    }

    public void markDone(String filename, Actor actor) {
        mutate(filename, doneDir, actor, "done", item -> {
            item.put("status", "done");
            item.putIfAbsent("done_date", LocalDate.now().toString());
        });
    }

    public void dismissItem(String filename, Actor actor) {
        dismissItem(filename, actor, "none", null);
    }

    /** Used by POST /api/chat/confirm to record that this dismiss was an LLM proposal the user approved, linked back to the chat message that proposed it. */
    public void dismissItem(String filename, Actor actor, String confirmation, String chatRef) {
        mutate(filename, discardDir, actor, "dismiss", confirmation, chatRef, item -> {
            item.put("status", "dismissed");
            item.putIfAbsent("discarded_date", LocalDate.now().toString());
        });
    }

    // TODO: not yet wired to any endpoint/prompt — utilizar en el flujo/prompt cuando el
    // classifier o el frontend necesiten "agregar" en vez de "reemplazar" el body de una tarea.
    public void appendToTask(String filename, String append, Actor actor) {
        mutate(filename, actor, "update", item -> {
            String body = (String) item.remove("body");
            String newBody = (body == null || body.isBlank()) ? append : body + "\n" + append;
            item.put("_body_override", newBody);
        });
    }

    public void replaceBody(String filename, String newBody, Actor actor) {
        replaceBody(filename, newBody, actor, "none", null);
    }

    /** Used by POST /api/chat/confirm to record that this edit/update was an LLM proposal the user approved, linked back to the chat message that proposed it. */
    public void replaceBody(String filename, String newBody, Actor actor, String confirmation, String chatRef) {
        mutate(filename, actor, "edit", confirmation, chatRef, item -> item.put("_body_override", newBody));
    }

    public void patchMeta(String filename, Map<String, Object> meta, Actor actor) {
        Set<String> allowed = Set.of("title", "tags", "due", "today_since", "markdownified", "delegado_a", "area", "estimate_minutes", "confirmed", "project");
        mutate(filename, actor, "patch", item -> meta.forEach((k, v) -> {
            if (!allowed.contains(k) || v == null) return;
            item.put(k, "delegado_a".equals(k) ? delegadoAsList(v) : v);
        }));
    }

    /**
     * Inverts a mutation event: no previousContent means it was a create (undo = delete);
     * otherwise moves the file back from path_after to path_before (if they differ — covers
     * move/done/dismiss) and restores previous_content. This single path-based rule replaces the
     * old op-specific undo logic and, as a side effect, fixes the historical bug where undoing a
     * move restored content at the old path but left the moved-to copy behind as a duplicate.
     */
    public synchronized void undoEvent(Event e) {
        try {
            Path after = e.pathAfter() != null ? Path.of(e.pathAfter()) : null;
            if (e.previousContent() == null) {
                if (after != null) Files.deleteIfExists(after);
                return;
            }
            Path before = Path.of(e.pathBefore());
            if (after != null && !after.equals(before)) {
                moveAtomically(after, before);
            }
            Files.writeString(before, e.previousContent());
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    public Map<String, Object> read(String filename) {
        Path file = resolveFile(filename);
        Map<String, Object> item = readFile(file);
        if (item == null) throw new IllegalArgumentException("File not found:" + filename);
        return item;
    }

    public String readContextFile(String relativePath) {
        Path base = Path.of(vaultPath).toAbsolutePath().normalize();
        Path file = base.resolve(relativePath).normalize();
        if (!file.startsWith(base)) {
            throw new IllegalArgumentException("Invalid path: " + relativePath);
        }
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
        for (var entry : listAll().entrySet()) {
            int count = entry.getValue().size();
            counts.put(entry.getKey(), count);
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
        for (Path dir : List.of(doneDir, discardDir)) {
            try (Stream<Path> files = Files.list(dir)) {
                files.filter(p -> p.toString().endsWith(".md"))
                     .map(this::readFile)
                     .filter(Objects::nonNull)
                     .filter(m -> {
                         try { return !LocalDate.parse(completionDate(m)).isBefore(cutoff); }
                         catch (Exception e) { return false; }
                     })
                     .forEach(all::add);
            } catch (IOException e) { /* empty directory, skip */ }
        }
        all.sort(Comparator.comparing(VaultService::completionDate, Comparator.reverseOrder()));
        return all;
    }

    public List<Map<String, Object>> history(int limit) {
        List<Map<String, Object>> all = new ArrayList<>();
        for (Path dir : List.of(doneDir, discardDir)) {
            try (Stream<Path> files = Files.list(dir)) {
                files.filter(p -> p.toString().endsWith(".md"))
                     .map(this::readFile)
                     .filter(Objects::nonNull)
                     .forEach(all::add);
            } catch (IOException e) { /* empty directory, skip */ }
        }
        all.sort(Comparator.comparing(VaultService::completionDate, Comparator.reverseOrder()));
        return limit > 0 ? all.subList(0, Math.min(limit, all.size())) : all;
    }

    /**
     * done_date/discarded_date are write-once (see markDone/dismissItem) so a later edit to an
     * already-completed item can't quietly bump it back to the top of history/listCompletedSince
     * the way the mutable `updated` field would — falls back to updated/created only for the
     * (should-not-happen post-migration) case where neither is present.
     */
    private static String completionDate(Map<String, Object> item) {
        Object doneDate = item.get("done_date");
        if (doneDate != null) return String.valueOf(doneDate);
        Object discardedDate = item.get("discarded_date");
        if (discardedDate != null) return String.valueOf(discardedDate);
        return String.valueOf(item.getOrDefault("updated", item.getOrDefault("created", "")));
    }

    public void moveBucket(String filename, String newBucket, String due, Actor actor) {
        mutate(filename, dirFor(newBucket), actor, "move", item -> {
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
        });
    }

    public void logDiscard(String message, List<Map<String, Object>> ops) {
        Path logFile = Path.of(this.vaultPath).resolve(".vault-meta/discard-log.jsonl");
        try {
            Files.createDirectories(logFile.getParent());
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("ts", java.time.Instant.now().toString());
            entry.put("message", message);
            entry.put("ops", ops);
            String line = mapper.writeValueAsString(entry) + "\n";
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
            case "today"     -> todayDir;
            case "backlog"   -> backlogDir;
            case "waiting"   -> waitingDir;
            case "someday"   -> somedayDir;
            case "reference" -> resourcesDir;
            default -> throw new IllegalArgumentException("Unknown bucket: " + bucket);
        };
    }

    /**
     * One-time move of files out of the legacy shared brain/inbox/ (and any done/dismissed
     * items sitting in brain/someday|resources/) into the 7 bucket-dedicated folders. Idempotent:
     * once a file has moved, later restarts find nothing left to do for it. Non-GTD notes
     * (no "bucket" key, e.g. _index.md) are left untouched.
     */
    private void migrateFolderSplit() {
        List<Path> legacyActiveDirs = new ArrayList<>();
        if (Files.isDirectory(legacyInboxDir)) legacyActiveDirs.add(legacyInboxDir);
        legacyActiveDirs.add(somedayDir);
        legacyActiveDirs.add(resourcesDir);

        for (Path dir : legacyActiveDirs) {
            try (Stream<Path> files = Files.list(dir)) {
                files.filter(p -> p.toString().endsWith(".md"))
                     .forEach(p -> migrateFileToBucketDir(dir, p));
            } catch (IOException e) {
                log.warn("migrateFolderSplit: could not list {}: {}", dir, e.getMessage());
            }
        }
    }

    private void migrateFileToBucketDir(Path sourceDir, Path p) {
        try {
            String content = Files.readString(p);
            Map<String, Object> item = MarkdownSerializer.parse(content, p.getFileName().toString());
            if (item.get("bucket") == null) return; // non-GTD note, leave in place

            // done/dismissed routing delegates to expectedDirFor() — the same method
            // migrateBucketMismatch() uses — so the two migrations can't silently diverge on
            // where a completed/discarded item belongs.
            String status = String.valueOf(item.getOrDefault("status", ""));
            Path targetDir;
            if ("done".equals(status)) {
                targetDir = expectedDirFor(item);
                item.putIfAbsent("done_date", bestEffortCompletionDate(item));
            } else if ("dismissed".equals(status)) {
                targetDir = expectedDirFor(item);
                item.putIfAbsent("discarded_date", bestEffortCompletionDate(item));
            } else if (sourceDir.equals(legacyInboxDir)) {
                targetDir = dirFor(String.valueOf(item.get("bucket")));
            } else {
                return; // open someday/resources item already lives in the right place
            }

            Path dest = targetDir.resolve(p.getFileName());
            if (dest.equals(p)) return;
            String body = (String) item.remove("body");
            moveAtomically(p, dest);
            Files.writeString(dest, MarkdownSerializer.serialize(item, body));
            log.info("migrateFolderSplit: moved {} -> {}", p.getFileName(), targetDir);
        } catch (Exception e) {
            log.warn("migrateFolderSplit: skipping {}: {}", p.getFileName(), e.getMessage());
        }
    }

    /** Best available approximation for a retroactive done_date/discarded_date: not exact for items archived before this migration. */
    private static String bestEffortCompletionDate(Map<String, Object> item) {
        Object updated = item.get("updated");
        if (updated != null) return String.valueOf(updated);
        Object created = item.get("created");
        return created != null ? String.valueOf(created) : LocalDate.now().toString();
    }

    /** Rewrites legacy scalar delegado_a ("Juan") as a single-element list (["Juan"]) on disk. */
    private void migrateDelegadoToList() {
        for (Path dir : allDirs) {
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
        for (Path dir : List.of(todayDir)) {
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
     * Duplicate: same filename in more than one of the 7 bucket directories — keep the
     * copy whose bucket matches the directory it's in, breaking ties (or mismatch-both) by most
     * recent "updated"; quarantine the other (see quarantineDuplicate — never deleted outright).
     * Misplaced-no-duplicate: single copy sitting in a directory that doesn't match its own
     * bucket field — relocate it.
     *
     * Only touches files that actually have a "bucket" key — brain/someday (and legacy
     * brain/inbox, while it still has content) also hold non-GTD notes (meta index pages,
     * freeform ideas/someday-maybe entries with their own type/status schema) that were never
     * written by VaultService and must not be moved or treated as duplicates just because the
     * same filename convention happens to collide.
     */
    private void migrateBucketMismatch() {
        Map<String, List<Map.Entry<Path, Map<String, Object>>>> byFilename = new LinkedHashMap<>();
        for (Path dir : allDirs) {
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

    /** Directory a file should live in given its status/bucket — done/dismissed always win over bucket, which is kept only for historical reference. */
    /**
     * Null means "don't know where this belongs" (an unrecognized bucket value — a hand-edited
     * frontmatter typo, or a legacy value from before this bucket scheme existed) — callers must
     * treat that as "leave the file alone", not crash. dirFor() intentionally throws for any
     * caller that's supposed to only ever see the 5 known buckets (write/moveBucket); this
     * self-healing migration is the one caller that has to tolerate arbitrary on-disk data.
     */
    private Path expectedDirFor(Map<String, Object> item) {
        String status = String.valueOf(item.getOrDefault("status", ""));
        if ("done".equals(status)) return doneDir;
        if ("dismissed".equals(status)) return discardDir;
        try {
            return dirFor(String.valueOf(item.get("bucket")));
        } catch (IllegalArgumentException e) {
            log.warn("migrateBucketMismatch: unrecognized bucket '{}', leaving file in place", item.get("bucket"));
            return null;
        }
    }

    private boolean isInOwnDir(Path path, Map<String, Object> item) {
        Path expected = expectedDirFor(item);
        return expected == null || path.getParent().equals(expected);
    }

    private void resolveDuplicate(List<Map.Entry<Path, Map<String, Object>>> entries) {
        Comparator<Map.Entry<Path, Map<String, Object>>> byOwnDirMatch = Comparator.comparing(e ->
            isInOwnDir(e.getKey(), e.getValue()));
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
        Map<String, Object> item = entry.getValue();
        if (isInOwnDir(path, item)) return;
        Path correctDir = expectedDirFor(item);
        Path dest = correctDir.resolve(path.getFileName());
        try {
            moveAtomically(path, dest);
            log.info("migrateBucketMismatch: relocated {} to {} (bucket: {})", path.getFileName(), correctDir, item.get("bucket"));
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

    /** In-place mutation: rewrites the file without moving it (target dir = its current parent). */
    private void mutate(String filename, Actor actor, String op, java.util.function.Consumer<Map<String, Object>> modifier) {
        mutate(filename, actor, op, "none", null, modifier);
    }

    private void mutate(String filename, Actor actor, String op, String confirmation, String chatRef, java.util.function.Consumer<Map<String, Object>> modifier) {
        Path file = resolveFile(filename);
        mutate(filename, file.getParent(), actor, op, confirmation, chatRef, modifier);
    }

    private void mutate(String filename, Path targetDir, Actor actor, String op, java.util.function.Consumer<Map<String, Object>> modifier) {
        mutate(filename, targetDir, actor, op, "none", null, modifier);
    }

    /**
     * Mutates a file's frontmatter/body and, if targetDir differs from its current directory,
     * moves it there atomically first. With one folder per bucket, every bucket transition
     * (today/backlog/waiting/someday/reference) and every done/discard is a move — there is no
     * special "already in the right place" branch to maintain, path equality covers it.
     *
     * The event is appended only after the write succeeds — unlike the old UndoStack, which
     * pushed before writing and could leave a phantom undo entry if the write failed.
     */
    private synchronized void mutate(String filename, Path targetDir, Actor actor, String op, String confirmation, String chatRef, java.util.function.Consumer<Map<String, Object>> modifier) {
        Path file = resolveFile(filename);
        try {
            String previousContent = Files.readString(file);

            Map<String, Object> item = MarkdownSerializer.parse(previousContent, filename);
            String body = (String) item.remove("body");
            modifier.accept(item);

            // _body_override permite que appendToTask/replaceBody cambien el body
            String newBody = (String) item.remove("_body_override");
            if (newBody == null) newBody = body;

            item.put("updated", LocalDate.now().toString());
            String newContent = MarkdownSerializer.serialize(item, newBody);

            Path dest = targetDir.resolve(file.getFileName());
            if (!file.equals(dest)) {
                try {
                    moveAtomically(file, dest);
                } catch (IOException e) {
                    log.error("mutate: atomic move failed for {} ({} -> {}), file untouched at origin: {}",
                        filename, file.getParent(), targetDir, e.getMessage());
                    throw e;
                }
                Files.writeString(dest, newContent);
            } else {
                Files.writeString(file, newContent);
            }
            eventLog.append(Event.mutation(actor, op, filename, String.valueOf(item.get("title")),
                file.toString(), dest.toString(), previousContent, confirmation, chatRef));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private Path resolveFile(String filename) {
        if (!filename.matches("[\\w.\\-]+\\.md")) {
            throw new IllegalArgumentException("Invalid filename: " + filename);
        }
        for (Path dir : allDirs) {
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
            log.warn("readFile: could not read {}, excluding from listing: {}", file, e.getMessage());
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private static List<String> tagsFrom(Map<String, Object> item) {
        Object raw = item.get("tags");
        return (raw instanceof List<?>) ? new ArrayList<>((List<String>) raw) : new ArrayList<>();
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

    /**
     * `reference` still gets auto-tagged: the bucket determines it structurally, and Bases/
     * Dataview views over `brain/resources/` filter on it. `action` is deliberately NOT
     * auto-added here anymore — `type: action` + the bucket folder already say everything the
     * tag used to say, and the tag bar was hiding it from the UI anyway.
     */
    private static void normalizeTypeTags(List<String> tags, String bucket) {
        if ("reference".equals(bucket)) {
            tags.remove("action");
            if (!tags.contains("reference")) tags.add("reference");
        } else {
            tags.remove("reference");
        }
    }

    private void migrateTimestamps() {
        java.util.regex.Pattern TS_IN_YAML = java.util.regex.Pattern.compile("\\d{4}-\\d{2}-\\d{2}T");
        for (Path dir : allDirs) {
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
