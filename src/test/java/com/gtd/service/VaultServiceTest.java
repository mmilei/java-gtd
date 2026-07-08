package com.gtd.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class VaultServiceTest {

    private static VaultService newVault(Path tempDir) {
        return newVault(tempDir, new EventLog(tempDir.toString()));
    }

    // Mirrors the localized vocabulary a real deployment may configure via gtd.areas — the
    // committed default is English, but validation must be vocabulary-agnostic.
    private static final List<String> TEST_AREAS =
        List.of("personal", "amistad", "ejercicio", "trabajo", "salud", "finanzas", "hogar", "aprendizaje");

    private static VaultService newVault(Path tempDir, EventLog eventLog) {
        return new VaultService(tempDir.toString(), TEST_AREAS, eventLog, new ObjectMapper(), true, true, true, true, true);
    }

    @Test
    void shouldCreateVaultDirectoriesOnStartup(@TempDir Path tempDir) {
        newVault(tempDir);

        assertThat(tempDir.resolve("brain/today")).isDirectory();
        assertThat(tempDir.resolve("brain/backlog")).isDirectory();
        assertThat(tempDir.resolve("brain/waiting")).isDirectory();
        assertThat(tempDir.resolve("brain/someday")).isDirectory();
        assertThat(tempDir.resolve("brain/resources")).isDirectory();
        assertThat(tempDir.resolve("brain/done")).isDirectory();
        assertThat(tempDir.resolve("brain/discard")).isDirectory();
    }

    @Test
    void shouldRejectPathTraversalInFilename(@TempDir Path tempDir) {
        VaultService vault = newVault(tempDir);
        assertThatThrownBy(() -> vault.read("../../../etc/passwd"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid filename");
    }

    @Test
    void shouldRejectFilenameWithoutMdExtension(@TempDir Path tempDir) {
        VaultService vault = newVault(tempDir);
        assertThatThrownBy(() -> vault.read("noextension"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid filename");
    }

    @Test
    void shouldNotFailIfDirectoriesAlreadyExist(@TempDir Path tempDir) throws Exception {
        Files.createDirectories(tempDir.resolve("brain/today"));
        Files.createDirectories(tempDir.resolve("brain/backlog"));
        Files.createDirectories(tempDir.resolve("brain/waiting"));
        Files.createDirectories(tempDir.resolve("brain/someday"));
        Files.createDirectories(tempDir.resolve("brain/resources"));
        Files.createDirectories(tempDir.resolve("brain/done"));
        Files.createDirectories(tempDir.resolve("brain/discard"));

        org.junit.jupiter.api.Assertions.assertDoesNotThrow(() -> newVault(tempDir));
    }

    @Test
    void shouldNotAutoAddActionOrGtdTags(@TempDir Path tempDir) throws Exception {
        VaultService vault = newVault(tempDir);
        Map<String, Object> op = new java.util.LinkedHashMap<>();
        op.put("bucket", "today");
        op.put("title", "Do something");
        op.put("tags", new java.util.ArrayList<>(List.of("work")));

        String filename = vault.write(op, Actor.USER);
        Map<String, Object> saved = vault.read(filename);

        // type: action + the bucket folder already say this is an action item — the tag no
        // longer gets force-added on top of that (was pure noise, hidden from the UI anyway).
        @SuppressWarnings("unchecked")
        List<String> tags = (List<String>) saved.get("tags");
        assertThat(tags).containsExactly("work");
        assertThat(tempDir.resolve("brain/today").resolve(filename)).exists();
    }

    @Test
    void shouldAddReferenceTagAndRemoveActionForReferenceItem(@TempDir Path tempDir) throws Exception {
        VaultService vault = newVault(tempDir);
        Map<String, Object> op = new java.util.LinkedHashMap<>();
        op.put("bucket", "reference");
        op.put("title", "Useful documentation");
        op.put("tags", new java.util.ArrayList<>(List.of("gtd", "action", "work")));

        String filename = vault.write(op, Actor.USER);
        Map<String, Object> saved = vault.read(filename);

        @SuppressWarnings("unchecked")
        List<String> tags = (List<String>) saved.get("tags");
        assertThat(tags).contains("gtd", "reference", "work");
        assertThat(tags).doesNotContain("action");
    }

    @Test
    void shouldHandleNullTagsGracefully(@TempDir Path tempDir) throws Exception {
        VaultService vault = newVault(tempDir);
        Map<String, Object> op = new java.util.LinkedHashMap<>();
        op.put("bucket", "backlog");
        op.put("title", "Task with no tags");
        op.put("tags", null);

        String filename = vault.write(op, Actor.USER);
        Map<String, Object> saved = vault.read(filename);

        // null tags no longer implies forced gtd/action — the item is created fine with an
        // empty tags list.
        @SuppressWarnings("unchecked")
        List<String> tags = (List<String>) saved.get("tags");
        assertThat(tags).isEmpty();
    }

    @Test
    void shouldRejectUnknownBucket(@TempDir Path tempDir) {
        VaultService vault = newVault(tempDir);
        Map<String, Object> op = new java.util.LinkedHashMap<>();
        op.put("bucket", "not-a-real-bucket");
        op.put("title", "Bad bucket");

        assertThatThrownBy(() -> vault.write(op, Actor.USER)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldMoveBucketAtomicallyAcrossDirectories(@TempDir Path tempDir) throws Exception {
        VaultService vault = newVault(tempDir);
        Map<String, Object> op = new java.util.LinkedHashMap<>();
        op.put("bucket", "someday");
        op.put("title", "Someday task");
        op.put("tags", new java.util.ArrayList<>(List.of("work")));
        String filename = vault.write(op, Actor.USER);

        assertThat(tempDir.resolve("brain/someday").resolve(filename)).exists();

        vault.moveBucket(filename, "backlog", null, Actor.USER);

        assertThat(tempDir.resolve("brain/backlog").resolve(filename)).exists();
        assertThat(tempDir.resolve("brain/someday").resolve(filename)).doesNotExist();
        assertThat(vault.read(filename).get("bucket")).isEqualTo("backlog");
    }

    @Test
    void shouldMoveBetweenTodayBacklogAndWaitingEvenThoughTheyUsedToShareADirectory(@TempDir Path tempDir) throws Exception {
        VaultService vault = newVault(tempDir);
        Map<String, Object> op = new java.util.LinkedHashMap<>();
        op.put("bucket", "today");
        op.put("title", "Triage me");
        String filename = vault.write(op, Actor.USER);

        vault.moveBucket(filename, "waiting", null, Actor.USER);
        assertThat(tempDir.resolve("brain/waiting").resolve(filename)).exists();
        assertThat(tempDir.resolve("brain/today").resolve(filename)).doesNotExist();

        vault.moveBucket(filename, "backlog", null, Actor.USER);
        assertThat(tempDir.resolve("brain/backlog").resolve(filename)).exists();
        assertThat(tempDir.resolve("brain/waiting").resolve(filename)).doesNotExist();
    }

    @Test
    void shouldMoveDoneItemToDoneDirAndSetDoneDateOnce(@TempDir Path tempDir) throws Exception {
        VaultService vault = newVault(tempDir);
        Map<String, Object> op = new java.util.LinkedHashMap<>();
        op.put("bucket", "today");
        op.put("title", "Finish this");
        String filename = vault.write(op, Actor.USER);

        vault.markDone(filename, Actor.USER);

        assertThat(tempDir.resolve("brain/done").resolve(filename)).exists();
        assertThat(tempDir.resolve("brain/today").resolve(filename)).doesNotExist();
        Map<String, Object> saved = vault.read(filename);
        assertThat(saved.get("status")).isEqualTo("done");
        Object doneDate = saved.get("done_date");
        assertThat(doneDate).isNotNull();

        // editing an already-done item must not overwrite the original done_date
        Files.writeString(tempDir.resolve("brain/done").resolve(filename),
            Files.readString(tempDir.resolve("brain/done").resolve(filename)));
        vault.appendToTask(filename, "extra note", Actor.USER);
        assertThat(vault.read(filename).get("done_date")).isEqualTo(doneDate);
    }

    @Test
    void shouldMoveDismissedItemToDiscardDirAndSetDiscardedDateOnce(@TempDir Path tempDir) throws Exception {
        VaultService vault = newVault(tempDir);
        Map<String, Object> op = new java.util.LinkedHashMap<>();
        op.put("bucket", "backlog");
        op.put("title", "Drop this");
        String filename = vault.write(op, Actor.USER);

        vault.dismissItem(filename, Actor.USER);

        assertThat(tempDir.resolve("brain/discard").resolve(filename)).exists();
        assertThat(tempDir.resolve("brain/backlog").resolve(filename)).doesNotExist();
        Map<String, Object> saved = vault.read(filename);
        assertThat(saved.get("status")).isEqualTo("dismissed");
        assertThat(saved.get("discarded_date")).isNotNull();
    }

    @Test
    void shouldMigrateExistingLegacyInboxFilesIntoBucketDirsOnStartup(@TempDir Path tempDir) throws Exception {
        Path inbox = tempDir.resolve("brain/inbox");
        Files.createDirectories(inbox);

        Files.writeString(inbox.resolve("20260101-000000-legacy-today.md"), """
            ---
            type: action
            title: Legacy today task
            bucket: today
            status: open
            created: 2026-01-01
            tags: [gtd, action]
            ---

            """);
        Files.writeString(inbox.resolve("20260101-000001-legacy-done.md"), """
            ---
            type: action
            title: Legacy done task
            bucket: backlog
            status: done
            created: 2026-01-01
            updated: 2026-01-02
            tags: [gtd, action]
            ---

            """);
        // non-GTD note without a bucket field must stay put
        Files.writeString(inbox.resolve("_index.md"), """
            ---
            type: meta
            title: "Brain / Inbox"
            status: active
            ---

            """);

        newVault(tempDir);

        assertThat(tempDir.resolve("brain/today").resolve("20260101-000000-legacy-today.md")).exists();
        assertThat(tempDir.resolve("brain/done").resolve("20260101-000001-legacy-done.md")).exists();
        assertThat(inbox.resolve("20260101-000000-legacy-today.md")).doesNotExist();
        assertThat(inbox.resolve("20260101-000001-legacy-done.md")).doesNotExist();
        assertThat(inbox.resolve("_index.md")).exists();

        String movedDone = Files.readString(tempDir.resolve("brain/done").resolve("20260101-000001-legacy-done.md"));
        assertThat(movedDone).contains("done_date: '2026-01-02'").as("retroactive done_date falls back to updated");
    }

    @Test
    void shouldSelfHealDuplicateFilenameAcrossBucketDirs(@TempDir Path tempDir) throws Exception {
        Path backlog = tempDir.resolve("brain/backlog");
        Path someday = tempDir.resolve("brain/someday");
        Files.createDirectories(backlog);
        Files.createDirectories(someday);

        String filename = "20260630-090727-duplicated-task.md";
        Files.writeString(backlog.resolve(filename), """
            ---
            type: action
            title: Duplicated task
            bucket: backlog
            status: open
            created: 2026-06-30
            updated: 2026-07-01
            tags: [gtd, action]
            ---

            """);
        Files.writeString(someday.resolve(filename), """
            ---
            type: action
            title: Duplicated task
            bucket: someday
            status: open
            created: 2026-06-30
            updated: 2026-06-30
            tags: [gtd, action]
            ---

            """);

        VaultService vault = newVault(tempDir);

        assertThat(backlog.resolve(filename)).exists();
        assertThat(someday.resolve(filename)).doesNotExist();
        assertThat(vault.read(filename).get("bucket")).isEqualTo("backlog");

        Path quarantined = tempDir.resolve("brain/.archive/duplicates").resolve(filename);
        assertThat(quarantined).exists();
        assertThat(Files.readString(quarantined)).contains("status: dismissed");
    }

    @Test
    void shouldKeepMostRecentlyUpdatedCopyWhenBothMatchTheirOwnDirectory(@TempDir Path tempDir) throws Exception {
        Path backlog = tempDir.resolve("brain/backlog");
        Path someday = tempDir.resolve("brain/someday");
        Files.createDirectories(backlog);
        Files.createDirectories(someday);

        // Both copies are individually self-consistent (bucket matches the dir they sit in) —
        // the backlog copy is scanned first but is the STALE one; someday has the newer edit.
        String filename = "20260630-tiebreak-both-consistent.md";
        Files.writeString(backlog.resolve(filename), """
            ---
            type: action
            title: Tiebreak task
            bucket: backlog
            status: open
            created: 2026-06-30
            updated: 2026-06-30
            tags: [gtd, action]
            ---

            """);
        Files.writeString(someday.resolve(filename), """
            ---
            type: action
            title: Tiebreak task
            bucket: someday
            status: open
            created: 2026-06-30
            updated: 2026-07-01
            tags: [gtd, action]
            ---

            """);

        newVault(tempDir);

        assertThat(someday.resolve(filename)).exists();
        assertThat(backlog.resolve(filename)).doesNotExist();
        assertThat(tempDir.resolve("brain/.archive/duplicates").resolve(filename)).exists();
    }

    @Test
    void shouldNotFlagADoneItemSittingInDoneDirAsMismatchedEvenThoughItsBucketFieldSaysSomethingElse(@TempDir Path tempDir) throws Exception {
        Path done = tempDir.resolve("brain/done");
        Files.createDirectories(done);

        String filename = "20260701-000000-already-done.md";
        Files.writeString(done.resolve(filename), """
            ---
            type: action
            title: Already done
            bucket: today
            status: done
            created: 2026-07-01
            updated: 2026-07-01
            done_date: '2026-07-01'
            tags: [gtd, action]
            ---

            """);

        newVault(tempDir);

        // must stay in done/, NOT get relocated back to today/ just because bucket still says "today"
        assertThat(done.resolve(filename)).exists();
        assertThat(tempDir.resolve("brain/today").resolve(filename)).doesNotExist();
    }

    @Test
    void shouldLeaveOriginUntouchedWhenMoveBucketDestinationAlreadyExists(@TempDir Path tempDir) throws Exception {
        Path backlog = tempDir.resolve("brain/backlog");
        Path someday = tempDir.resolve("brain/someday");
        Files.createDirectories(backlog);
        Files.createDirectories(someday);

        VaultService vault = newVault(tempDir);
        Map<String, Object> op = new java.util.LinkedHashMap<>();
        op.put("bucket", "backlog");
        op.put("title", "Conflicting move");
        op.put("tags", new java.util.ArrayList<>(List.of("work")));
        String filename = vault.write(op, Actor.USER);
        String originalContent = Files.readString(backlog.resolve(filename));

        // Pre-create a conflicting file at the move destination to force Files.move to throw.
        Files.writeString(someday.resolve(filename), "conflicting content");

        assertThatThrownBy(() -> vault.moveBucket(filename, "someday", null, Actor.USER))
            .isInstanceOf(java.io.UncheckedIOException.class);

        assertThat(Files.readString(backlog.resolve(filename))).isEqualTo(originalContent);
    }

    @Test
    void shouldRelocateReferenceBucketFileToResourcesDir(@TempDir Path tempDir) throws Exception {
        Path backlog = tempDir.resolve("brain/backlog");
        Files.createDirectories(backlog);

        String filename = "20260701-000000-misplaced-reference-task.md";
        Files.writeString(backlog.resolve(filename), """
            ---
            type: reference
            title: Misplaced reference note
            bucket: reference
            status: open
            created: 2026-07-01
            updated: 2026-07-01
            tags: [gtd, reference]
            ---

            """);

        VaultService vault = newVault(tempDir);

        assertThat(backlog.resolve(filename)).doesNotExist();
        assertThat(tempDir.resolve("brain/resources").resolve(filename)).exists();
        assertThat(vault.read(filename).get("bucket")).isEqualTo("reference");
    }

    @Test
    void shouldRelocateFileWhoseBucketDoesNotMatchItsDirectory(@TempDir Path tempDir) throws Exception {
        Path backlog = tempDir.resolve("brain/backlog");
        Files.createDirectories(backlog);

        String filename = "20260701-000000-misplaced-someday-task.md";
        Files.writeString(backlog.resolve(filename), """
            ---
            type: action
            title: Misplaced someday task
            bucket: someday
            status: open
            created: 2026-07-01
            updated: 2026-07-01
            tags: [gtd, action]
            ---

            """);

        VaultService vault = newVault(tempDir);

        assertThat(backlog.resolve(filename)).doesNotExist();
        assertThat(tempDir.resolve("brain/someday").resolve(filename)).exists();
        assertThat(vault.read(filename).get("bucket")).isEqualTo("someday");
    }

    @Test
    void shouldNotTouchNonGtdNotesWithoutBucketField(@TempDir Path tempDir) throws Exception {
        Path backlog = tempDir.resolve("brain/backlog");
        Path someday = tempDir.resolve("brain/someday");
        Files.createDirectories(backlog);
        Files.createDirectories(someday);

        // Per-directory meta index — same filename in both dirs by design, not a duplicate task.
        Files.writeString(backlog.resolve("_index.md"), """
            ---
            type: meta
            title: "Brain / Backlog"
            status: active
            ---

            """);
        Files.writeString(someday.resolve("_index.md"), """
            ---
            type: meta
            title: "Brain / Someday"
            status: active
            ---

            """);

        // Freeform idea/someday-maybe note with its own schema (status, not bucket).
        Files.writeString(someday.resolve("some-idea.md"), """
            ---
            type: idea
            status: someday
            ---

            """);

        newVault(tempDir);

        assertThat(backlog.resolve("_index.md")).exists();
        assertThat(someday.resolve("_index.md")).exists();
        assertThat(someday.resolve("some-idea.md")).exists();
        assertThat(backlog.resolve("some-idea.md")).doesNotExist();
    }

    @Test
    void shouldNormalizeTagsOnMoveBucket(@TempDir Path tempDir) throws Exception {
        VaultService vault = newVault(tempDir);
        Map<String, Object> op = new java.util.LinkedHashMap<>();
        op.put("bucket", "backlog");
        op.put("title", "Move to reference");
        op.put("tags", new java.util.ArrayList<>(List.of("work")));
        String filename = vault.write(op, Actor.USER);

        vault.moveBucket(filename, "reference", null, Actor.USER);
        Map<String, Object> moved = vault.read(filename);

        @SuppressWarnings("unchecked")
        List<String> tags = (List<String>) moved.get("tags");
        assertThat(tags).contains("reference");
        assertThat(tags).doesNotContain("action");
        assertThat(moved.get("type")).isEqualTo("reference");
    }

    @Test
    void shouldCountTagsAcrossAllFiveBuckets(@TempDir Path tempDir) throws Exception {
        VaultService vault = newVault(tempDir);

        Map<String, Object> today = new java.util.LinkedHashMap<>();
        today.put("bucket", "today");
        today.put("title", "Today task");
        today.put("tags", new java.util.ArrayList<>(List.of("shopping")));
        vault.write(today, Actor.USER);

        Map<String, Object> backlog1 = new java.util.LinkedHashMap<>();
        backlog1.put("bucket", "backlog");
        backlog1.put("title", "Backlog task 1");
        backlog1.put("tags", new java.util.ArrayList<>(List.of("shopping")));
        vault.write(backlog1, Actor.USER);

        Map<String, Object> backlog2 = new java.util.LinkedHashMap<>();
        backlog2.put("bucket", "backlog");
        backlog2.put("title", "Backlog task 2");
        backlog2.put("tags", new java.util.ArrayList<>(List.of("shopping", "urgent")));
        vault.write(backlog2, Actor.USER);

        Map<String, Map<String, Integer>> counts = vault.tagCounts();

        assertThat(counts.get("shopping"))
            .containsEntry("today", 1)
            .containsEntry("backlog", 2)
            .containsEntry("waiting", 0)
            .containsEntry("someday", 0)
            .containsEntry("reference", 0);
        assertThat(counts.get("urgent")).containsEntry("backlog", 1);
    }

    @Test
    void shouldPersistProjectFieldViaPatchMetaAndReadItBack(@TempDir Path tempDir) throws Exception {
        VaultService vault = newVault(tempDir);
        Map<String, Object> op = new java.util.LinkedHashMap<>();
        op.put("bucket", "backlog");
        op.put("title", "Fix the tag tab bug");
        op.put("tags", new java.util.ArrayList<>(List.of("work")));
        String filename = vault.write(op, Actor.USER);

        vault.patchMeta(filename, Map.of("project", "frontend-gtd"), Actor.USER);

        assertThat(vault.read(filename).get("project")).isEqualTo("frontend-gtd");
        assertThat(vault.list("backlog").stream()
            .filter(m -> filename.equals(m.get("file")))
            .findFirst().orElseThrow().get("project")).isEqualTo("frontend-gtd");
    }

    @Test
    void shouldPersistLocationFieldViaPatchMetaAndReadItBack(@TempDir Path tempDir) throws Exception {
        VaultService vault = newVault(tempDir);
        Map<String, Object> op = new java.util.LinkedHashMap<>();
        op.put("bucket", "backlog");
        op.put("title", "Buy 8mm screws");
        op.put("tags", new java.util.ArrayList<>(List.of("shopping")));
        String filename = vault.write(op, Actor.USER);

        vault.patchMeta(filename, Map.of("location", "ferretería"), Actor.USER);

        assertThat(vault.read(filename).get("location")).isEqualTo("ferretería");
        assertThat(vault.list("backlog").stream()
            .filter(m -> filename.equals(m.get("file")))
            .findFirst().orElseThrow().get("location")).isEqualTo("ferretería");
    }

    @Test
    void shouldPersistAreaFieldWhenValidAndDropInvalidValues(@TempDir Path tempDir) throws Exception {
        VaultService vault = newVault(tempDir);

        // valid area (member of the closed vocabulary) persists via patchMeta
        Map<String, Object> valid = new java.util.LinkedHashMap<>();
        valid.put("bucket", "backlog");
        valid.put("title", "Go to the gym");
        String validFile = vault.write(valid, Actor.USER);
        vault.patchMeta(validFile, Map.of("area", "ejercicio"), Actor.USER);
        assertThat(vault.read(validFile).get("area")).isEqualTo("ejercicio");

        // invalid area is silently dropped — no exception, field stays unset
        Map<String, Object> invalid = new java.util.LinkedHashMap<>();
        invalid.put("bucket", "backlog");
        invalid.put("title", "Something else");
        String invalidFile = vault.write(invalid, Actor.USER);
        org.junit.jupiter.api.Assertions.assertDoesNotThrow(() ->
            vault.patchMeta(invalidFile, Map.of("area", "not-a-real-area"), Actor.USER));
        assertThat(vault.read(invalidFile)).doesNotContainKey("area");

        // same validation applies on write(): a valid area is kept, an invalid one never lands
        Map<String, Object> validOnWrite = new java.util.LinkedHashMap<>();
        validOnWrite.put("bucket", "backlog");
        validOnWrite.put("title", "See friends");
        validOnWrite.put("area", "AMISTAD"); // also verifies case-insensitive normalization
        assertThat(vault.read(vault.write(validOnWrite, Actor.USER)).get("area")).isEqualTo("amistad");

        Map<String, Object> invalidOnWrite = new java.util.LinkedHashMap<>();
        invalidOnWrite.put("bucket", "backlog");
        invalidOnWrite.put("title", "No area here");
        invalidOnWrite.put("area", "bogus");
        assertThat(vault.read(vault.write(invalidOnWrite, Actor.USER))).doesNotContainKey("area");
    }

    @Test
    void areaMatchingIsAccentInsensitiveAndPersistsCanonicalSpelling(@TempDir Path tempDir) throws Exception {
        VaultService vault = newVault(tempDir);
        // an accented/mis-cased variant the LLM may emit still lands as the canonical config value
        Map<String, Object> op = new java.util.LinkedHashMap<>();
        op.put("bucket", "backlog");
        op.put("title", "Go to the gym");
        op.put("area", " Ejercició ");
        assertThat(vault.read(vault.write(op, Actor.USER)).get("area")).isEqualTo("ejercicio");
    }

    @Test
    void validAreasReturnsConfiguredVocabularyInOrder(@TempDir Path tempDir) {
        assertThat(newVault(tempDir).validAreas()).isEqualTo(TEST_AREAS);
    }

    @Test
    void shouldPersistLocationFieldOnWriteAndDropBlankValues(@TempDir Path tempDir) throws Exception {
        VaultService vault = newVault(tempDir);

        // create-time location is stripped and persisted (the write() mirror of the patchMeta test)
        Map<String, Object> withLocation = new java.util.LinkedHashMap<>();
        withLocation.put("bucket", "backlog");
        withLocation.put("title", "Buy 8mm screws");
        withLocation.put("location", " ferretería ");
        assertThat(vault.read(vault.write(withLocation, Actor.USER)).get("location")).isEqualTo("ferretería");

        // a blank location the LLM may emit never lands as an empty field
        Map<String, Object> blankLocation = new java.util.LinkedHashMap<>();
        blankLocation.put("bucket", "backlog");
        blankLocation.put("title", "Answer emails");
        blankLocation.put("location", "   ");
        assertThat(vault.read(vault.write(blankLocation, Actor.USER))).doesNotContainKey("location");
    }

    @Test
    void knownProjectsShouldReturnSortedDeduplicatedNonBlankValuesAcrossBuckets(@TempDir Path tempDir) throws Exception {
        VaultService vault = newVault(tempDir);

        Map<String, Object> a = new java.util.LinkedHashMap<>();
        a.put("bucket", "today");
        a.put("title", "Task A");
        a.put("project", "java-gtd");
        vault.write(a, Actor.USER);

        Map<String, Object> b = new java.util.LinkedHashMap<>();
        b.put("bucket", "backlog");
        b.put("title", "Task B");
        b.put("project", "frontend-gtd");
        vault.write(b, Actor.USER);

        // duplicate project value in another bucket — must be deduplicated
        Map<String, Object> c = new java.util.LinkedHashMap<>();
        c.put("bucket", "someday");
        c.put("title", "Task C");
        c.put("project", "java-gtd");
        vault.write(c, Actor.USER);

        // no project field at all — must be ignored, not crash
        Map<String, Object> d = new java.util.LinkedHashMap<>();
        d.put("bucket", "backlog");
        d.put("title", "Task D");
        vault.write(d, Actor.USER);

        assertThat(vault.knownProjects()).containsExactly("frontend-gtd", "java-gtd");
    }

    @Test
    void knownProjectsShouldExcludeBlankProjectValuesAndReturnEmptyWhenNoneExist(@TempDir Path tempDir) throws Exception {
        VaultService vault = newVault(tempDir);

        assertThat(vault.knownProjects()).isEmpty();

        Map<String, Object> blank = new java.util.LinkedHashMap<>();
        blank.put("bucket", "backlog");
        blank.put("title", "Blank project");
        blank.put("project", "   ");
        vault.write(blank, Actor.USER);

        Map<String, Object> empty = new java.util.LinkedHashMap<>();
        empty.put("bucket", "backlog");
        empty.put("title", "Empty project");
        empty.put("project", "");
        vault.write(empty, Actor.USER);

        assertThat(vault.knownProjects()).isEmpty();
    }

    @Test
    void knownProjectsShouldIncludeProjectsFromDoneAndDiscardedTasks(@TempDir Path tempDir) throws Exception {
        VaultService vault = newVault(tempDir);

        Map<String, Object> done = new java.util.LinkedHashMap<>();
        done.put("bucket", "today");
        done.put("title", "Ship the release");
        done.put("project", "java-gtd");
        String doneFilename = vault.write(done, Actor.USER);
        vault.markDone(doneFilename, Actor.USER);

        Map<String, Object> discarded = new java.util.LinkedHashMap<>();
        discarded.put("bucket", "backlog");
        discarded.put("title", "Abandoned spike");
        discarded.put("project", "frontend-gtd");
        String discardedFilename = vault.write(discarded, Actor.USER);
        vault.dismissItem(discardedFilename, Actor.USER);

        // an established project shouldn't disappear from the known-projects context just
        // because every one of its tasks is finished or dropped
        assertThat(vault.knownProjects()).containsExactly("frontend-gtd", "java-gtd");
    }

    @Test
    void shouldNormalizeDelegadoAToListOnWriteAndPatch(@TempDir Path tempDir) throws Exception {
        VaultService vault = newVault(tempDir);

        Map<String, Object> op = new java.util.LinkedHashMap<>();
        op.put("bucket", "waiting");
        op.put("title", "Ask Juan");
        op.put("delegado_a", "Juan");
        String filename = vault.write(op, Actor.USER);

        @SuppressWarnings("unchecked")
        List<String> delegados = (List<String>) vault.read(filename).get("delegado_a");
        assertThat(delegados).containsExactly("Juan");

        vault.patchMeta(filename, Map.of("delegado_a", List.of("Juan", "Maria")), Actor.USER);

        @SuppressWarnings("unchecked")
        List<String> updated = (List<String>) vault.read(filename).get("delegado_a");
        assertThat(updated).containsExactly("Juan", "Maria");
    }

    @Test
    void shouldDropNullEntriesFromDelegadoAList(@TempDir Path tempDir) throws Exception {
        VaultService vault = newVault(tempDir);

        Map<String, Object> op = new java.util.LinkedHashMap<>();
        op.put("bucket", "waiting");
        op.put("title", "Ask someone");
        op.put("delegado_a", java.util.Arrays.asList("Juan", null, "  "));
        String filename = vault.write(op, Actor.USER);

        @SuppressWarnings("unchecked")
        List<String> delegados = (List<String>) vault.read(filename).get("delegado_a");
        assertThat(delegados).containsExactly("Juan");
    }

    @Test
    void shouldPassThroughEstimateMinutesOnWriteAndAcceptItInPatchMeta(@TempDir Path tempDir) throws Exception {
        VaultService vault = newVault(tempDir);

        // classifier op carries estimate_minutes → generic passthrough files it in the frontmatter
        Map<String, Object> op = new java.util.LinkedHashMap<>();
        op.put("bucket", "backlog");
        op.put("title", "Clean the kitchen");
        op.put("tags", new java.util.ArrayList<>(List.of("home")));
        op.put("estimate_minutes", 30);
        String filename = vault.write(op, Actor.USER);

        assertThat(vault.read(filename).get("estimate_minutes")).isEqualTo(30);

        vault.patchMeta(filename, Map.of("estimate_minutes", 45), Actor.USER);
        assertThat(vault.read(filename).get("estimate_minutes")).isEqualTo(45);
    }

    @Test
    void shouldMigrateLegacyScalarDelegadoAToList(@TempDir Path tempDir) throws Exception {
        Path backlog = tempDir.resolve("brain/backlog");
        Files.createDirectories(backlog);

        String filename = "20260701-000000-legacy-delegado.md";
        Files.writeString(backlog.resolve(filename), """
            ---
            type: action
            title: Legacy delegado
            bucket: waiting
            status: open
            created: 2026-07-01
            delegado_a: Juan
            tags: [gtd, action]
            ---

            """);

        VaultService vault = newVault(tempDir);

        @SuppressWarnings("unchecked")
        List<String> delegados = (List<String>) vault.read(filename).get("delegado_a");
        assertThat(delegados).containsExactly("Juan");
    }

    @Test
    void shouldPassThroughConfirmedFalseOnWriteAndFlipItViaPatchMeta(@TempDir Path tempDir) throws Exception {
        VaultService vault = newVault(tempDir);
        Map<String, Object> op = new java.util.LinkedHashMap<>();
        op.put("bucket", "backlog");
        op.put("title", "Low confidence task");
        op.put("confirmed", false);
        String filename = vault.write(op, Actor.LLM);

        assertThat(vault.read(filename).get("confirmed")).isEqualTo(false);

        vault.patchMeta(filename, Map.of("confirmed", true), Actor.USER);
        assertThat(vault.read(filename).get("confirmed")).isEqualTo(true);
    }

    @Test
    void shouldOmitConfirmedFieldWhenNotProvidedOnWrite(@TempDir Path tempDir) throws Exception {
        VaultService vault = newVault(tempDir);
        Map<String, Object> op = new java.util.LinkedHashMap<>();
        op.put("bucket", "backlog");
        op.put("title", "Normal confidence task");
        String filename = vault.write(op, Actor.LLM);

        assertThat(vault.read(filename)).doesNotContainKey("confirmed");
    }

    @Test
    void migrateBucketMismatchShouldNotCrashOnAnUnrecognizedBucketValueAndShouldLeaveTheFileInPlace(@TempDir Path tempDir) throws Exception {
        Path backlog = tempDir.resolve("brain/backlog");
        Files.createDirectories(backlog);

        // "inbox" was never a valid bucket value, but a hand-edited frontmatter or leftover from
        // an older schema could plausibly put it here — the self-healing migration must not crash.
        String filename = "20260701-000000-legacy-bucket-value.md";
        Files.writeString(backlog.resolve(filename), """
            ---
            type: action
            title: Weird legacy bucket
            bucket: inbox
            status: open
            created: 2026-07-01
            updated: 2026-07-01
            tags: [gtd, action]
            ---

            """);

        VaultService vault = org.junit.jupiter.api.Assertions.assertDoesNotThrow(() -> newVault(tempDir));

        assertThat(backlog.resolve(filename)).exists();
        assertThat(vault.read(filename).get("bucket")).isEqualTo("inbox");
    }

    @Test
    void listCompletedSinceAndHistoryShouldSortByDoneDateNotByALaterMetadataEdit(@TempDir Path tempDir) throws Exception {
        Path done = tempDir.resolve("brain/done");
        Files.createDirectories(done);
        String filename = "20260620-000000-completed-a-week-ago.md";
        Files.writeString(done.resolve(filename), """
            ---
            type: action
            title: Completed a week ago
            bucket: backlog
            status: done
            created: 2026-06-20
            updated: 2026-06-20
            done_date: '2026-06-20'
            tags: [gtd, action]
            ---

            """);

        VaultService vault = newVault(tempDir);

        // editing an already-done item bumps `updated` to today but must not affect done_date
        vault.patchMeta(filename, Map.of("tags", List.of("gtd", "action", "retagged")), Actor.USER);
        Map<String, Object> reread = vault.read(filename);
        assertThat(reread.get("done_date")).isEqualTo("2026-06-20");
        assertThat(reread.get("updated")).isEqualTo(LocalDate.now().toString());

        // listCompletedSince(3) = "completed in the last 3 days" — must exclude this despite today's edit
        assertThat(vault.listCompletedSince(3).stream().anyMatch(m -> filename.equals(m.get("file"))))
            .as("a task done a week ago shouldn't reappear as newly-completed just because it was edited today")
            .isFalse();

        assertThat(vault.history(1).get(0).get("done_date")).isEqualTo("2026-06-20");
    }

    @Test
    void undoOfCreateShouldDeleteTheFile(@TempDir Path tempDir) throws Exception {
        EventLog eventLog = new EventLog(tempDir.toString());
        VaultService vault = newVault(tempDir, eventLog);
        Map<String, Object> op = new java.util.LinkedHashMap<>();
        op.put("bucket", "backlog");
        op.put("title", "Undo me");
        String filename = vault.write(op, Actor.USER);
        assertThat(tempDir.resolve("brain/backlog").resolve(filename)).exists();

        Event created = eventLog.nextUndoable().orElseThrow();
        vault.undoEvent(created);

        assertThat(tempDir.resolve("brain/backlog").resolve(filename)).doesNotExist();
    }

    @Test
    void undoOfMoveShouldRestoreOriginalLocationWithoutLeavingADuplicateAtDestination(@TempDir Path tempDir) throws Exception {
        EventLog eventLog = new EventLog(tempDir.toString());
        VaultService vault = newVault(tempDir, eventLog);
        Map<String, Object> op = new java.util.LinkedHashMap<>();
        op.put("bucket", "backlog");
        op.put("title", "Move me");
        String filename = vault.write(op, Actor.USER);

        vault.moveBucket(filename, "today", null, Actor.USER);
        assertThat(tempDir.resolve("brain/today").resolve(filename)).exists();

        Event moveEvent = eventLog.tail(0, null, "move").get(0);
        vault.undoEvent(moveEvent);

        assertThat(tempDir.resolve("brain/backlog").resolve(filename))
            .as("undo must restore the file at its pre-move location")
            .exists();
        assertThat(tempDir.resolve("brain/today").resolve(filename))
            .as("undo must not leave a duplicate at the post-move location — this was the historical bug")
            .doesNotExist();
    }

    @Test
    void undoControllerFlowShouldWalkBackTwoMutationsInReverseOrder(@TempDir Path tempDir) throws Exception {
        EventLog eventLog = new EventLog(tempDir.toString());
        VaultService vault = newVault(tempDir, eventLog);
        Map<String, Object> op = new java.util.LinkedHashMap<>();
        op.put("bucket", "backlog");
        op.put("title", "Two-step task");
        String filename = vault.write(op, Actor.USER);
        vault.moveBucket(filename, "today", null, Actor.USER);

        // step 1: undo the move -> back to backlog
        Event lastMove = eventLog.nextUndoable().orElseThrow();
        vault.undoEvent(lastMove);
        eventLog.append(Event.undoOf(lastMove));
        assertThat(tempDir.resolve("brain/backlog").resolve(filename)).exists();

        // step 2: undo the create -> file gone entirely
        Event createEvent = eventLog.nextUndoable().orElseThrow();
        vault.undoEvent(createEvent);
        eventLog.append(Event.undoOf(createEvent));
        assertThat(tempDir.resolve("brain/backlog").resolve(filename)).doesNotExist();

        assertThat(eventLog.nextUndoable()).isEmpty();
    }
}
