package ar.maxi.gtd.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class VaultServiceTest {

    private static VaultService newVault(Path tempDir) {
        return newVault(tempDir, new EventLog(tempDir.toString()));
    }

    private static VaultService newVault(Path tempDir, EventLog eventLog) {
        return new VaultService(tempDir.toString(), eventLog, true, true, true, true, true);
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
    void shouldAddActionTagForNonReferenceItem(@TempDir Path tempDir) throws Exception {
        VaultService vault = newVault(tempDir);
        Map<String, Object> op = new java.util.LinkedHashMap<>();
        op.put("bucket", "today");
        op.put("title", "Do something");
        op.put("tags", new java.util.ArrayList<>(List.of("work")));

        String filename = vault.write(op, Actor.USER);
        Map<String, Object> saved = vault.read(filename);

        @SuppressWarnings("unchecked")
        List<String> tags = (List<String>) saved.get("tags");
        assertThat(tags).contains("gtd", "action", "work");
        assertThat(tags).doesNotContain("reference");
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

        @SuppressWarnings("unchecked")
        List<String> tags = (List<String>) saved.get("tags");
        assertThat(tags).contains("gtd", "action");
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
        assertThat(tags).contains("gtd", "reference");
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
