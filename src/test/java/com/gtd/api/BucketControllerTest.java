package com.gtd.api;

import com.gtd.service.Actor;
import com.gtd.service.LlmProviderService;
import com.gtd.service.MarkdownifyService;
import com.gtd.service.VaultService;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(BucketController.class)
class BucketControllerTest {

    @Autowired MockMvc mvc;
    @MockBean VaultService vault;
    @MockBean MarkdownifyService markdownify;
    // required by GlobalExceptionHandler, which the @WebMvcTest slice also instantiates
    @MockBean LlmProviderService llmProviders;

    private static final String FILE = "20260625-120000-test.md";

    @BeforeEach
    void setUp() {
        when(vault.listAll()).thenReturn(Map.of("today", List.of(), "backlog", List.of()));
        when(vault.list(any())).thenReturn(List.of());
        when(vault.stats()).thenReturn(Map.of("counts", Map.of(), "total", 0));
        when(vault.history(anyInt())).thenReturn(List.of());
        when(vault.read(FILE)).thenReturn(Map.of("title", "Test", "bucket", "today", "status", "open"));
    }

    @Test
    void allBuckets() throws Exception {
        mvc.perform(get("/api/buckets"))
                .andExpect(status().isOk());
    }

    @Test
    void byBucket() throws Exception {
        mvc.perform(get("/api/buckets/today"))
                .andExpect(status().isOk());
    }

    @Test
    void today() throws Exception {
        mvc.perform(get("/api/today"))
                .andExpect(status().isOk());
    }

