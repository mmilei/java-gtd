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
                Map.of("op", "create", "bucket", "today", "title", "Llamar al médico",
                        "body", "", "due", "", "delegado_a", "", "tags", List.of())
        );
        when(classifier.classifyAll(any(), any())).thenReturn(new ClassifyResult(ops, false));
        when(vault.write(any())).thenReturn("20260625-120000-llamar-al-medico.md");

        mvc.perform(post("/api/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"llamar al médico mañana\"}"))
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
                Map.of("op", "create", "bucket", "someday", "title", "Aprender piano",
                        "body", "", "due", "", "delegado_a", "", "tags", List.of())
        );
        when(classifier.classifyAll(any(), any())).thenReturn(new ClassifyResult(ops, true));
        when(vault.write(any())).thenReturn("20260625-120000-aprender-piano.md");

        mvc.perform(post("/api/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"algún día aprender piano\"}"))
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
                        .content("{\"message\":\"ya hice la tarea\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ops[0].op").value("done"))
                .andExpect(jsonPath("$.ops[0].filed").value(true));
        verify(vault).markDone("20260625-120000-test.md");
    }

    @Test
    void chatNowNotFiled() throws Exception {
        List<Map<String, Object>> ops = List.of(
                Map.of("op", "create", "bucket", "now", "title", "Responder email",
                        "message", "Hacelo ya", "body", "", "due", "", "delegado_a", "", "tags", List.of())
        );
        when(classifier.classifyAll(any(), any())).thenReturn(new ClassifyResult(ops, false));

        mvc.perform(post("/api/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"respondé ese email\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ops[0].filed").value(false))
                .andExpect(jsonPath("$.ops[0].bucket").value("now"));
        verify(vault, never()).write(any());
    }
}
