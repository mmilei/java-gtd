package ar.maxi.gtd.api;

import ar.maxi.gtd.service.MarkdownifyService;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(BucketController.class)
class BucketControllerTest {

    @Autowired MockMvc mvc;
    @MockBean VaultService vault;
    @MockBean MarkdownifyService markdownify;

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
    void tags() throws Exception {
        when(vault.tagCounts()).thenReturn(Map.of("shopping", Map.of("today", 1, "backlog", 2)));
        mvc.perform(get("/api/tags"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.shopping.today").value(1))
                .andExpect(jsonPath("$.shopping.backlog").value(2));
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
    void markDone() throws Exception {
        mvc.perform(post("/api/items/" + FILE + "/done"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.done").value(true));
        verify(vault).markDone(FILE);
    }

    @Test
    void dismiss() throws Exception {
        mvc.perform(post("/api/items/" + FILE + "/dismiss"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dismissed").value(true));
        verify(vault).dismissItem(FILE);
    }

    @Test
    void replaceBody() throws Exception {
        mvc.perform(put("/api/items/" + FILE + "/body")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\":\"nuevo contenido\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.updated").value(true));
        verify(vault).replaceBody(eq(FILE), eq("nuevo contenido"));
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
        verify(vault).moveBucket(eq(FILE), eq("backlog"), isNull());
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
