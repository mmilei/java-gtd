package ar.maxi.gtd.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class VaultServiceTest {

    @Test
    void shouldCreateVaultDirectoriesOnStartup(@TempDir Path tempDir) {
        new VaultService(tempDir.toString());

        assertThat(tempDir.resolve("wiki/gtd/actions")).isDirectory();
        assertThat(tempDir.resolve("wiki/references")).isDirectory();
    }

    @Test
    void shouldNotFailIfDirectoriesAlreadyExist(@TempDir Path tempDir) throws Exception {
        Files.createDirectories(tempDir.resolve("wiki/gtd/actions"));
        Files.createDirectories(tempDir.resolve("wiki/references"));

        org.junit.jupiter.api.Assertions.assertDoesNotThrow(
            () -> new VaultService(tempDir.toString())
        );
    }
}
