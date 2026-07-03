package ar.maxi.gtd.api;

import ar.maxi.gtd.service.Event;
import ar.maxi.gtd.service.EventLog;
import ar.maxi.gtd.service.VaultService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(UndoController.class)
class UndoControllerTest {

    @Autowired MockMvc mvc;
    @MockBean EventLog eventLog;
    @MockBean VaultService vault;

    private static Event mutationEvent(String op, String pathBefore, String pathAfter, String previousContent) {
        return new Event("e-000001", "2026-07-03T12:00:00Z", "user", "mutation", op,
            "20260625-120000-test.md", "Test", pathBefore, pathAfter, previousContent, "none", null, null);
    }

    @Test
    void undoEmptyStack() throws Exception {
        when(eventLog.nextUndoable()).thenReturn(Optional.empty());

        mvc.perform(post("/api/undo"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.undone").value(false))
                .andExpect(jsonPath("$.reason").value("empty stack"));

        verify(vault, never()).undoEvent(any());
    }

    @Test
    void undoRestoresPreviousContentAndMovesBackToPathBefore() throws Exception {
        Event e = mutationEvent("move", "/vault/brain/backlog/test.md", "/vault/brain/today/test.md", "---\ntitle: Test\n---\n");
        when(eventLog.nextUndoable()).thenReturn(Optional.of(e));

        mvc.perform(post("/api/undo"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.undone").value(true))
                .andExpect(jsonPath("$.file").value("20260625-120000-test.md"))
                .andExpect(jsonPath("$.op").value("move"));

        verify(vault).undoEvent(e);
        verify(eventLog).append(argThat(undo -> "undo".equals(undo.kind()) && "e-000001".equals(undo.undoes())));
    }

    @Test
    void undoOfCreateDeletesFileAndLogsUndoEvent() throws Exception {
        Event e = mutationEvent("create", null, "/vault/brain/backlog/test.md", null);
        when(eventLog.nextUndoable()).thenReturn(Optional.of(e));

        mvc.perform(post("/api/undo"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.undone").value(true))
                .andExpect(jsonPath("$.restored_to").value("deleted"));

        verify(vault).undoEvent(e);
        verify(eventLog).append(argThat(undo -> "undo".equals(undo.kind())));
    }

    @Test
    void undoReportsErrorWithoutLoggingUndoEventWhenVaultThrows() throws Exception {
        Event e = mutationEvent("move", "/vault/brain/backlog/test.md", "/vault/brain/today/test.md", "content");
        when(eventLog.nextUndoable()).thenReturn(Optional.of(e));
        doThrow(new RuntimeException("boom")).when(vault).undoEvent(e);

        mvc.perform(post("/api/undo"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.undone").value(false));

        verify(eventLog, never()).append(any());
    }

    @Test
    void undoStackReturnsPeekWithoutMutating() throws Exception {
        Event e = mutationEvent("move", "/vault/brain/backlog/test.md", "/vault/brain/today/test.md", "content");
        when(eventLog.undoableStack()).thenReturn(List.of(e));

        mvc.perform(get("/api/undo/stack"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].file").value("20260625-120000-test.md"))
                .andExpect(jsonPath("$[0].op").value("move"));

        verify(vault, never()).undoEvent(any());
        verify(eventLog, never()).append(any());
    }
}
