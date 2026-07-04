package ar.maxi.gtd.util;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class MarkdownSerializerTest {

    @Test
    void shouldRejectArbitraryJavaTypeTagsInFrontmatter() {
        String malicious = """
                ---
                title: !!javax.script.ScriptEngineManager []
                ---

                body
                """;

        Map<String, Object> result = MarkdownSerializer.parse(malicious, "malicious-test");

        // SafeConstructor refuses the !! type tag, falls back to empty frontmatter
        // instead of instantiating an arbitrary Java class.
        assertThat(result).doesNotContainKey("title");
        assertThat(result.get("body")).isEqualTo("body");
    }

    @Test
    void shouldStillParseOrdinaryFrontmatter() {
        String content = """
                ---
                title: Buy milk
                bucket: today
                ---

                Remember the receipt.
                """;

        Map<String, Object> result = MarkdownSerializer.parse(content, "ordinary-test");

        assertThat(result.get("title")).isEqualTo("Buy milk");
        assertThat(result.get("bucket")).isEqualTo("today");
        assertThat(result.get("body")).isEqualTo("Remember the receipt.");
    }
}
