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
import static org.assertj.core.api.InstanceOfAssertFactories.LIST;

class VaultServiceTest {

    private static VaultService newVault(Path tempDir) {
        return newVault(tempDir, new EventLog(tempDir.toString()));
    }

    // Mirrors the localized vocabulary a real deployment may configure via gtd.areas — the
    // committed default is English, but validation must be vocabulary-agnostic.
    private static final List<String> TEST_AREAS =
        List.of("personal", "amistad", "ejercicio", "trabajo", "salud", "finanzas", "hogar", "aprendizaje");

    private static VaultService newVault(Path tempDir, EventLog eventLog) {
        return new VaultService(tempDir.toString(), TEST_AREAS, eventLog, new ObjectMapper(), true);
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
        vault.replaceBody(filename, "extra note", Actor.USER);
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
    void knownTagsShouldReturnSortedDeduplicatedValuesAcrossBuckets(@TempDir Path tempDir) throws Exception {
        VaultService vault = newVault(tempDir);

        Map<String, Object> a = new java.util.LinkedHashMap<>();
        a.put("bucket", "today");
        a.put("title", "Task A");
        a.put("tags", java.util.List.of("compras", "hogar"));
        vault.write(a, Actor.USER);

        // duplicate tag in another bucket — must be deduplicated
        Map<String, Object> b = new java.util.LinkedHashMap<>();
        b.put("bucket", "backlog");
        b.put("title", "Task B");
        b.put("tags", java.util.List.of("compras", "salud"));
        vault.write(b, Actor.USER);

        // no tags field at all — must be ignored, not crash
        Map<String, Object> c = new java.util.LinkedHashMap<>();
        c.put("bucket", "backlog");
        c.put("title", "Task C");
        vault.write(c, Actor.USER);

        assertThat(vault.knownTags()).containsExactly("compras", "hogar", "salud");
    }

    @Test
    void knownTagsShouldReturnEmptyWhenNoneExist(@TempDir Path tempDir) throws Exception {
        VaultService vault = newVault(tempDir);
        assertThat(vault.knownTags()).isEmpty();
    }

    @Test
    void knownTagsShouldIncludeTagsFromDoneAndDiscardedTasks(@TempDir Path tempDir) throws Exception {
        VaultService vault = newVault(tempDir);

        Map<String, Object> done = new java.util.LinkedHashMap<>();
        done.put("bucket", "today");
        done.put("title", "Ship the release");
        done.put("tags", java.util.List.of("trabajo"));
        String doneFilename = vault.write(done, Actor.USER);
        vault.markDone(doneFilename, Actor.USER);

        Map<String, Object> discarded = new java.util.LinkedHashMap<>();
        discarded.put("bucket", "backlog");
        discarded.put("title", "Abandoned spike");
        discarded.put("tags", java.util.List.of("mascotas"));
        String discardedFilename = vault.write(discarded, Actor.USER);
        vault.dismissItem(discardedFilename, Actor.USER);

        // an established tag shouldn't disappear from the known-tags context just because
        // every one of its tasks is finished or dropped
        assertThat(vault.knownTags()).containsExactly("mascotas", "trabajo");
    }

            @Test
    void shouldIgnoreUnknownKeysOnWrite(@TempDir Path tempDir) throws Exception {
        VaultService vault = newVault(tempDir);

        // write() used to copy every key it didn't recognize into the frontmatter, which made any
        // caller handing it unvalidated input — POST /api/items, or the LLM hallucinating a field
        // into a create op — able to write arbitrary keys and vault-owned lifecycle fields.
        Map<String, Object> op = new java.util.LinkedHashMap<>();
        op.put("bucket", "backlog");
        op.put("title", "Clean the kitchen");
        op.put("done_date", "2020-01-01");
        op.put("discarded_date", "2020-01-01");
        op.put("done", true);
        op.put("whatever", "junk");
        String filename = vault.write(op, Actor.USER);

        Map<String, Object> saved = vault.read(filename);
        assertThat(saved).containsEntry("status", "open");
        assertThat(saved).doesNotContainKeys("done_date", "discarded_date", "done", "whatever");
    }

    @Test
    void shouldPersistCaptureSourceOnWrite(@TempDir Path tempDir) throws Exception {
        VaultService vault = newVault(tempDir);

        // ChatController attaches the originating utterance to a captured task; blank means the
        // caller had none to give, and an empty frontmatter key is worse than no key.
        Map<String, Object> op = new java.util.LinkedHashMap<>();
        op.put("bucket", "backlog");
        op.put("title", "Call the dentist");
        op.put("capture_source", "recordame llamar al dentista");
        assertThat(vault.read(vault.write(op, Actor.USER)))
            .containsEntry("capture_source", "recordame llamar al dentista");

        op.put("capture_source", "   ");
        assertThat(vault.read(vault.write(op, Actor.USER))).doesNotContainKey("capture_source");
    }

    @Test
    void shouldPersistPriorityOnWrite(@TempDir Path tempDir) throws Exception {
        VaultService vault = newVault(tempDir);

        // priority arrives on create from POST /api/items; patchMeta coverage alone left the
        // create path untested.
        Map<String, Object> op = new java.util.LinkedHashMap<>();
        op.put("bucket", "backlog");
        op.put("title", "Pay the rent");
        op.put("priority", "high");

        assertThat(vault.read(vault.write(op, Actor.USER))).containsEntry("priority", "high");
    }

    @Test
    void shouldPersistEstimateMinutesOnWriteAndAcceptItInPatchMeta(@TempDir Path tempDir) throws Exception {
        VaultService vault = newVault(tempDir);

        // classifier op carries estimate_minutes → write() files it in the frontmatter
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
    void listUnconfirmedShouldReturnOnlyActiveTasksWithConfirmedFalse(@TempDir Path tempDir) throws Exception {
        VaultService vault = newVault(tempDir);

        Map<String, Object> low = new java.util.LinkedHashMap<>();
        low.put("bucket", "backlog");
        low.put("title", "Low confidence task");
        low.put("confirmed", false);
        String lowFile = vault.write(low, Actor.LLM);

        // confirmed:true and confirmed absent must NOT show up in the review queue
        Map<String, Object> high = new java.util.LinkedHashMap<>();
        high.put("bucket", "backlog");
        high.put("title", "High confidence task");
        high.put("confirmed", true);
        vault.write(high, Actor.LLM);

        Map<String, Object> normal = new java.util.LinkedHashMap<>();
        normal.put("bucket", "today");
        normal.put("title", "Normal task");
        vault.write(normal, Actor.LLM);

        List<Map<String, Object>> unconfirmed = vault.listUnconfirmed();
        assertThat(unconfirmed).hasSize(1);
        assertThat(unconfirmed.get(0).get("file")).isEqualTo(lowFile);

        // once confirmed via patchMeta, it drops out of the queue
        vault.patchMeta(lowFile, Map.of("confirmed", true), Actor.USER);
        assertThat(vault.listUnconfirmed()).isEmpty();
    }

    @Test
    void shouldPersistPriorityViaPatchMetaAndReadItBack(@TempDir Path tempDir) throws Exception {
        VaultService vault = newVault(tempDir);
        Map<String, Object> op = new java.util.LinkedHashMap<>();
        op.put("bucket", "backlog");
        op.put("title", "Prioritize me");
        String filename = vault.write(op, Actor.USER);

        vault.patchMeta(filename, Map.of("priority", "high"), Actor.USER);
        assertThat(vault.read(filename).get("priority")).isEqualTo("high");
    }

    @Test
    void shouldPersistCaptureSourceOnWriteViaPassthrough(@TempDir Path tempDir) throws Exception {
        VaultService vault = newVault(tempDir);
        Map<String, Object> op = new java.util.LinkedHashMap<>();
        op.put("bucket", "backlog");
        op.put("title", "Buy milk");
        op.put("capture_source", "acordate de comprar leche mañana");
        String filename = vault.write(op, Actor.LLM);

        assertThat(vault.read(filename).get("capture_source")).isEqualTo("acordate de comprar leche mañana");
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

    // ---- [[wikilink]] derivation: related / related_people --------------------------------
    //
    // The body is the only place either field is authored. These lock in that nothing else can
    // write them, that a mention resolves to the canonical page name, and — the one that matters
    // most — that an unresolved link is left alone instead of being invented into the field, which
    // is how the old freeform column ended up holding "gtd" and "[[presupuestador]]".

    private static void givenPerson(Path tempDir, String name) throws Exception {
        Path entities = tempDir.resolve("brain/entities");
        Files.createDirectories(entities);
        Files.writeString(entities.resolve(name + ".md"), "---\ntype: entity\n---\n");
    }

    @Test
    void shouldDeriveRelatedPeopleFromBodyWikilink(@TempDir Path tempDir) throws Exception {
        givenPerson(tempDir, "Ana");
        VaultService vault = newVault(tempDir);

        Map<String, Object> op = new java.util.LinkedHashMap<>();
        op.put("bucket", "waiting");
        op.put("title", "Water bill");
        op.put("body", "Talk to [[Ana]] before it is due.");
        String filename = vault.write(op, Actor.USER);

        assertThat(vault.read(filename).get("related_people")).asInstanceOf(LIST).containsExactly("Ana");
    }

    @Test
    void shouldPersistTheCanonicalPersonNameWhateverTheMentionsCasingOrAccents(@TempDir Path tempDir) throws Exception {
        givenPerson(tempDir, "Mary-Jane");
        VaultService vault = newVault(tempDir);

        Map<String, Object> op = new java.util.LinkedHashMap<>();
        op.put("bucket", "backlog");
        op.put("title", "Call");
        op.put("body", "Let [[mary-jane]] know.");
        String filename = vault.write(op, Actor.USER);

        // The mention was lowercase; what lands on disk is the page's own name, so every task
        // mentioning this person groups under one value instead of jane/Jane/mary-jane.
        assertThat(vault.read(filename).get("related_people")).asInstanceOf(LIST).containsExactly("Mary-Jane");
    }

        @Test
    void shouldIgnoreAWikilinkThatResolvesToNeitherTaskNorPerson(@TempDir Path tempDir) throws Exception {
        VaultService vault = newVault(tempDir);

        Map<String, Object> op = new java.util.LinkedHashMap<>();
        op.put("bucket", "backlog");
        op.put("title", "A note");
        op.put("body", "See [[a-page-that-does-not-exist]].");
        String filename = vault.write(op, Actor.USER);

        Map<String, Object> note = vault.read(filename);
        assertThat(note).doesNotContainKey("related");
        assertThat(note).doesNotContainKey("related_people");
    }

    @Test
    void shouldDropTheDerivedFieldWhenTheMentionLeavesTheBody(@TempDir Path tempDir) throws Exception {
        givenPerson(tempDir, "Ana");
        VaultService vault = newVault(tempDir);

        Map<String, Object> op = new java.util.LinkedHashMap<>();
        op.put("bucket", "waiting");
        op.put("title", "Water bill");
        op.put("body", "Talk to [[Ana]].");
        String filename = vault.write(op, Actor.USER);
        assertThat(vault.read(filename)).containsKey("related_people");

        vault.replaceBody(filename, "Nobody else is involved any more.", Actor.USER);

        assertThat(vault.read(filename)).doesNotContainKey("related_people");
    }

    @Test
    void shouldRefuseRelatedPeopleAndRelatedAsDirectPatchInput(@TempDir Path tempDir) throws Exception {
        givenPerson(tempDir, "Ana");
        VaultService vault = newVault(tempDir);

        Map<String, Object> op = new java.util.LinkedHashMap<>();
        op.put("bucket", "backlog");
        op.put("title", "A task");
        String filename = vault.write(op, Actor.USER);

        vault.patchMeta(filename, Map.of("related_people", List.of("Ana"), "related", List.of("x.md")), Actor.USER);

        // Silently ignored, like any key outside the whitelist: accepting it would store a value
        // the next body edit overwrites without warning.
        Map<String, Object> note = vault.read(filename);
        assertThat(note).doesNotContainKey("related_people");
        assertThat(note).doesNotContainKey("related");
    }

    @Test
    void shouldKeepDerivedFieldsAcrossABucketMove(@TempDir Path tempDir) throws Exception {
        givenPerson(tempDir, "Ana");
        VaultService vault = newVault(tempDir);

        Map<String, Object> op = new java.util.LinkedHashMap<>();
        op.put("bucket", "backlog");
        op.put("title", "A task");
        op.put("body", "With [[Ana]].");
        String filename = vault.write(op, Actor.USER);

        vault.moveBucket(filename, "today", null, Actor.USER);

        assertThat(vault.read(filename).get("related_people")).asInstanceOf(LIST).containsExactly("Ana");
    }

    @Test
    void knownPeopleShouldListEntityPagesAndSkipIndexes(@TempDir Path tempDir) throws Exception {
        givenPerson(tempDir, "Ana");
        givenPerson(tempDir, "Mary-Jane");
        givenPerson(tempDir, "_index");
        VaultService vault = newVault(tempDir);

        assertThat(vault.knownPeople()).containsExactly("Ana", "Mary-Jane");
    }

    @Test
    void knownPeopleShouldBeEmptyWithoutAnEntitiesDirectory(@TempDir Path tempDir) {
        assertThat(newVault(tempDir).knownPeople()).isEmpty();
    }

        @Test
    void shouldFollowAnAliasedWikilinkToItsTarget(@TempDir Path tempDir) throws Exception {
        // "[[Ana|Annie]]" reads as Annie but points at Ana — the alias is how the migration
        // keeps a card's wording while still resolving to the canonical person page.
        givenPerson(tempDir, "Ana");
        VaultService vault = newVault(tempDir);

        Map<String, Object> op = new java.util.LinkedHashMap<>();
        op.put("bucket", "waiting");
        op.put("title", "Quote bot");
        op.put("body", "El que tiene que probarlo es [[Ana|Annie]].");
        String filename = vault.write(op, Actor.USER);

        assertThat(vault.read(filename).get("related_people")).asInstanceOf(LIST).containsExactly("Ana");
    }

    @Test
    void resolveLinksShouldReportKindAndPathForEachTarget(@TempDir Path tempDir) throws Exception {
        givenPerson(tempDir, "Ana");
        Files.createDirectories(tempDir.resolve("brain/projects"));
        Files.writeString(tempDir.resolve("brain/projects/java-gtd.md"), "---\ntype: project\n---\n");
        VaultService vault = newVault(tempDir);

        Map<String, Object> task = new java.util.LinkedHashMap<>();
        task.put("bucket", "backlog");
        task.put("title", "Buy paint");
        String target = vault.write(task, Actor.USER);

        var links = vault.resolveLinks(
            "Con [[Ana]], depende de [[" + target.replace(".md", "") + "]], ver [[java-gtd]].");

        assertThat(links).extracting(VaultService.ResolvedLink::kind).containsExactly(
            VaultService.LinkKind.PERSON, VaultService.LinkKind.TASK, VaultService.LinkKind.NOTE);
        assertThat(links).extracting(VaultService.ResolvedLink::path).containsExactly(
            "brain/entities/Ana.md", "brain/backlog/" + target, "brain/projects/java-gtd.md");
    }

    @Test
    void resolveLinksShouldSkipTheVaultsNonLinkableCorners(@TempDir Path tempDir) throws Exception {
        Files.createDirectories(tempDir.resolve(".obsidian"));
        Files.writeString(tempDir.resolve(".obsidian/workspace.md"), "internal");
        Files.createDirectories(tempDir.resolve("brain/.archive/duplicates"));
        Files.writeString(tempDir.resolve("brain/.archive/duplicates/superseded.md"), "old copy");
        VaultService vault = newVault(tempDir);

        assertThat(vault.resolveLinks("See [[workspace]] and [[superseded]].")).isEmpty();
    }

    @Test
    void shouldLeaveTheVaultsCuratedRelatedFieldAlone(@TempDir Path tempDir) throws Exception {
        // `related` is not this service's field: it's a vault-wide convention (415 notes, written
        // by the save/autoresearch/wiki-query skills) holding curated cross-references, which are
        // not the same set as the pages a body happens to mention. Deriving it would silently drop
        // those curations on the next save.
        VaultService vault = newVault(tempDir);
        Map<String, Object> op = new java.util.LinkedHashMap<>();
        op.put("bucket", "backlog");
        op.put("title", "Migrate features");
        String filename = vault.write(op, Actor.USER);

        Path file = tempDir.resolve("brain/backlog").resolve(filename);
        Files.writeString(file, Files.readString(file).replaceFirst(
            "(?m)^status: open$",
            "status: open\nrelated:\n  - '[[java-gtd]]'\n  - '[[frontend-gtd]]'"));

        // A body edit that mentions neither of them must not disturb the curated list.
        vault.replaceBody(filename, "No mention of either one.", Actor.USER);

        assertThat(vault.read(filename).get("related")).asInstanceOf(LIST)
            .containsExactly("[[java-gtd]]", "[[frontend-gtd]]");
    }

    @Test
    void shouldNotInventARelatedFieldOnACardThatHadNone(@TempDir Path tempDir) throws Exception {
        Files.createDirectories(tempDir.resolve("brain/projects"));
        Files.writeString(tempDir.resolve("brain/projects/java-gtd.md"), "---\ntype: project\n---\n");
        VaultService vault = newVault(tempDir);

        Map<String, Object> op = new java.util.LinkedHashMap<>();
        op.put("bucket", "backlog");
        op.put("title", "A task");
        op.put("body", "See [[java-gtd]].");
        String filename = vault.write(op, Actor.USER);

        // The mention is real and resolveLinks() reports it, but it is served live — never written.
        assertThat(vault.read(filename)).doesNotContainKey("related");
        assertThat(vault.resolveLinks("See [[java-gtd]].")).hasSize(1);
    }

    @Test
    void shouldResolveAPersonByTheirObsidianAlias(@TempDir Path tempDir) throws Exception {
        // Ana's page carries `aliases: [Annie]` — his actual nickname, and the reason the old
        // freeform column held both "Ana" and "tito"/"Annie" as if they were three people.
        Path entities = tempDir.resolve("brain/entities");
        Files.createDirectories(entities);
        Files.writeString(entities.resolve("Ana.md"),
            "---\ntype: entity\naliases:\n  - Annie\n---\n");
        VaultService vault = newVault(tempDir);

        assertThat(vault.knownPeople()).containsExactly("Ana", "Annie");

        Map<String, Object> op = new java.util.LinkedHashMap<>();
        op.put("bucket", "waiting");
        op.put("title", "Quote bot");
        op.put("body", "Waiting on [[Annie]] to try it.");
        String filename = vault.write(op, Actor.USER);

        // Written as Annie, stored as Ana: one person, one value to group by.
        assertThat(vault.read(filename).get("related_people")).asInstanceOf(LIST).containsExactly("Ana");
    }

    @Test
    void aRealPageShouldWinOverSomeoneElsesAlias(@TempDir Path tempDir) throws Exception {
        Path entities = tempDir.resolve("brain/entities");
        Files.createDirectories(entities);
        Files.writeString(entities.resolve("Ana.md"), "---\ntype: entity\naliases:\n  - Annie\n---\n");
        Files.writeString(entities.resolve("Annie.md"), "---\ntype: entity\n---\n");
        VaultService vault = newVault(tempDir);

        Map<String, Object> op = new java.util.LinkedHashMap<>();
        op.put("bucket", "waiting");
        op.put("title", "Who is who");
        op.put("body", "With [[Annie]].");
        String filename = vault.write(op, Actor.USER);

        assertThat(vault.read(filename).get("related_people")).asInstanceOf(LIST).containsExactly("Annie");
    }

    @Test
    void aSessionNoteInTheReferenceBucketIsNotATask(@TempDir Path tempDir) throws Exception {
        // brain/resources/ is the `reference` bucket, but 89 of its 96 real pages are session
        // notes written by /save with no `bucket` field. Judging by folder alone marked those as
        // tasks, which would send the frontend to open a session note in the task modal.
        VaultService vault = newVault(tempDir);
        Path resources = tempDir.resolve("brain/resources");
        Files.writeString(resources.resolve("a-session-note.md"), "---\ntype: session\n---\n");
        Files.writeString(resources.resolve("a-reference-card.md"),
            "---\ntype: reference\nbucket: reference\n---\n");

        var links = vault.resolveLinks("See [[a-session-note]] and [[a-reference-card]].");

        assertThat(links).extracting(VaultService.ResolvedLink::kind)
            .containsExactly(VaultService.LinkKind.NOTE, VaultService.LinkKind.TASK);
    }

    @Test
    void aWikilinkCanNotReachOutsideTheVault(@TempDir Path tempDir) {
        // Body text is only ever a lookup key into the name index — it never becomes a path. A
        // traversal attempt finds no key and resolves to nothing, so the filesystem is never
        // touched with attacker-controlled text.
        VaultService vault = newVault(tempDir);

        assertThat(vault.resolveLinks("See [[../../../etc/passwd]] and [[..\\..\\secrets]].")).isEmpty();
    }

    @Test
    void codeCheckoutsUnderTheVaultRootAreNotVaultPages(@TempDir Path tempDir) throws Exception {
        // The vault root also holds workspace/, where the code projects are checked out. Walking
        // from the root indexed their READMEs as vault pages — so [[README]] in a card resolved to
        // a project README — and descended into node_modules on the way: 71k filesystem entries
        // per pass instead of 660.
        Files.createDirectories(tempDir.resolve("workspace/some-project/node_modules/left-pad"));
        Files.writeString(tempDir.resolve("workspace/some-project/README.md"), "# a code project");
        Files.writeString(tempDir.resolve("workspace/some-project/node_modules/left-pad/README.md"), "# a dependency");
        Files.createDirectories(tempDir.resolve("wiki/concepts"));
        Files.writeString(tempDir.resolve("wiki/concepts/Real-Page.md"), "---\ntype: concept\n---\n");
        VaultService vault = newVault(tempDir);

        assertThat(vault.resolveLinks("See [[README]].")).isEmpty();
        assertThat(vault.resolveLinks("See [[Real-Page]].")).hasSize(1);
    }

    @Test
    void vaultPagesShouldOfferEveryLinkTargetIncludingClosedTasks(@TempDir Path tempDir) throws Exception {
        Path entities = tempDir.resolve("brain/entities");
        Files.createDirectories(entities);
        Files.writeString(entities.resolve("Ana.md"), "---\ntype: entity\n---\n");
        Files.createDirectories(tempDir.resolve("wiki/concepts"));
        Files.writeString(tempDir.resolve("wiki/concepts/Some-Concept.md"), "---\ntype: concept\n---\n");
        VaultService vault = newVault(tempDir);

        Map<String, Object> op = new java.util.LinkedHashMap<>();
        op.put("bucket", "backlog");
        op.put("title", "An open task");
        String open = vault.write(op, Actor.USER);
        Map<String, Object> closing = new java.util.LinkedHashMap<>();
        closing.put("bucket", "backlog");
        closing.put("title", "A finished task");
        String closed = vault.write(closing, Actor.USER);
        vault.markDone(closed, Actor.USER);

        var pages = vault.vaultPages();

        // A finished task stays offerable: a card linking to one is saying where it came from.
        assertThat(pages).extracting(VaultService.ResolvedLink::name)
            .contains("Ana", "Some-Concept", open.replace(".md", ""), closed.replace(".md", ""));
        assertThat(pages).filteredOn(l -> l.name().equals("Ana"))
            .extracting(VaultService.ResolvedLink::kind).containsExactly(VaultService.LinkKind.PERSON);
        assertThat(pages).filteredOn(l -> l.path().startsWith("brain/done/"))
            .extracting(VaultService.ResolvedLink::kind).containsExactly(VaultService.LinkKind.TASK);
    }

    /** Files a backlog task and returns its filename. */
    private static String task(VaultService vault, String title) {
        Map<String, Object> op = new java.util.LinkedHashMap<>();
        op.put("bucket", "backlog");
        op.put("title", title);
        return vault.write(op, Actor.USER);
    }

    @Test
    void dependsOnAcceptsFilenamesTheVaultActuallyHolds(@TempDir Path tempDir) throws Exception {
        VaultService vault = newVault(tempDir);
        String blocker = task(vault, "Order the tiles");
        String blocked = task(vault, "Lay the tiles");

        vault.patchMeta(blocked, Map.of("depends_on", List.of(blocker)), Actor.USER);

        assertThat(vault.read(blocked).get("depends_on")).asInstanceOf(LIST).containsExactly(blocker);
    }

    @Test
    void dependsOnRejectsAFileThatIsNotInTheVault(@TempDir Path tempDir) throws Exception {
        VaultService vault = newVault(tempDir);
        String blocked = task(vault, "Lay the tiles");

        assertThatThrownBy(() -> vault.patchMeta(blocked, Map.of("depends_on", List.of("20990101-000000-ghost.md")), Actor.USER))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("depends_on")
            .hasMessageContaining("20990101-000000-ghost.md");
        // rejected before anything is written: the note keeps no half-applied value
        assertThat(vault.read(blocked)).doesNotContainKey("depends_on");
    }

    @Test
    void createRejectsADependencyThatIsNotInTheVault(@TempDir Path tempDir) throws Exception {
        VaultService vault = newVault(tempDir);
        Map<String, Object> op = new java.util.LinkedHashMap<>();
        op.put("bucket", "backlog");
        op.put("title", "Lay the tiles");
        op.put("depends_on", List.of("20990101-000000-ghost.md"));

        assertThatThrownBy(() -> vault.write(op, Actor.USER))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("depends_on");
        try (var files = Files.list(tempDir.resolve("brain/backlog"))) {
            assertThat(files).isEmpty();
        }
    }

    @Test
    void markDoneClosesTheItemAnywayAndReportsTheDependenciesStillOpen(@TempDir Path tempDir) throws Exception {
        VaultService vault = newVault(tempDir);
        String open = task(vault, "Order the tiles");
        String finished = task(vault, "Measure the floor");
        String blocked = task(vault, "Lay the tiles");
        vault.markDone(finished, Actor.USER);
        vault.patchMeta(blocked, Map.of("depends_on", List.of(open, finished)), Actor.USER);

        var stillOpen = vault.markDone(blocked, Actor.USER);

        // warn, never forbid: the close happened regardless
        assertThat(tempDir.resolve("brain/done").resolve(blocked)).exists();
        assertThat(vault.read(blocked).get("status")).isEqualTo("done");
        // only the unfinished one is reported, with the title the app needs to name it
        assertThat(stillOpen).containsExactly(Map.of("file", open, "title", "Order the tiles"));
    }

    @Test
    void markDoneReportsNothingWhenEveryDependencyIsAlreadyClosed(@TempDir Path tempDir) throws Exception {
        VaultService vault = newVault(tempDir);
        String done = task(vault, "Order the tiles");
        String dismissed = task(vault, "Call the tiler");
        String blocked = task(vault, "Lay the tiles");
        vault.patchMeta(blocked, Map.of("depends_on", List.of(done, dismissed)), Actor.USER);
        vault.markDone(done, Actor.USER);
        vault.dismissItem(dismissed, Actor.USER);

        assertThat(vault.markDone(blocked, Actor.USER)).isEmpty();
        assertThat(vault.read(blocked).get("status")).isEqualTo("done");
    }

    @Test
    void everyLinkCarriesAnObsidianUriBuiltFromTheConfiguredVaultPath(@TempDir Path tempDir) throws Exception {
        // Built server-side on purpose: the frontend must never need to know where the vault
        // lives or what it is called. One configured value (gtd.vault.path) answers for both.
        Files.createDirectories(tempDir.resolve("wiki/concepts"));
        Files.writeString(tempDir.resolve("wiki/concepts/Some Concept.md"), "---\ntype: concept\n---\n");
        VaultService vault = newVault(tempDir);

        var links = vault.resolveLinks("See [[Some Concept]].");

        assertThat(links).hasSize(1);
        String uri = links.get(0).obsidianUri();
        assertThat(uri).startsWith("obsidian://open?path=");
        // A space must not survive as "+" — Obsidian does not decode form encoding.
        assertThat(uri).doesNotContain("+").contains("%20");
        assertThat(java.net.URLDecoder.decode(uri.substring("obsidian://open?path=".length()), java.nio.charset.StandardCharsets.UTF_8))
            .isEqualTo(tempDir.toAbsolutePath().normalize().resolve("wiki/concepts/Some Concept.md").toString());
    }
}
