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

    @Test
    void shouldCreateVaultDirectoriesOnStartup(@TempDir Path tempDir) {
        new VaultService(tempDir.toString(), new UndoStack(), true, true, true, true);

        assertThat(tempDir.resolve("brain/inbox")).isDirectory();
        assertThat(tempDir.resolve("brain/someday")).isDirectory();
        assertThat(tempDir.resolve("brain/resources")).isDirectory();
    }

    @Test
    void shouldRejectPathTraversalInFilename(@TempDir Path tempDir) {
        VaultService vault = new VaultService(tempDir.toString(), new UndoStack(), true, true, true, true);
        assertThatThrownBy(() -> vault.read("../../../etc/passwd"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid filename");
    }

    @Test
    void shouldRejectFilenameWithoutMdExtension(@TempDir Path tempDir) {
        VaultService vault = new VaultService(tempDir.toString(), new UndoStack(), true, true, true, true);
        assertThatThrownBy(() -> vault.read("noextension"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid filename");
    }

    @Test
    void shouldNotFailIfDirectoriesAlreadyExist(@TempDir Path tempDir) throws Exception {
        Files.createDirectories(tempDir.resolve("brain/inbox"));
        Files.createDirectories(tempDir.resolve("brain/someday"));
        Files.createDirectories(tempDir.resolve("brain/resources"));

        org.junit.jupiter.api.Assertions.assertDoesNotThrow(
            () -> new VaultService(tempDir.toString(), new UndoStack(), true, true, true, true)
        );
    }

    @Test
    void shouldAddActionTagForNonReferenceItem(@TempDir Path tempDir) throws Exception {
        VaultService vault = new VaultService(tempDir.toString(), new UndoStack(), true, true, true, true);
        Map<String, Object> op = new java.util.LinkedHashMap<>();
        op.put("bucket", "today");
        op.put("title", "Do something");
        op.put("tags", new java.util.ArrayList<>(List.of("work")));

        String filename = vault.write(op);
        Map<String, Object> saved = vault.read(filename);

        @SuppressWarnings("unchecked")
        List<String> tags = (List<String>) saved.get("tags");
        assertThat(tags).contains("gtd", "action", "work");
        assertThat(tags).doesNotContain("reference");
    }

    @Test
    void shouldAddReferenceTagAndRemoveActionForReferenceItem(@TempDir Path tempDir) throws Exception {
        VaultService vault = new VaultService(tempDir.toString(), new UndoStack(), true, true, true, true);
        Map<String, Object> op = new java.util.LinkedHashMap<>();
        op.put("bucket", "reference");
        op.put("title", "Useful documentation");
        op.put("tags", new java.util.ArrayList<>(List.of("gtd", "action", "work")));

        String filename = vault.write(op);
        Map<String, Object> saved = vault.read(filename);

        @SuppressWarnings("unchecked")
        List<String> tags = (List<String>) saved.get("tags");
        assertThat(tags).contains("gtd", "reference", "work");
        assertThat(tags).doesNotContain("action");
    }

    @Test
    void shouldHandleNullTagsGracefully(@TempDir Path tempDir) throws Exception {
        VaultService vault = new VaultService(tempDir.toString(), new UndoStack(), true, true, true, true);
        Map<String, Object> op = new java.util.LinkedHashMap<>();
        op.put("bucket", "backlog");
        op.put("title", "Task with no tags");
        op.put("tags", null);

        String filename = vault.write(op);
        Map<String, Object> saved = vault.read(filename);

        @SuppressWarnings("unchecked")
        List<String> tags = (List<String>) saved.get("tags");
        assertThat(tags).contains("gtd", "action");
    }

    @Test
    void shouldMoveBucketAtomicallyAcrossDirectories(@TempDir Path tempDir) throws Exception {
        VaultService vault = new VaultService(tempDir.toString(), new UndoStack(), true, true, true, true);
        Map<String, Object> op = new java.util.LinkedHashMap<>();
        op.put("bucket", "someday");
        op.put("title", "Someday task");
        op.put("tags", new java.util.ArrayList<>(List.of("work")));
        String filename = vault.write(op);

        assertThat(tempDir.resolve("brain/someday").resolve(filename)).exists();
        assertThat(tempDir.resolve("brain/inbox").resolve(filename)).doesNotExist();

        vault.moveBucket(filename, "backlog", null);

        assertThat(tempDir.resolve("brain/inbox").resolve(filename)).exists();
        assertThat(tempDir.resolve("brain/someday").resolve(filename)).doesNotExist();
        assertThat(vault.read(filename).get("bucket")).isEqualTo("backlog");
    }

    @Test
    void shouldSelfHealDuplicateFilenameAcrossInboxAndSomeday(@TempDir Path tempDir) throws Exception {
        Path inbox   = tempDir.resolve("brain/inbox");
        Path someday = tempDir.resolve("brain/someday");
        Files.createDirectories(inbox);
        Files.createDirectories(someday);

        String filename = "20260630-090727-duplicated-task.md";
        Files.writeString(inbox.resolve(filename), """
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

        VaultService vault = new VaultService(tempDir.toString(), new UndoStack(), true, true, true, true);

        assertThat(inbox.resolve(filename)).exists();
        assertThat(someday.resolve(filename)).doesNotExist();
        assertThat(vault.read(filename).get("bucket")).isEqualTo("backlog");

        Path quarantined = tempDir.resolve("brain/.archive/duplicates").resolve(filename);
        assertThat(quarantined).exists();
        assertThat(Files.readString(quarantined)).contains("status: dismissed");
    }

    @Test
    void shouldKeepMostRecentlyUpdatedCopyWhenBothMatchTheirOwnDirectory(@TempDir Path tempDir) throws Exception {
        Path inbox   = tempDir.resolve("brain/inbox");
        Path someday = tempDir.resolve("brain/someday");
        Files.createDirectories(inbox);
        Files.createDirectories(someday);

        // Both copies are individually self-consistent (bucket matches the dir they sit in) —
        // the inbox copy is scanned first but is the STALE one; someday has the newer edit.
        String filename = "20260630-tiebreak-both-consistent.md";
        Files.writeString(inbox.resolve(filename), """
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

        new VaultService(tempDir.toString(), new UndoStack(), true, true, true, true);

        assertThat(someday.resolve(filename)).exists();
        assertThat(inbox.resolve(filename)).doesNotExist();
        assertThat(tempDir.resolve("brain/.archive/duplicates").resolve(filename)).exists();
    }

    @Test
    void shouldLeaveOriginUntouchedWhenMoveBucketDestinationAlreadyExists(@TempDir Path tempDir) throws Exception {
        Path inbox   = tempDir.resolve("brain/inbox");
        Path someday = tempDir.resolve("brain/someday");
        Files.createDirectories(inbox);
        Files.createDirectories(someday);

        VaultService vault = new VaultService(tempDir.toString(), new UndoStack(), true, true, true, true);
        Map<String, Object> op = new java.util.LinkedHashMap<>();
        op.put("bucket", "backlog");
        op.put("title", "Conflicting move");
        op.put("tags", new java.util.ArrayList<>(List.of("work")));
        String filename = vault.write(op);
        String originalContent = Files.readString(inbox.resolve(filename));

        // Pre-create a conflicting file at the move destination to force Files.move to throw.
        Files.writeString(someday.resolve(filename), "conflicting content");

        assertThatThrownBy(() -> vault.moveBucket(filename, "someday", null))
            .isInstanceOf(java.io.UncheckedIOException.class);

        assertThat(Files.readString(inbox.resolve(filename))).isEqualTo(originalContent);
    }

    @Test
    void shouldRelocateReferenceBucketFileToResourcesDir(@TempDir Path tempDir) throws Exception {
        Path inbox = tempDir.resolve("brain/inbox");
        Files.createDirectories(inbox);

        String filename = "20260701-000000-misplaced-reference-task.md";
        Files.writeString(inbox.resolve(filename), """
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

        VaultService vault = new VaultService(tempDir.toString(), new UndoStack(), true, true, true, true);

        assertThat(inbox.resolve(filename)).doesNotExist();
        assertThat(tempDir.resolve("brain/resources").resolve(filename)).exists();
        assertThat(vault.read(filename).get("bucket")).isEqualTo("reference");
    }

    @Test
    void shouldRelocateFileWhoseBucketDoesNotMatchItsDirectory(@TempDir Path tempDir) throws Exception {
        Path inbox = tempDir.resolve("brain/inbox");
        Files.createDirectories(inbox);

        String filename = "20260701-000000-misplaced-someday-task.md";
        Files.writeString(inbox.resolve(filename), """
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

        VaultService vault = new VaultService(tempDir.toString(), new UndoStack(), true, true, true, true);

        assertThat(inbox.resolve(filename)).doesNotExist();
        assertThat(tempDir.resolve("brain/someday").resolve(filename)).exists();
        assertThat(vault.read(filename).get("bucket")).isEqualTo("someday");
    }

    @Test
    void shouldNotTouchNonGtdNotesWithoutBucketField(@TempDir Path tempDir) throws Exception {
        Path inbox   = tempDir.resolve("brain/inbox");
        Path someday = tempDir.resolve("brain/someday");
        Files.createDirectories(inbox);
        Files.createDirectories(someday);

        // Per-directory meta index — same filename in both dirs by design, not a duplicate task.
        Files.writeString(inbox.resolve("_index.md"), """
            ---
            type: meta
            title: "Brain / Inbox"
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

        new VaultService(tempDir.toString(), new UndoStack(), true, true, true, true);

        assertThat(inbox.resolve("_index.md")).exists();
        assertThat(someday.resolve("_index.md")).exists();
        assertThat(someday.resolve("some-idea.md")).exists();
        assertThat(inbox.resolve("some-idea.md")).doesNotExist();
    }

    @Test
    void shouldNormalizeTagsOnMoveBucket(@TempDir Path tempDir) throws Exception {
        VaultService vault = new VaultService(tempDir.toString(), new UndoStack(), true, true, true, true);
        Map<String, Object> op = new java.util.LinkedHashMap<>();
        op.put("bucket", "backlog");
        op.put("title", "Move to reference");
        op.put("tags", new java.util.ArrayList<>(List.of("work")));
        String filename = vault.write(op);

        vault.moveBucket(filename, "reference", null);
        Map<String, Object> moved = vault.read(filename);

        @SuppressWarnings("unchecked")
        List<String> tags = (List<String>) moved.get("tags");
        assertThat(tags).contains("gtd", "reference");
        assertThat(tags).doesNotContain("action");
        assertThat(moved.get("type")).isEqualTo("reference");
    }

    @Test
    void shouldCountTagsAcrossAllFiveBuckets(@TempDir Path tempDir) throws Exception {
        VaultService vault = new VaultService(tempDir.toString(), new UndoStack(), true, true, true, true);

        Map<String, Object> today = new java.util.LinkedHashMap<>();
        today.put("bucket", "today");
        today.put("title", "Today task");
        today.put("tags", new java.util.ArrayList<>(List.of("shopping")));
        vault.write(today);

        Map<String, Object> backlog1 = new java.util.LinkedHashMap<>();
        backlog1.put("bucket", "backlog");
        backlog1.put("title", "Backlog task 1");
        backlog1.put("tags", new java.util.ArrayList<>(List.of("shopping")));
        vault.write(backlog1);

        Map<String, Object> backlog2 = new java.util.LinkedHashMap<>();
        backlog2.put("bucket", "backlog");
        backlog2.put("title", "Backlog task 2");
        backlog2.put("tags", new java.util.ArrayList<>(List.of("shopping", "urgent")));
        vault.write(backlog2);

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
        VaultService vault = new VaultService(tempDir.toString(), new UndoStack(), true, true, true, true);

        Map<String, Object> op = new java.util.LinkedHashMap<>();
        op.put("bucket", "waiting");
        op.put("title", "Ask Juan");
        op.put("delegado_a", "Juan");
        String filename = vault.write(op);

        @SuppressWarnings("unchecked")
        List<String> delegados = (List<String>) vault.read(filename).get("delegado_a");
        assertThat(delegados).containsExactly("Juan");

        vault.patchMeta(filename, Map.of("delegado_a", List.of("Juan", "Maria")));

        @SuppressWarnings("unchecked")
        List<String> updated = (List<String>) vault.read(filename).get("delegado_a");
        assertThat(updated).containsExactly("Juan", "Maria");
    }

    @Test
    void shouldDropNullEntriesFromDelegadoAList(@TempDir Path tempDir) throws Exception {
        VaultService vault = new VaultService(tempDir.toString(), new UndoStack(), true, true, true, true);

        Map<String, Object> op = new java.util.LinkedHashMap<>();
        op.put("bucket", "waiting");
        op.put("title", "Ask someone");
        op.put("delegado_a", java.util.Arrays.asList("Juan", null, "  "));
        String filename = vault.write(op);

        @SuppressWarnings("unchecked")
        List<String> delegados = (List<String>) vault.read(filename).get("delegado_a");
        assertThat(delegados).containsExactly("Juan");
    }

    @Test
    void shouldMigrateLegacyScalarDelegadoAToList(@TempDir Path tempDir) throws Exception {
        Path inbox = tempDir.resolve("brain/inbox");
        Files.createDirectories(inbox);

        String filename = "20260701-000000-legacy-delegado.md";
        Files.writeString(inbox.resolve(filename), """
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

        VaultService vault = new VaultService(tempDir.toString(), new UndoStack(), true, true, true, true);

        @SuppressWarnings("unchecked")
        List<String> delegados = (List<String>) vault.read(filename).get("delegado_a");
        assertThat(delegados).containsExactly("Juan");
    }
}
