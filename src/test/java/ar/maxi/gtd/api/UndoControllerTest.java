package ar.maxi.gtd.api;

import ar.maxi.gtd.service.UndoStack;
import ar.maxi.gtd.service.UndoStack.UndoEntry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(UndoController.class)
class UndoControllerTest {

    @Autowired MockMvc mvc;
    @MockBean UndoStack undoStack;

    @Test
    void undoEmptyStack() throws Exception {
        when(undoStack.pop()).thenReturn(Optional.empty());

        mvc.perform(post("/api/undo"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.undone").value(false))
                .andExpect(jsonPath("$.reason").value("stack vacío"));
    }

    @Test
    void undoRestoresFile(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("20260625-120000-test.md");
        String previousContent = "---\ntitle: Test\n---\n";
        Files.writeString(file, "contenido actual");

        when(undoStack.pop()).thenReturn(Optional.of(
                new UndoEntry("20260625-120000-test.md", file, previousContent)
        ));

        mvc.perform(post("/api/undo"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.undone").value(true))
                .andExpect(jsonPath("$.file").value("20260625-120000-test.md"));

        assertThat(Files.readString(file)).isEqualTo(previousContent);
    }

    @Test
    void undoDeletesCreatedFile(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("20260625-120000-nuevo.md");
        Files.writeString(file, "---\ntitle: Nuevo\n---\n");

        when(undoStack.pop()).thenReturn(Optional.of(
                new UndoEntry("20260625-120000-nuevo.md", file, null)
        ));

        mvc.perform(post("/api/undo"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.undone").value(true));

        assertThat(Files.exists(file)).isFalse();
    }
}
