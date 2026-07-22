package com.gtd.api;

import com.gtd.service.Actor;
import com.gtd.service.ChatMessage;
import com.gtd.service.ClassifierService;
import com.gtd.service.ClassifierService.ClassifyResult;
import com.gtd.service.EventLog;
import com.gtd.service.LlmProviderService;
import com.gtd.service.TranscriptLog;
import com.gtd.service.VaultService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(ChatController.class)
class ChatControllerTest {

    @Autowired MockMvc mvc;
    @MockBean ClassifierService classifier;
    @MockBean VaultService vault;
    @MockBean TranscriptLog transcript;
    @MockBean EventLog eventLog;
    // required by GlobalExceptionHandler, which the @WebMvcTest slice also instantiates
    @MockBean LlmProviderService llmProviders;

    @BeforeEach
    void setUp() {
        when(vault.listAllFlat()).thenReturn(List.of());
        when(transcript.append(anyString(), anyString(), anyBoolean()))
            .thenReturn(new ChatMessage("t-000001", "2026-07-03T12:00:00Z", "assistant", "[]", false));
    }

    @Test
    void chatCreate() throws Exception {
        List<Map<String, Object>> ops = List.of(
                Map.of("op", "create", "bucket", "today", "title", "Call the doctor",
                        "body", "", "due", "", "related_people", "", "tags", List.of())
        );
        when(classifier.classifyAll(any(), any())).thenReturn(new ClassifyResult(ops, false));
        when(vault.write(any(), any())).thenReturn("20260625-120000-call-the-doctor.md");

        mvc.perform(post("/api/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"call the doctor today\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fallback").value(false))
                .andExpect(jsonPath("$.ops[0].op").value("create"))
                .andExpect(jsonPath("$.ops[0].filed").value(true))
                .andExpect(jsonPath("$.ops[0].confirmed").value(true));
        verify(vault).write(argThat(m -> !m.containsKey("confirmed")), eq(Actor.LLM));
    }

    @Test
    void chatCreatePersistsOriginalMessageAsCaptureSource() throws Exception {
        List<Map<String, Object>> ops = List.of(
                Map.of("op", "create", "bucket", "today", "title", "Call the doctor",
                        "body", "", "due", "", "related_people", "", "tags", List.of())
        );
        when(classifier.classifyAll(any(), any())).thenReturn(new ClassifyResult(ops, false));
        when(vault.write(any(), any())).thenReturn("20260625-120000-call-the-doctor.md");

        mvc.perform(post("/api/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"call the doctor today\"}"))
                .andExpect(status().isOk());
        verify(vault).write(argThat(m -> "call the doctor today".equals(m.get("capture_source"))), eq(Actor.LLM));
    }

    @Test
    void chatLlmProviderErrorIsClassifiedByGlobalHandler() throws Exception {
        when(classifier.classifyAll(any(), any())).thenThrow(new NonTransientAiException(
                "429 - {\"error\":{\"message\":\"Rate limit reached\",\"code\":\"rate_limit_exceeded\"}}"));
        when(llmProviders.describeAll()).thenReturn(Map.of("active", "GROQ", "providers", List.of()));

        mvc.perform(post("/api/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"call the doctor today\"}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error").value("RATE_LIMIT"))
                .andExpect(jsonPath("$.message").isNotEmpty())
                .andExpect(jsonPath("$.providers.active").value("GROQ"));
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
                        "body", "", "due", "", "related_people", "", "tags", List.of())
        );
        when(classifier.classifyAll(any(), any())).thenReturn(new ClassifyResult(ops, true));
        when(vault.write(any(), any())).thenReturn("20260625-120000-learn-piano.md");

        mvc.perform(post("/api/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"someday learn piano\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fallback").value(true))
                .andExpect(jsonPath("$.ops[0].confirmed").value(false));
        verify(vault).write(argThat(m -> Boolean.FALSE.equals(m.get("confirmed"))), eq(Actor.LLM));
    }

    @Test
    void chatDoneOp() throws Exception {
        List<Map<String, Object>> ops = List.of(
                Map.of("op", "done", "target_file", "20260625-120000-test.md")
        );
        when(classifier.classifyAll(any(), any())).thenReturn(new ClassifyResult(ops, false));
        when(vault.read("20260625-120000-test.md")).thenReturn(
                Map.of("title", "Test task", "body", "Some content", "bucket", "today")
        );

        mvc.perform(post("/api/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"done with the task\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ops[0].op").value("done"))
                .andExpect(jsonPath("$.ops[0].filed").value(true))
                .andExpect(jsonPath("$.ops[0].title").value("Test task"));
        verify(vault).markDone("20260625-120000-test.md", Actor.LLM);
    }

    @Test
    void chatMoveOp() throws Exception {
        List<Map<String, Object>> ops = List.of(
                Map.of("op", "move", "target_file", "20260625-120000-test.md", "new_bucket", "today")
        );
        when(classifier.classifyAll(any(), any())).thenReturn(new ClassifyResult(ops, false));
        when(vault.read("20260625-120000-test.md")).thenReturn(
                Map.of("title", "Test task", "body", "Some content", "bucket", "backlog")
        );

        mvc.perform(post("/api/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"move the test task to today\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ops[0].op").value("move"))
                .andExpect(jsonPath("$.ops[0].filed").value(true))
                .andExpect(jsonPath("$.ops[0].new_bucket").value("today"))
                .andExpect(jsonPath("$.ops[0].title").value("Test task"));
        verify(vault).moveBucket("20260625-120000-test.md", "today", null, Actor.LLM);
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
        verify(vault, never()).replaceBody(any(), any(), any());
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
        verify(vault, never()).replaceBody(any(), any(), any());
    }

    @Test
    void chatUpdateRequiresConfirmation() throws Exception {
        List<Map<String, Object>> ops = List.of(
                Map.of("op", "update", "target_file", "20260625-120000-test.md", "append", "New line")
        );
        when(classifier.classifyAll(any(), any())).thenReturn(new ClassifyResult(ops, false));
        when(vault.read("20260625-120000-test.md")).thenReturn(
                Map.of("title", "Test task", "body", "Existing content", "bucket", "backlog")
        );

        mvc.perform(post("/api/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"add to the test task: New line\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ops[0].op").value("update"))
                .andExpect(jsonPath("$.ops[0].filed").value(false))
                .andExpect(jsonPath("$.ops[0].requires_confirmation").value(true))
                .andExpect(jsonPath("$.ops[0].current_body").value("Existing content"))
                .andExpect(jsonPath("$.ops[0].proposed_body").value("Existing content\nNew line"));
        verify(vault).read("20260625-120000-test.md");
    }

    @Test
    void chatDismissRequiresConfirmation() throws Exception {
        List<Map<String, Object>> ops = List.of(
                Map.of("op", "dismiss", "target_file", "20260625-120000-test.md")
        );
        when(classifier.classifyAll(any(), any())).thenReturn(new ClassifyResult(ops, false));
        when(vault.read("20260625-120000-test.md")).thenReturn(
                Map.of("title", "Test task", "body", "Some content", "bucket", "backlog")
        );

        mvc.perform(post("/api/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"discard the test task\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ops[0].op").value("dismiss"))
                .andExpect(jsonPath("$.ops[0].filed").value(false))
                .andExpect(jsonPath("$.ops[0].requires_confirmation").value(true))
                .andExpect(jsonPath("$.ops[0].title").value("Test task"));
        verify(vault, never()).dismissItem(any(), any());
        verify(vault).read("20260625-120000-test.md");
    }

    @Test
    void chatNowNotFiled() throws Exception {
        List<Map<String, Object>> ops = List.of(
                Map.of("op", "create", "bucket", "now", "title", "Reply to email",
                        "message", "Do it now", "body", "", "due", "", "related_people", "", "tags", List.of())
        );
        when(classifier.classifyAll(any(), any())).thenReturn(new ClassifyResult(ops, false));

        mvc.perform(post("/api/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"reply to that email now\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ops[0].filed").value(false))
                .andExpect(jsonPath("$.ops[0].bucket").value("now"));
        verify(vault, never()).write(any(), any());
    }

    @Test
    void chatDismissAttachesChatRefForConfirmFlow() throws Exception {
        List<Map<String, Object>> ops = List.of(
                Map.of("op", "dismiss", "target_file", "20260625-120000-test.md")
        );
        when(classifier.classifyAll(any(), any())).thenReturn(new ClassifyResult(ops, false));
        when(vault.read("20260625-120000-test.md")).thenReturn(
                Map.of("title", "Test task", "body", "Some content", "bucket", "backlog")
        );

        mvc.perform(post("/api/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"discard the test task\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ops[0].chat_ref").value("t-000001"));
    }

    @Test
    void confirmDismissAppliesMutationWithLlmActorAndConfirmedFlag() throws Exception {
        mvc.perform(post("/api/chat/confirm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"target_file\":\"20260625-120000-test.md\",\"op\":\"dismiss\",\"chat_ref\":\"t-000001\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.confirmed").value(true));

        verify(vault).dismissItem("20260625-120000-test.md", Actor.LLM, "confirmed", "t-000001");
    }

    @Test
    void confirmEditAppliesProposedBody() throws Exception {
        mvc.perform(post("/api/chat/confirm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"target_file\":\"20260625-120000-test.md\",\"op\":\"edit\",\"proposed_body\":\"New body\",\"chat_ref\":\"t-000001\"}"))
                .andExpect(status().isOk());

        verify(vault).replaceBody("20260625-120000-test.md", "New body", Actor.LLM, "confirmed", "t-000001");
    }

    @Test
    void confirmRejectsUnsupportedOp() throws Exception {
        mvc.perform(post("/api/chat/confirm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"target_file\":\"20260625-120000-test.md\",\"op\":\"move\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void historyReturnsUserAndAssistantEntriesInterleaved() throws Exception {
        when(transcript.tail(50)).thenReturn(List.of(
            new ChatMessage("t-000001", "2026-07-03T12:00:00Z", "user", "call the doctor", false),
            new ChatMessage("t-000002", "2026-07-03T12:00:01Z", "assistant",
                "[{\"op\":\"create\",\"filed\":true,\"title\":\"Call the doctor\"}]", false)
        ));

        mvc.perform(get("/api/chat/history"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].role").value("user"))
                .andExpect(jsonPath("$[0].text").value("call the doctor"))
                .andExpect(jsonPath("$[1].role").value("assistant"))
                .andExpect(jsonPath("$[1].ops[0].title").value("Call the doctor"));
    }

    @Test
    void historyMarksPendingConfirmationOpAsUnresolvedUntilAMatchingConfirmedEventExists() throws Exception {
        when(transcript.tail(50)).thenReturn(List.of(
            new ChatMessage("t-000002", "2026-07-03T12:00:01Z", "assistant",
                "[{\"op\":\"dismiss\",\"filed\":false,\"requires_confirmation\":true,\"target_file\":\"20260625-120000-test.md\"}]", false)
        ));
        when(eventLog.tail(0, null, null)).thenReturn(List.of());

        mvc.perform(get("/api/chat/history"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].ops[0].resolved").value(false));
    }
}
