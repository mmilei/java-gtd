package com.gtd.service;

import com.gtd.util.MarkdownSerializer;
import com.gtd.util.TextNormalizer;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
    // People pages, one file per person. Not a bucket: never listed as tasks, never written to,
    // only read to resolve [[Name]] mentions in a body to a canonical name. Kept out of allDirs
    // so resolveFile() can't reach it.
    private final Path entitiesDir;
    private final Path legacyInboxDir;
    private final List<Path> allDirs;
    private final Path archiveDuplicatesDir;
    private final EventLog eventLog;
    private final ObjectMapper mapper;
    // Single lock guarding every file-system mutation (mutate/undoEvent) — replaces the old
    // `synchronized` monitors, which pin the carrier thread under virtual threads
    // (spring.threads.virtual.enabled=true). One lock per class preserves the full mutual
    // exclusion the monitors gave: undo and mutate can never interleave a half-written file.
    private final ReentrantLock lock = new ReentrantLock();

    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final Set<String> INACTIVE_STATUSES = Set.of("done", "dismissed");
    private static final List<String> ALL_BUCKETS = List.of("today", "backlog", "waiting", "someday", "reference");
    // Closed vocabulary for the `area` life-area field — the first LLM-controlled value in this
    // codebase with real validation. Configured via `gtd.areas` (English defaults committed,
    // localized override in application-local.properties). An out-of-vocabulary value is silently
    // dropped (see normalizeArea), same spirit as an ambiguous `project` being left null.
    private final List<String> areaVocabulary;
    private final Map<String, String> canonicalAreas;

    public VaultService(
            @Value("${gtd.vault.path}") String vaultPath,
            @Value("${gtd.areas}") List<String> areas,
            EventLog eventLog,
            ObjectMapper mapper,
            @Value("${gtd.vault.migrations-enabled:true}") boolean migrationsEnabled) {
        this.vaultPath      = vaultPath;
        this.mapper         = mapper;
        this.areaVocabulary = areas.stream().map(String::strip).filter(a -> !a.isBlank()).toList();
        this.canonicalAreas = new LinkedHashMap<>();
        for (String area : areaVocabulary) canonicalAreas.put(TextNormalizer.normalize(area), area);
        this.todayDir       = Path.of(vaultPath, "brain/today");
        this.backlogDir     = Path.of(vaultPath, "brain/backlog");
        this.waitingDir     = Path.of(vaultPath, "brain/waiting");
        this.somedayDir     = Path.of(vaultPath, "brain/someday");
        this.resourcesDir   = Path.of(vaultPath, "brain/resources");
        this.doneDir        = Path.of(vaultPath, "brain/done");
        this.discardDir     = Path.of(vaultPath, "brain/discard");
        this.legacyInboxDir = Path.of(vaultPath, "brain/inbox");
        this.entitiesDir    = Path.of(vaultPath, "brain/entities");
        this.allDirs = List.of(todayDir, backlogDir, waitingDir, somedayDir, resourcesDir, doneDir, discardDir);
        this.archiveDuplicatesDir = Path.of(vaultPath, "brain/.archive/duplicates");
        this.eventLog = eventLog;
        try {
            for (Path dir : allDirs) Files.createDirectories(dir);
            Files.createDirectories(archiveDuplicatesDir);
        } catch (IOException e) {
            log.error("Could not create vault directories: {}", e.getMessage());
        }
        if (migrationsEnabled) {
            migrateFolderSplit();
            migrateTodaySince();
            migrateTimestamps();
            migrateBucketMismatch();
        }
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
        // location is a freeform physical place inferred per-message, no vault-wide known-values context.
        putIfPresent(frontmatter, "project", item.get("project"));
        putIfPresent(frontmatter, "location", item.get("location"));
        // area is validated against a closed vocabulary — an out-of-vocab value is dropped silently.
        String area = normalizeArea(item.get("area"));
        if (area != null) frontmatter.put("area", area);
        // related_people is derived from the body, never taken from the caller — see deriveLinks.
        String body = (String) item.getOrDefault("body", "");
        deriveLinks(frontmatter, body);
        List<String> tags = tagsFrom(item);
        normalizeTypeTags(tags, bucket);
        frontmatter.put("tags", tags);
        if ("today".equals(bucket)) frontmatter.put("today_since", LocalDate.now().toString());

        // depends_on names other notes, so every entry is checked against the vault before the
        // file is written — see validateDependsOn.
        if (item.get("depends_on") != null) {
            List<String> deps = validateDependsOn(item.get("depends_on"));
            if (!deps.isEmpty()) frontmatter.put("depends_on", deps);
        }

        // capture_source is the exact user string that produced this task, set by ChatController
        // on the capture path — a hand-created item has none.
        putIfPresent(frontmatter, "capture_source", item.get("capture_source"));
        putIfPresent(frontmatter, "priority", item.get("priority"));
        // estimate_minutes stays out of putIfPresent: it's a number, and strip() would make it a String.
        if (item.get("estimate_minutes") != null) frontmatter.put("estimate_minutes", item.get("estimate_minutes"));
        // Only ever persist confirmed:false — absent and true mean the same thing to listUnconfirmed(),
        // and leaving the key off keeps a normal task's note clean. See ChatController.handleCreate.
        if (Boolean.FALSE.equals(item.get("confirmed"))) frontmatter.put("confirmed", false);

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

    /**
     * Active tasks the classifier filed with low confidence (confirmed:false, set by
     * ChatController.handleCreate when the fallback prompt ran) — the review queue the user works
     * through with POST /api/items/{file}/confirm. Only confirmed==Boolean.FALSE qualifies: an
     * absent, null, or true value all mean "confirmed" (see the note-format doc), so a normal task
     * never shows up here. Scans the same active buckets as list()/listAll(), so done/dismissed
     * items are already excluded.
     */
    public List<Map<String, Object>> listUnconfirmed() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (String bucket : ALL_BUCKETS) {
            for (Map<String, Object> item : list(bucket)) {
                if (Boolean.FALSE.equals(item.get("confirmed"))) result.add(item);
            }
        }
        return result;
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

    /**
     * Every name that reaches a person page in brain/entities/: the filename, which is the
     * canonical name ("Mary-Jane.md" → "Mary-Jane"), plus each entry of its `aliases` frontmatter.
     * Feeds both the [[Name]] resolution in deriveLinks() and the classifier's context, so a
     * mention lands on the existing page instead of minting a near-duplicate — the failure mode
     * of the freeform column this replaces, which accumulated several spellings and nicknames of
     * the same person as if they were different people.
     *
     * Aliases are listed flat, as names in their own right, so the classifier can use whichever
     * one the user actually said: a nickname writes [[Nickname]], which reaches that person's
     * page and is stored under their canonical name. `aliases` is Obsidian's own mechanism and it resolves those links the
     * same way, so the app and the vault agree on who a name points at.
     *
     * Index pages (a leading underscore, by Obsidian convention) are not people. A missing
     * directory means a vault with no people pages yet, not an error.
     */
    public List<String> knownPeople() {
        return new ArrayList<>(peopleByName().keySet());
    }

    // Anything that would make the name something other than a plain file name in brain/entities/:
    // path separators, the Windows-reserved set, control characters. A leading dot is out too, which
    // also settles "." and "..".
    private static final Pattern UNSAFE_PERSON_NAME = Pattern.compile("[\\\\/:*?\"<>|\\p{Cntrl}]");
    private static final int MAX_PERSON_NAME = 100;

    /**
     * Creates a person page in brain/entities/ with the minimal frontmatter knownPeople() and
     * Obsidian need, and returns the canonical name it was filed under.
     *
     * The one write this service makes outside the task buckets, and deliberately the narrowest
     * one that works: a fixed directory and a fixed shape, not a "write a file anywhere in the
     * vault" primitive. wiki/ in particular is not this app's to write — that zone has ingest
     * conventions (addresses, cross-refs, index) nothing here knows about.
     *
     * An existing name is an error rather than an overwrite, matched the way deriveLinks() matches
     * so a different casing or a registered alias counts as the same person instead of minting the
     * near-duplicate page the whole entities/ design exists to avoid.
     */
    public String createPerson(String rawName) {
        String name = rawName == null ? "" : rawName.strip();
        if (name.isEmpty()) throw new IllegalArgumentException("name is required");
        if (name.length() > MAX_PERSON_NAME || name.startsWith(".") || UNSAFE_PERSON_NAME.matcher(name).find()) {
            throw new IllegalArgumentException("Invalid person name: " + name);
        }

        lock.lock();
        try {
            String normalized = TextNormalizer.normalize(name);
            for (Map.Entry<String, String> known : peopleByName().entrySet()) {
                if (TextNormalizer.normalize(known.getKey()).equals(normalized)) {
                    throw new IllegalArgumentException("Person already exists: " + known.getValue());
                }
            }

            Map<String, Object> frontmatter = new LinkedHashMap<>();
            frontmatter.put("type", "entity");
            frontmatter.put("entity_type", "person");
            frontmatter.put("title", name);
            frontmatter.put("created", LocalDate.now().toString());
            frontmatter.put("tags", new ArrayList<String>());
            try {
                Files.createDirectories(entitiesDir);
                // CREATE_NEW rather than a plain write: the existence check above is the useful
                // error message, this is the one that can't be raced.
                Files.writeString(entitiesDir.resolve(name + ".md"),
                    MarkdownSerializer.serialize(frontmatter, ""), java.nio.file.StandardOpenOption.CREATE_NEW);
            } catch (java.nio.file.FileAlreadyExistsException e) {
                throw new IllegalArgumentException("Person already exists: " + name);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            return name;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Every name (canonical or alias) → the canonical name of the page it reaches. Keys keep their
     * written form; matching against them goes through TextNormalizer at the call site.
     */
    private Map<String, String> peopleByName() {
        Map<String, String> byName = new TreeMap<>();
        try (Stream<Path> files = Files.list(entitiesDir)) {
            files.filter(p -> {
                     String name = p.getFileName().toString();
                     return name.endsWith(".md") && !name.startsWith("_");
                 })
                 .forEach(p -> {
                     String filename = p.getFileName().toString();
                     String canonical = filename.substring(0, filename.length() - 3);
                     byName.put(canonical, canonical);
                     Map<String, Object> page = readFile(p);
                     if (page == null) return;
                     Object aliases = page.get("aliases");
                     if (!(aliases instanceof List<?> list)) return;
                     for (Object alias : list) {
                         if (alias == null) continue;
                         String a = String.valueOf(alias).strip();
                         // An alias never shadows a real page: two people can't share a name.
                         if (!a.isEmpty()) byName.putIfAbsent(a, canonical);
                     }
                 });
        } catch (IOException e) { /* no entities directory yet, no people to resolve against */ }
        return byName;
    }

    private static void addProject(Set<String> projects, Map<String, Object> item) {
        Object raw = item.get("project");
        if (raw == null) return;
        String project = String.valueOf(raw).strip();
        if (!project.isEmpty()) projects.add(project);
    }

    /**
     * Sorted, deduplicated list of every tag currently in use across the vault, including
     * done/discarded tasks — same continuity reasoning as knownProjects(): a tag shouldn't drop
     * out of the classifier's context just because every task that used it is finished. Fed to
     * the classifier so it reuses an existing tag ("compras") instead of inventing a
     * near-duplicate ("shopping", "super"). Returns an empty list when the vault has no tagged
     * items yet.
     */
    public List<String> knownTags() {
        Set<String> tags = new TreeSet<>();
        listAll().forEach((bucket, items) -> items.forEach(item -> addTags(tags, item)));
        for (Path dir : List.of(doneDir, discardDir)) {
            try (Stream<Path> files = Files.list(dir)) {
                files.filter(p -> p.toString().endsWith(".md"))
                     .map(this::readFile)
                     .filter(Objects::nonNull)
                     .forEach(item -> addTags(tags, item));
            } catch (IOException e) { /* empty directory, skip */ }
        }
        return new ArrayList<>(tags);
    }

    private static void addTags(Set<String> tags, Map<String, Object> item) {
        if (!(item.get("tags") instanceof List<?> itemTags)) return;
        for (Object t : itemTags) {
            String tag = String.valueOf(t).strip();
            if (!tag.isEmpty()) tags.add(tag);
        }
    }

    /**
     * Closes the item and reports which of its `depends_on` entries were still open at that
     * moment, as {@code {file, title}} pairs. Advisory only: an open dependency never blocks the
     * close — the app warns, the user decides — so the answer is a notice the caller may relay,
     * not an error. Empty list when the item has no dependencies or all of them are finished.
     */
    public List<Map<String, Object>> markDone(String filename, Actor actor) {
        List<Map<String, Object>> stillOpen = new ArrayList<>();
        mutate(filename, doneDir, actor, "done", item -> {
            stillOpen.addAll(openDependencies(item));
            item.put("status", "done");
            item.putIfAbsent("done_date", LocalDate.now().toString());
        });
        return stillOpen;
    }

    /**
     * The item's dependencies that are neither done nor dismissed, as {@code {file, title}}.
     * A dependency whose file is gone is skipped rather than reported: it can't be open, and
     * chasing dead references is deliberately out of scope (nothing revalidates depends_on after
     * the write that accepted it).
     */
    private List<Map<String, Object>> openDependencies(Map<String, Object> item) {
        if (!(item.get("depends_on") instanceof List<?> deps)) return List.of();
        List<Map<String, Object>> open = new ArrayList<>();
        for (Object raw : deps) {
            Path file = findFile(String.valueOf(raw).strip());
            if (file == null) continue;
            Map<String, Object> dep = readFile(file);
            if (dep == null || INACTIVE_STATUSES.contains(String.valueOf(dep.getOrDefault("status", "")))) continue;
            open.add(Map.of("file", dep.get("file"), "title", String.valueOf(dep.getOrDefault("title", dep.get("file")))));
        }
        return open;
    }

    /**
     * Every entry must name a note the vault actually holds — a dependency on a file that isn't
     * there is a typo, and the whole patch/create is rejected so it never reaches disk. Only the
     * write path checks: a file deleted afterwards leaves a dead reference behind on purpose
     * (see openDependencies), and cycles are not detected at all.
     */
    private List<String> validateDependsOn(Object raw) {
        if (!(raw instanceof List<?> list)) {
            throw new IllegalArgumentException("depends_on must be a list of filenames");
        }
        List<String> deps = new ArrayList<>();
        for (Object entry : list) {
            String dep = String.valueOf(entry).strip();
            if (findFile(dep) == null) {
                throw new IllegalArgumentException("depends_on: no such file in the vault: " + dep);
            }
            if (!deps.contains(dep)) deps.add(dep);
        }
        return deps;
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

    public void replaceBody(String filename, String newBody, Actor actor) {
        replaceBody(filename, newBody, actor, "none", null);
    }

    /** Used by POST /api/chat/confirm to record that this edit/update was an LLM proposal the user approved, linked back to the chat message that proposed it. */
    public void replaceBody(String filename, String newBody, Actor actor, String confirmation, String chatRef) {
        mutate(filename, actor, "edit", confirmation, chatRef, item -> item.put("_body_override", newBody));
    }

    public void patchMeta(String filename, Map<String, Object> meta, Actor actor) {
        // related/related_people are absent on purpose: both are derived from the body's
        // wikilinks by deriveLinks(), so accepting them here would let a caller set a value the
        // next save silently overwrites.
        Set<String> allowed = Set.of("title", "tags", "due", "today_since", "markdownified", "area", "estimate_minutes", "confirmed", "project", "location", "priority", "depends_on");
        mutate(filename, actor, "patch", item -> meta.forEach((k, v) -> {
            if (!allowed.contains(k) || v == null) return;
            if ("area".equals(k)) {
                String area = normalizeArea(v);
                if (area != null) item.put("area", area);
                return;
            }
            if ("depends_on".equals(k)) {
                // Throws before anything is written when an entry names a file the vault doesn't
                // have. An empty list drops the key instead of leaving `depends_on: []` behind.
                List<String> deps = validateDependsOn(v);
                if (deps.isEmpty()) item.remove("depends_on"); else item.put("depends_on", deps);
                return;
            }
            item.put(k, v);
        }));
    }

    /**
     * Inverts a mutation event: no previousContent means it was a create (undo = delete);
     * otherwise moves the file back from path_after to path_before (if they differ — covers
     * move/done/dismiss) and restores previous_content. This single path-based rule replaces the
     * old op-specific undo logic and, as a side effect, fixes the historical bug where undoing a
     * move restored content at the old path but left the moved-to copy behind as a duplicate.
     */
    public void undoEvent(Event e) {
        lock.lock();
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
        } finally {
            lock.unlock();
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

        forEachMarkdownFile(legacyActiveDirs, "migrateFolderSplit", this::migrateFileToBucketDir);
    }

    private void migrateFileToBucketDir(Path sourceDir, Path p) throws Exception {
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
    }

    /** Best available approximation for a retroactive done_date/discarded_date: not exact for items archived before this migration. */
    private static String bestEffortCompletionDate(Map<String, Object> item) {
        Object updated = item.get("updated");
        if (updated != null) return String.valueOf(updated);
        Object created = item.get("created");
        return created != null ? String.valueOf(created) : LocalDate.now().toString();
    }

    private void migrateTodaySince() {
        forEachMarkdownFile(List.of(todayDir), "migrateTodaySince", (dir, p) -> {
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
        });
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
    private void mutate(String filename, Path targetDir, Actor actor, String op, String confirmation, String chatRef, java.util.function.Consumer<Map<String, Object>> modifier) {
        lock.lock();
        try {
            Path file = resolveFile(filename);
            String previousContent = Files.readString(file);

            Map<String, Object> item = MarkdownSerializer.parse(previousContent, filename);
            String body = (String) item.remove("body");
            modifier.accept(item);

            // _body_override lets replaceBody swap the body
            String newBody = (String) item.remove("_body_override");
            if (newBody == null) newBody = body;

            deriveLinks(item, newBody);
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
        } finally {
            lock.unlock();
        }
    }

    /**
     * Obsidian wikilink. Captures the target only, dropping an alias ("[[target|shown text]]") or
     * a heading anchor ("[[target#section]]") — both are display concerns, the link still points
     * at `target`.
     */
    private static final Pattern WIKILINK = Pattern.compile("\\[\\[([^\\[\\]|#]+)(?:[#|][^\\[\\]]*)?]]");

    /** What a resolved [[wikilink]] points at, which decides who can open it. */
    public enum LinkKind {
        /** A note in one of the task buckets — the app opens it in its own modal. */
        TASK,
        /** A page in brain/entities/ — the app filters its task list by that person. */
        PERSON,
        /** Any other vault page: project, concept, session note. Only Obsidian renders these. */
        NOTE
    }

    /**
     * A vault page a wikilink resolved to: its canonical name, what kind it is, where it lives
     * (relative to the vault), and the `obsidian://` URI that opens it in Obsidian.
     *
     * The URI is built here, not in the frontend, so no client ever has to know the vault's
     * location or name — that lives in `gtd.vault.path` and nowhere else.
     */
    public record ResolvedLink(String name, LinkKind kind, String path, String obsidianUri) {}

    /**
     * `obsidian://open?path=<absolute path>`. The `path` form is used over `vault=<name>&file=`
     * precisely because it needs no vault name: one configured value answers for everything.
     *
     * URLEncoder is form encoding, where a space is "+" — which Obsidian does not decode back to a
     * space — so spaces are rewritten to %20 after the fact.
     */
    private String obsidianUri(Path base, Path file) {
        String encoded = URLEncoder.encode(base.resolve(file).toString(), StandardCharsets.UTF_8)
            .replace("+", "%20");
        return "obsidian://open?path=" + encoded;
    }

    /**
     * Rewrites `related_people` from the [[Name]] mentions in the body, which becomes the single
     * place a person is ever named. It stays in the frontmatter because the waiting bucket, the
     * frontend and Dataview all read it without parsing markdown, but nothing except this method
     * writes it: patchMeta rejects it, and the classifier names the person in the body like a
     * human would. That closes the hole that let the column fill up with several spellings and
     * nicknames of the same person, plus values that were not people at all.
     *
     * `related` is deliberately NOT touched. It looks like the same kind of field but has a
     * different owner: it's a vault-wide convention (415 notes, documented in the save,
     * autoresearch, wiki-query and obsidian-markdown skills) holding *curated* cross-references,
     * which are not the same set as the pages a body happens to mention — a wiki concept links to
     * a related concept it never names in prose. Deriving it would make this service the author
     * of a field it doesn't own and silently drop those curations on the next save. Body mentions
     * are served live instead, as `links` on GET /api/items/{filename}.
     */
    private void deriveLinks(Map<String, Object> frontmatter, String body) {
        frontmatter.remove("related_people");
        if (body == null || body.isBlank()) return;

        // Matched against the people pages alone, not the vault index: this runs on every write,
        // with the lock held, and the only thing it keeps is the people. Resolving what the other
        // links point at would be work thrown away.
        Map<String, String> people = new LinkedHashMap<>();
        peopleByName().forEach((name, canonical) -> people.put(TextNormalizer.normalize(name), canonical));

        Set<String> found = new LinkedHashSet<>();
        Matcher matcher = WIKILINK.matcher(body);
        while (matcher.find()) {
            String canonical = people.get(TextNormalizer.normalize(wikilinkKey(matcher.group(1))));
            if (canonical != null) found.add(canonical);
        }
        if (!found.isEmpty()) frontmatter.put("related_people", new ArrayList<>(found));
    }

    /**
     * Every [[wikilink]] in a body that names a real vault page, in order, deduplicated. Used both
     * to derive the frontmatter fields and to answer the frontend, which needs the path to hand a
     * NOTE off to Obsidian via its obsidian:// URI.
     */
    /**
     * The lookup key for a raw `[[...]]` target: strips a trailing ".md" and any folder prefix, so
     * both a bare "[[Some Note]]" and a folder-qualified "[[wiki/references/some-note]]" resolve
     * the same way the vault's own index and Obsidian itself do — by basename, not full path.
     */
    private static String wikilinkKey(String rawTarget) {
        String target = rawTarget.strip();
        if (target.endsWith(".md")) target = target.substring(0, target.length() - 3);
        int slash = target.lastIndexOf('/');
        return slash >= 0 ? target.substring(slash + 1) : target;
    }

    public List<ResolvedLink> resolveLinks(String body) {
        if (body == null || body.isBlank()) return List.of();
        Map<String, ResolvedLink> index = vaultIndex();
        Map<String, ResolvedLink> found = new LinkedHashMap<>();
        Matcher matcher = WIKILINK.matcher(body);
        while (matcher.find()) {
            String key = wikilinkKey(matcher.group(1));
            if (key.isEmpty()) continue;
            ResolvedLink link = index.get(TextNormalizer.normalize(key));
            if (link != null) found.putIfAbsent(link.name(), withSettledKind(link));
        }
        return new ArrayList<>(found.values());
    }

    /**
     * Upgrades a NOTE to a TASK when the page really is one. The bucket folder alone doesn't prove
     * it: brain/resources/ is the `reference` bucket, but 89 of its 96 pages are session notes
     * written by /save, which have no `bucket` field and would open in the task modal as if they
     * were cards. Having the field is what makes a page a task, so that is what gets checked.
     *
     * One read per link in a body, typically none or two — the cost the index deliberately avoids
     * paying 661 times per save.
     */
    private ResolvedLink withSettledKind(ResolvedLink link) {
        if (link.kind() != LinkKind.NOTE) return link;
        Path file = Path.of(vaultPath).toAbsolutePath().normalize().resolve(link.path());
        if (!allDirs.contains(file.getParent())) return link;
        Map<String, Object> page = readFile(file);
        return (page != null && page.get("bucket") != null)
            ? new ResolvedLink(link.name(), LinkKind.TASK, link.path(), link.obsidianUri())
            : link;
    }

    /**
     * The two zones that hold linkable pages: wiki/ for external knowledge, brain/ for personal
     * notes. Scoping the walk to them is not an optimization, it's what makes the index correct —
     * the vault root also contains the workspace/ checkouts, whose READMEs and node_modules would
     * otherwise register as vault pages and answer to a [[README]] mention. It cuts the walk from
     * ~71k filesystem entries to ~660 at the same time.
     */
    private static final List<String> LINKABLE_ZONES = List.of("wiki", "brain");

    /** Superseded duplicates live on under brain/ but are not link targets. */
    private static final String ARCHIVE_DIR = ".archive";

    /**
     * Name → page index over the linkable zones, keyed by normalized name so a mention matches
     * regardless of case or accents ("[[jane-doe]]" finds "Jane-Doe.md").
     *
     * ponytail: rebuilt per call, ~660 paths with no file reads, and only on the read path —
     * writes match against the people pages alone (see deriveLinks). Cache it against the
     * directory mtimes if a read ever feels slow.
     */
    /**
     * Every page a [[wikilink]] could name, for the editor's autocomplete: tasks in any bucket
     * (done and discarded included — linking to a finished task is how a card says where it came
     * from), people, and the wiki/brain notes. Sorted by name.
     *
     * Aliases are left out: they resolve, but offering both a person's name and their nickname as
     * two entries would read as two people. The canonical name is what gets inserted anyway.
     */
    public List<ResolvedLink> vaultPages() {
        Path base = Path.of(vaultPath).toAbsolutePath().normalize();
        Map<String, ResolvedLink> index = new LinkedHashMap<>();
        for (String zone : LINKABLE_ZONES) indexZone(base, base.resolve(zone), index);
        return index.values().stream()
            .sorted(Comparator.comparing(ResolvedLink::name, String.CASE_INSENSITIVE_ORDER))
            .toList();
    }

    private Map<String, ResolvedLink> vaultIndex() {
        Path base = Path.of(vaultPath).toAbsolutePath().normalize();
        Map<String, ResolvedLink> index = new LinkedHashMap<>();
        for (String zone : LINKABLE_ZONES) indexZone(base, base.resolve(zone), index);
        // Person aliases last, and only into free slots: a nickname reaches its person's page,
        // but a real page owning that name already claimed the key and keeps it.
        peopleByName().forEach((name, canonical) -> {
            ResolvedLink target = index.get(TextNormalizer.normalize(canonical));
            if (target != null) index.putIfAbsent(TextNormalizer.normalize(name), target);
        });
        return index;
    }

    private void indexZone(Path base, Path zone, Map<String, ResolvedLink> index) {
        try (Stream<Path> files = Files.walk(zone)) {
            files.filter(p -> p.toString().endsWith(".md"))
                 .filter(p -> !base.relativize(p).startsWith(Path.of("brain", ARCHIVE_DIR))
                           && !p.getParent().getFileName().toString().equals(ARCHIVE_DIR))
                 .forEach(p -> {
                     String filename = p.getFileName().toString();
                     String name = filename.substring(0, filename.length() - 3);
                     if (name.startsWith("_")) return;   // _index and friends are not link targets
                     // Kind by directory, which settles it for every folder but one: entities are
                     // people, and the six single-purpose bucket folders hold nothing but tasks.
                     // brain/resources/ is the exception — it is the `reference` bucket, yet 89 of
                     // its 96 pages are session notes — so it starts as NOTE and only a link that
                     // actually gets resolved pays a read to check (see withSettledKind).
                     Path parent = p.getParent();
                     LinkKind kind = parent.equals(entitiesDir) ? LinkKind.PERSON
                                   : allDirs.contains(parent) && !parent.equals(resourcesDir) ? LinkKind.TASK
                                   : LinkKind.NOTE;
                     // First writer wins: two pages sharing a name is an Obsidian ambiguity we
                     // don't try to out-guess, and stable order beats a coin flip per read.
                     Path relative = base.relativize(p);
                     index.putIfAbsent(TextNormalizer.normalize(name),
                         new ResolvedLink(name, kind, relative.toString().replace('\\', '/'),
                             obsidianUri(base, relative)));
                 });
        } catch (IOException e) {
            log.warn("indexZone: could not walk {}, its links will not resolve this pass: {}", zone, e.getMessage());
        }
    }

    private Path resolveFile(String filename) {
        if (!filename.matches("[\\w.\\-]+\\.md")) {
            throw new IllegalArgumentException("Invalid filename: " + filename);
        }
        Path file = findFile(filename);
        if (file == null) throw new IllegalArgumentException("File not found:" + filename);
        return file;
    }

    /** Same lookup as resolveFile without the exceptions — for callers that treat "not there" as an answer. */
    private Path findFile(String filename) {
        if (!filename.matches("[\\w.\\-]+\\.md")) return null;
        for (Path dir : allDirs) {
            Path p = dir.resolve(filename);
            if (Files.exists(p)) return p;
        }
        return null;
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


    /** The configured `gtd.areas` vocabulary, in config order — served to the frontend and the classifier prompt. */
    public List<String> validAreas() {
        return areaVocabulary;
    }

    /**
     * Validates the `area` life-area field against the configured vocabulary. Matching is
     * accent/case-insensitive (via TextNormalizer) and the persisted value is always the canonical
     * spelling from config, so an LLM emitting "Ejercició" still lands as "ejercicio". Any
     * non-member input (including null) yields null so the caller can simply omit the field.
     * Silent/non-throwing on purpose — a bad area is dropped, never an error, matching how an
     * ambiguous project is left null. Only applies to new writes; existing on-disk `area` values
     * outside the vocabulary are never migrated or touched.
     */
    private String normalizeArea(Object raw) {
        if (raw == null) return null;
        return canonicalAreas.get(TextNormalizer.normalize(String.valueOf(raw)));
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
        forEachMarkdownFile(allDirs, "migrateTimestamps", (dir, p) -> {
            String content = Files.readString(p);
            int fmEnd = content.indexOf("\n---", content.indexOf('\n') + 1);
            String frontmatter = fmEnd > 0 ? content.substring(0, fmEnd) : "";
            if (!TS_IN_YAML.matcher(frontmatter).find()) return;

            Map<String, Object> item = MarkdownSerializer.parse(content, p.getFileName().toString());
            String body = (String) item.remove("body");
            Files.writeString(p, MarkdownSerializer.serialize(item, body));
            log.info("migrateTimestamps: fixed {}", p.getFileName());
        });
    }

    @FunctionalInterface
    private interface FileMigrationStep {
        void apply(Path dir, Path file) throws Exception;
    }

    /** Shared scan-every-file-in-these-dirs loop for the migrations above: per-file failures are logged and skipped, never thrown. */
    private void forEachMarkdownFile(List<Path> dirs, String migrationName, FileMigrationStep step) {
        for (Path dir : dirs) {
            try (Stream<Path> files = Files.list(dir)) {
                files.filter(p -> p.toString().endsWith(".md")).forEach(p -> {
                    try {
                        step.apply(dir, p);
                    } catch (Exception e) {
                        log.warn("{}: skipping {}: {}", migrationName, p.getFileName(), e.getMessage());
                    }
                });
            } catch (IOException e) {
                log.warn("{}: could not list {}: {}", migrationName, dir, e.getMessage());
            }
        }
    }

    /** Writes key only when raw has real content — a blank field is worse than an absent one. */
    private static void putIfPresent(Map<String, Object> frontmatter, String key, Object raw) {
        if (raw != null && !String.valueOf(raw).isBlank()) frontmatter.put(key, String.valueOf(raw).strip());
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
