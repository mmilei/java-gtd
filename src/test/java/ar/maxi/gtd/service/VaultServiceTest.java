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
        new VaultService(tempDir.toString(), new UndoStack());

        assertThat(tempDir.resolve("brain/inbox")).isDirectory();
        assertThat(tempDir.resolve("brain/someday")).isDirectory();
        assertThat(tempDir.resolve("brain/resources")).isDirectory();
    }

    @Test
    void shouldRejectPathTraversalInFilename(@TempDir Path tempDir) {
        VaultService vault = new VaultService(tempDir.toString(), new UndoStack());
        assertThatThrownBy(() -> vault.read("../../../etc/passwd"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid filename");
    }

    @Test
    void shouldRejectFilenameWithoutMdExtension(@TempDir Path tempDir) {
        VaultService vault = new VaultService(tempDir.toString(), new UndoStack());
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
            () -> new VaultService(tempDir.toString(), new UndoStack())
        );
    }

    @Test
    void shouldAddActionTagForNonReferenceItem(@TempDir Path tempDir) throws Exception {
        VaultService vault = new VaultService(tempDir.toString(), new UndoStack());
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
        VaultService vault = new VaultService(tempDir.toString(), new UndoStack());
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
        VaultService vault = new VaultService(tempDir.toString(), new UndoStack());
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
        VaultService vault = new VaultService(tempDir.toString(), new UndoStack());
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

        VaultService vault = new VaultService(tempDir.toString(), new UndoStack());

        assertThat(inbox.resolve(filename)).exists();
        assertThat(someday.resolve(filename)).doesNotExist();
        assertThat(vault.read(filename).get("bucket")).isEqualTo("backlog");
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

        VaultService vault = new VaultService(tempDir.toString(), new UndoStack());

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

        new VaultService(tempDir.toString(), new UndoStack());

        assertThat(inbox.resolve("_index.md")).exists();
        assertThat(someday.resolve("_index.md")).exists();
        assertThat(someday.resolve("some-idea.md")).exists();
        assertThat(inbox.resolve("some-idea.md")).doesNotExist();
    }

    @Test
    void shouldNormalizeTagsOnMoveBucket(@TempDir Path tempDir) throws Exception {
        VaultService vault = new VaultService(tempDir.toString(), new UndoStack());
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
}
