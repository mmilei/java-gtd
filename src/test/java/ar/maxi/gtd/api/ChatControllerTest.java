package ar.maxi.gtd.api;

import ar.maxi.gtd.service.ClassifierService;
import ar.maxi.gtd.service.ClassifierService.ClassifyResult;
import ar.maxi.gtd.service.VaultService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(ChatController.class)
class ChatControllerTest {

    @Autowired MockMvc mvc;
    @MockBean ClassifierService classifier;
    @MockBean VaultService vault;

    @BeforeEach
    void setUp() {
        when(vault.listAllFlat()).thenReturn(List.of());
    }

    @Test
    void chatCreate() throws Exception {
        List<Map<String, Object>> ops = List.of(
                Map.of("op", "create", "bucket", "today", "title", "Call the doctor",
                        "body", "", "due", "", "delegado_a", "", "tags", List.of())
        );
        when(classifier.classifyAll(any(), any())).thenReturn(new ClassifyResult(ops, false));
        when(vault.write(any())).thenReturn("20260625-120000-call-the-doctor.md");

        mvc.perform(post("/api/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"call the doctor today\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fallback").value(false))
                .andExpect(jsonPath("$.ops[0].op").value("create"))
                .andExpect(jsonPath("$.ops[0].filed").value(true));
    }

    @Test
    void chatMissingMessage() throws Exception {
        mvc.perform(post("/api/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void chatBlankMessage() throws Exception {
        mvc.perform(post("/api/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"   \"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void chatWithFallback() throws Exception {
        List<Map<String, Object>> ops = List.of(
                Map.of("op", "create", "bucket", "someday", "title", "Learn piano",
                        "body", "", "due", "", "delegado_a", "", "tags", List.of())
        );
        when(classifier.classifyAll(any(), any())).thenReturn(new ClassifyResult(ops, true));
        when(vault.write(any())).thenReturn("20260625-120000-learn-piano.md");

        mvc.perform(post("/api/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"someday learn piano\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fallback").value(true));
    }

    @Test
    void chatDoneOp() throws Exception {
        List<Map<String, Object>> ops = List.of(
                Map.of("op", "done", "target_file", "20260625-120000-test.md")
        );
        when(classifier.classifyAll(any(), any())).thenReturn(new ClassifyResult(ops, false));

        mvc.perform(post("/api/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"done with the task\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ops[0].op").value("done"))
                .andExpect(jsonPath("$.ops[0].filed").value(true));
        verify(vault).markDone("20260625-120000-test.md");
    }

    @Test
    void chatEditRequiresConfirmation() throws Exception {
        List<Map<String, Object>> ops = List.of(
                Map.of("op", "edit", "target_file", "20260625-120000-test.md", "new_body", "Updated content")
        );
        when(classifier.classifyAll(any(), any())).thenReturn(new ClassifyResult(ops, false));
        when(vault.read("20260625-120000-test.md")).thenReturn(
                Map.of("title", "Test task", "body", "Original content", "bucket", "backlog")
        );

        mvc.perform(post("/api/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"edit the test task\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ops[0].op").value("edit"))
                .andExpect(jsonPath("$.ops[0].filed").value(false))
                .andExpect(jsonPath("$.ops[0].requires_confirmation").value(true))
                .andExpect(jsonPath("$.ops[0].current_body").value("Original content"))
                .andExpect(jsonPath("$.ops[0].proposed_body").value("Updated content"));
        verify(vault, never()).replaceBody(any(), any());
        verify(vault).read("20260625-120000-test.md");
    }

    @Test
    void chatEditNoMatchReturnsError() throws Exception {
        List<Map<String, Object>> ops = List.of(
                Map.of("op", "edit")
        );
        when(classifier.classifyAll(any(), any())).thenReturn(new ClassifyResult(ops, false));

        mvc.perform(post("/api/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"edit something\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ops[0].op").value("edit"))
                .andExpect(jsonPath("$.ops[0].filed").value(false))
                .andExpect(jsonPath("$.ops[0].error").exists());
        verify(vault, never()).replaceBody(any(), any());
    }

    @Test
    void chatNowNotFiled() throws Exception {
        List<Map<String, Object>> ops = List.of(
                Map.of("op", "create", "bucket", "now", "title", "Reply to email",
                        "message", "Do it now", "body", "", "due", "", "delegado_a", "", "tags", List.of())
        );
        when(classifier.classifyAll(any(), any())).thenReturn(new ClassifyResult(ops, false));

        mvc.perform(post("/api/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"reply to that email now\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ops[0].filed").value(false))
                .andExpect(jsonPath("$.ops[0].bucket").value("now"));
        verify(vault, never()).write(any());
    }
}
