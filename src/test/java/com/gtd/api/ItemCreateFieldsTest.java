package com.gtd.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gtd.service.EventLog;
import com.gtd.service.VaultService;
import com.gtd.util.MarkdownSerializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.InstanceOfAssertFactories.LIST;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end coverage for what POST /api/items is allowed to put in a note's frontmatter.
 *
 * BucketControllerTest mocks VaultService, so it can only assert what the controller forwards.
 * These tests run the controller against a real VaultService on a temp vault and read the file
 * back off disk, which is where the actual risk lives: write() copies any key it doesn't
 * recognize straight into the frontmatter, so "the controller dropped it" and "it never reached
 * the file" are two different claims.
 */
class ItemCreateFieldsTest {

    private static final List<String> AREAS =
        List.of("personal", "trabajo", "salud", "finanzas", "hogar", "aprendizaje");

    private static MockMvc mvcOver(Path tempDir) {
        VaultService vault = new VaultService(
            tempDir.toString(), AREAS, new EventLog(tempDir.toString(), new ObjectMapper()), new ObjectMapper(), true);
        return MockMvcBuilders.standaloneSetup(new BucketController(vault, null)).build();
    }

    /** The single note written to a bucket, parsed back from disk. */
    private static Map<String, Object> onlyNoteIn(Path vaultDir, String bucket) throws Exception {
        try (var files = Files.list(vaultDir.resolve("brain/" + bucket))) {
            List<Path> notes = files.filter(p -> p.toString().endsWith(".md")).toList();
            assertThat(notes).hasSize(1);
            return MarkdownSerializer.parse(Files.readString(notes.get(0)));
        }
    }

    private static void postItem(MockMvc mvc, String json) throws Exception {
        mvc.perform(post("/api/items").contentType(MediaType.APPLICATION_JSON).content(json))
            .andExpect(status().isOk());
    }



    @Test
    void captureFieldsCannotBeSetByHand(@TempDir Path tempDir) throws Exception {
        MockMvc mvc = mvcOver(tempDir);

        // capture_source and confirmed belong to the LLM capture path: a typed task has no
        // originating utterance, and nothing typed by hand should land in the review queue.
        // write() persists both for the classifier, so only CREATABLE_FIELDS keeps them out here.
        postItem(mvc, """
            {"bucket":"backlog","title":"Water the plants",
             "capture_source":"never said this","confirmed":false}
            """);

        assertThat(onlyNoteIn(tempDir, "backlog")).doesNotContainKeys("capture_source", "confirmed");
    }

    @Test
    void everyAllowedFieldStillLands(@TempDir Path tempDir) throws Exception {
        MockMvc mvc = mvcOver(tempDir);

        postItem(mvc, """
            {"bucket":"backlog","title":"Buy paint","body":"Two litres, matte white.",
             "tags":["home","shopping"],"related_people":"Ana","due":"2026-09-01",
             "area":"hogar","project":"repaint-kitchen","location":"hardware store",
             "estimate_minutes":45,"priority":"high"}
            """);

        Map<String, Object> note = onlyNoteIn(tempDir, "backlog");
        assertThat(note)
            .containsEntry("title", "Buy paint")
            .containsEntry("bucket", "backlog")
            .containsEntry("due", "2026-09-01")
            .containsEntry("area", "hogar")
            .containsEntry("project", "repaint-kitchen")
            .containsEntry("location", "hardware store")
            .containsEntry("estimate_minutes", 45)
            .containsEntry("priority", "high")
            .containsEntry("body", "Two litres, matte white.");
        assertThat(note.get("tags")).asInstanceOf(LIST).contains("home", "shopping");
        assertThat(note.get("related_people")).asInstanceOf(LIST).contains("Ana");
    }

    @Test
    void todayNoteStillGetsItsVaultOwnedTodaySince(@TempDir Path tempDir) throws Exception {
        MockMvc mvc = mvcOver(tempDir);

        // today_since is dropped as client input, but the vault sets its own — dropping the field
        // must not stop a today note from getting one.
        postItem(mvc, """
            {"bucket":"today","title":"Call the dentist","today_since":"1999-01-01"}
            """);

        assertThat(onlyNoteIn(tempDir, "today"))
            .hasEntrySatisfying("today_since", v -> assertThat(String.valueOf(v)).doesNotStartWith("1999"));
    }

}
