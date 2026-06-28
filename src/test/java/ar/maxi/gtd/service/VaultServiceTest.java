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
                .hasMessageContaining("Filename inválido");
    }

    @Test
    void shouldRejectFilenameWithoutMdExtension(@TempDir Path tempDir) {
        VaultService vault = new VaultService(tempDir.toString(), new UndoStack());
        assertThatThrownBy(() -> vault.read("noextension"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Filename inválido");
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
        op.put("title", "Hacer algo");
        op.put("tags", new java.util.ArrayList<>(List.of("trabajo")));

        String filename = vault.write(op);
        Map<String, Object> saved = vault.read(filename);

        @SuppressWarnings("unchecked")
        List<String> tags = (List<String>) saved.get("tags");
        assertThat(tags).contains("gtd", "action", "trabajo");
        assertThat(tags).doesNotContain("reference");
    }

    @Test
    void shouldAddReferenceTagAndRemoveActionForReferenceItem(@TempDir Path tempDir) throws Exception {
        VaultService vault = new VaultService(tempDir.toString(), new UndoStack());
        Map<String, Object> op = new java.util.LinkedHashMap<>();
        op.put("bucket", "reference");
        op.put("title", "Documentación útil");
        op.put("tags", new java.util.ArrayList<>(List.of("gtd", "action", "trabajo")));

        String filename = vault.write(op);
        Map<String, Object> saved = vault.read(filename);

        @SuppressWarnings("unchecked")
        List<String> tags = (List<String>) saved.get("tags");
        assertThat(tags).contains("gtd", "reference", "trabajo");
        assertThat(tags).doesNotContain("action");
    }

    @Test
    void shouldHandleNullTagsGracefully(@TempDir Path tempDir) throws Exception {
        VaultService vault = new VaultService(tempDir.toString(), new UndoStack());
        Map<String, Object> op = new java.util.LinkedHashMap<>();
        op.put("bucket", "backlog");
        op.put("title", "Tarea sin tags");
        op.put("tags", null);

        String filename = vault.write(op);
        Map<String, Object> saved = vault.read(filename);

        @SuppressWarnings("unchecked")
        List<String> tags = (List<String>) saved.get("tags");
        assertThat(tags).contains("gtd", "action");
    }

    @Test
    void shouldNormalizeTagsOnMoveBucket(@TempDir Path tempDir) throws Exception {
        VaultService vault = new VaultService(tempDir.toString(), new UndoStack());
        Map<String, Object> op = new java.util.LinkedHashMap<>();
        op.put("bucket", "backlog");
        op.put("title", "Mover a referencia");
        op.put("tags", new java.util.ArrayList<>(List.of("trabajo")));
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