    @Test
    void createPersonReturnsTheNameTheVaultFiledItUnder() throws Exception {
        when(vault.createPerson("Quinn")).thenReturn("Quinn");
        mvc.perform(post("/api/people").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Quinn\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.created").value(true))
                .andExpect(jsonPath("$.name").value("Quinn"));
    }

    @Test
    void createPersonSurfacesTheVaultRejectionAsABadRequest() throws Exception {
        when(vault.createPerson("Quinn")).thenThrow(new IllegalArgumentException("Person already exists: Quinn"));
        mvc.perform(post("/api/people").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Quinn\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Person already exists: Quinn"));
    }

    @Test
    void areasReturnsConfiguredVocabularyInOrder() throws Exception {
        when(vault.validAreas()).thenReturn(List.of("personal", "friends", "exercise"));
        mvc.perform(get("/api/areas"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0]").value("personal"))
                .andExpect(jsonPath("$[1]").value("friends"))
                .andExpect(jsonPath("$[2]").value("exercise"));
    }

    @Test
    void tags() throws Exception {
        when(vault.tagCounts()).thenReturn(Map.of("shopping", Map.of("today", 1, "backlog", 2)));
        mvc.perform(get("/api/tags"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.shopping.today").value(1))
                .andExpect(jsonPath("$.shopping.backlog").value(2));
    }

    @Test
    void unconfirmed() throws Exception {
        when(vault.listUnconfirmed()).thenReturn(List.of(
                Map.of("file", FILE, "title", "Low confidence", "bucket", "backlog", "confirmed", false)));
        mvc.perform(get("/api/unconfirmed"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].file").value(FILE))
                .andExpect(jsonPath("$[0].confirmed").value(false));
        verify(vault).listUnconfirmed();
    }

    @Test
    void getItemFound() throws Exception {
        mvc.perform(get("/api/items/" + FILE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bucket").value("today"));
    }

    @Test
    void getItemNotFound() throws Exception {
        when(vault.read("missing.md")).thenThrow(new IllegalArgumentException("not found"));
        mvc.perform(get("/api/items/missing.md"))
                .andExpect(status().isNotFound());
    }

    @Test
    void createItem() throws Exception {
        when(vault.write(anyMap(), eq(Actor.USER))).thenReturn(FILE);
        mvc.perform(post("/api/items")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"bucket\":\"backlog\",\"title\":\"Water the plants\",\"tags\":[\"home\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.filed").value(true))
                .andExpect(jsonPath("$.file").value(FILE))
                .andExpect(jsonPath("$.bucket").value("backlog"));
        verify(vault).write(argThat(item ->
                "backlog".equals(item.get("bucket")) && "Water the plants".equals(item.get("title"))), eq(Actor.USER));
    }

    @Test
    void createItemDropsUnknownAndLifecycleFields() throws Exception {
        when(vault.write(anyMap(), eq(Actor.USER))).thenReturn(FILE);
        mvc.perform(post("/api/items")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"bucket\":\"backlog\",\"title\":\"Water the plants\","
                                + "\"status\":\"done\",\"done_date\":\"2020-01-01\",\"whatever\":\"junk\"}"))
                .andExpect(status().isOk());
        // write() copies unrecognized keys straight into frontmatter, so the controller must not
        // forward anything the client isn't allowed to set.
        verify(vault).write(argThat(item ->
                !item.containsKey("status")
                        && !item.containsKey("done_date")
                        && !item.containsKey("whatever")
                        && "Water the plants".equals(item.get("title"))), eq(Actor.USER));
    }

    @Test
    void createItemMissingBucket() throws Exception {
        mvc.perform(post("/api/items")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Water the plants\"}"))
                .andExpect(status().isBadRequest());
        verify(vault, never()).write(anyMap(), any());
    }

    @Test
    void createItemInvalidBucket() throws Exception {
        mvc.perform(post("/api/items")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"bucket\":\"discard\",\"title\":\"nope\"}"))
                .andExpect(status().isBadRequest());
        verify(vault, never()).write(anyMap(), any());
    }

    @Test
    void createItemBlankTitle() throws Exception {
        mvc.perform(post("/api/items")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"bucket\":\"backlog\",\"title\":\"  \"}"))
                .andExpect(status().isBadRequest());
        verify(vault, never()).write(anyMap(), any());
    }

    @Test
    void markDone() throws Exception {
        mvc.perform(post("/api/items/" + FILE + "/done"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.done").value(true));
        verify(vault).markDone(FILE, Actor.USER);
    }

    /** The close still succeeds — the open dependencies ride along as an extra key for the client to warn with. */
    @Test
    void markDoneReportsDependenciesThatWereStillOpen() throws Exception {
        when(vault.markDone(FILE, Actor.USER))
            .thenReturn(List.of(Map.of("file", "20260625-110000-blocker.md", "title", "Order the tiles")));

        mvc.perform(post("/api/items/" + FILE + "/done"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.done").value(true))
                .andExpect(jsonPath("$.open_dependencies[0].title").value("Order the tiles"));
    }

    @Test
    void confirmItem() throws Exception {
        mvc.perform(post("/api/items/" + FILE + "/confirm"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.confirmed").value(true));
        verify(vault).patchMeta(FILE, Map.of("confirmed", true), Actor.USER);
    }

    @Test
    void dismiss() throws Exception {
        mvc.perform(post("/api/items/" + FILE + "/dismiss"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dismissed").value(true));
        verify(vault).dismissItem(FILE, Actor.USER);
    }

    @Test
    void replaceBody() throws Exception {
        mvc.perform(put("/api/items/" + FILE + "/body")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\":\"nuevo contenido\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.updated").value(true));
        verify(vault).replaceBody(eq(FILE), eq("nuevo contenido"), eq(Actor.USER));
    }

    @Test
    void replaceBodyMissingField() throws Exception {
        mvc.perform(put("/api/items/" + FILE + "/body")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void moveItem() throws Exception {
        mvc.perform(post("/api/items/" + FILE + "/move")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"bucket\":\"backlog\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.moved").value(true));
        verify(vault).moveBucket(eq(FILE), eq("backlog"), isNull(), eq(Actor.USER));
    }

    @Test
    void moveItemMissingBucket() throws Exception {
        mvc.perform(post("/api/items/" + FILE + "/move")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void stats() throws Exception {
        mvc.perform(get("/api/stats"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(0));
    }

    @Test
    void history() throws Exception {
        mvc.perform(get("/api/history"))
                .andExpect(status().isOk());
    }

    @Test
    void historyWithLimit() throws Exception {
        mvc.perform(get("/api/history?limit=5"))
                .andExpect(status().isOk());
        verify(vault).history(5);
    }
}
