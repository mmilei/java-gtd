package ar.maxi.gtd.service;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ClassifierServiceTest {

    private static final List<Map<String, Object>> OPEN_TASKS = List.of(
            Map.of("file", "20260601-1-buy-bread.md", "title", "Buy bread", "bucket", "backlog"),
            Map.of("file", "20260601-2-buy-milk.md", "title", "Buy milk", "bucket", "backlog"),
            Map.of("file", "20260601-3-old-idea.md", "title", "Old idea", "bucket", "someday")
    );

    private static Map<String, Object> op(String targetTitle) {
        Map<String, Object> op = new HashMap<>();
        op.put("op", "done");
        op.put("target_title", targetTitle);
        return op;
    }

    @Test
    void exactTitleMatchResolvesToRealFile() {
        String resolved = ClassifierService.resolveTargetFile(op("Buy bread"), OPEN_TASKS);
        assertThat(resolved).isEqualTo("20260601-1-buy-bread.md");
    }

    @Test
    void matchIsCaseInsensitiveAndTrimmed() {
        String resolved = ClassifierService.resolveTargetFile(op("  buy BREAD  "), OPEN_TASKS);
        assertThat(resolved).isEqualTo("20260601-1-buy-bread.md");
    }

    @Test
    void matchIgnoresAccents() {
        // real bug hit during manual verification: user types "verificacion" (no accent),
        // task title on disk is "Verificación" (LLM-generated, with accent)
        List<Map<String, Object>> tasks = List.of(
                Map.of("file", "20260701-1-verificacion.md", "title", "Tarjeta verificación bugfix", "bucket", "backlog")
        );
        String resolved = ClassifierService.resolveTargetFile(op("tarjeta verificacion bugfix"), tasks);
        assertThat(resolved).isEqualTo("20260701-1-verificacion.md");
    }

    @Test
    void uniqueSubstringMatchResolves() {
        String resolved = ClassifierService.resolveTargetFile(op("old idea"), OPEN_TASKS);
        assertThat(resolved).isEqualTo("20260601-3-old-idea.md");
    }

    @Test
    void ambiguousMatchReturnsNull() {
        // "buy" matches both "Buy bread" and "Buy milk" as a substring — must not guess
        String resolved = ClassifierService.resolveTargetFile(op("buy"), OPEN_TASKS);
        assertThat(resolved).isNull();
    }

    @Test
    void blankOrNullTitleCandidatesNeverFalsePositiveMatch() {
        // real risk: "" is a substring of everything in Java, so a task with a blank/missing
        // title would otherwise match any target_title via the partial-match fallback — exactly
        // the kind of false-positive mistarget this method exists to prevent. This vault has a
        // documented malformed-frontmatter bug, so blank/null titles are a real occurrence.
        Map<String, Object> nullTitleTask = new HashMap<>();
        nullTitleTask.put("file", "20260601-8-placeholder.md");
        nullTitleTask.put("title", null);
        nullTitleTask.put("bucket", "backlog");

        List<Map<String, Object>> tasksWithBadTitles = List.of(
                Map.of("file", "20260601-9-blank-title.md", "title", "", "bucket", "backlog"),
                nullTitleTask
        );
        assertThat(ClassifierService.resolveTargetFile(op("something nobody typed"), tasksWithBadTitles)).isNull();
    }

    @Test
    void noMatchReturnsNull() {
        String resolved = ClassifierService.resolveTargetFile(op("something totally unrelated"), OPEN_TASKS);
        assertThat(resolved).isNull();
    }

    @Test
    void nullTargetTitleReturnsNull() {
        Map<String, Object> op = new HashMap<>();
        op.put("op", "done");
        String resolved = ClassifierService.resolveTargetFile(op, OPEN_TASKS);
        assertThat(resolved).isNull();
    }

    @Test
    void templateResourcePathUsesCustomWhenModeIsCustom() {
        assertThat(ClassifierService.templateResourcePath("custom", false))
                .isEqualTo("prompts/classifier_custom.st");
        assertThat(ClassifierService.templateResourcePath("custom", true))
                .isEqualTo("prompts/classifier-fallback-custom.st");
    }

    @Test
    void templateResourcePathDefaultsToSampleForSampleModeOrUnknownValue() {
        assertThat(ClassifierService.templateResourcePath("sample", false))
                .isEqualTo("prompts/classifier.st");
        assertThat(ClassifierService.templateResourcePath("sample", true))
                .isEqualTo("prompts/classifier-fallback.st");

        // unrecognized/null values must never silently fall through to the gitignored
        // custom templates, which don't exist in CI or a public clone
        assertThat(ClassifierService.templateResourcePath("something-else", false))
                .isEqualTo("prompts/classifier.st");
        assertThat(ClassifierService.templateResourcePath(null, false))
                .isEqualTo("prompts/classifier.st");
    }

    @Test
    void legacyTargetFileTrustedOnlyWhenVerifiedPresent() {
        Map<String, Object> validLegacy = new HashMap<>();
        validLegacy.put("op", "done");
        validLegacy.put("target_file", "20260601-1-buy-bread.md");
        assertThat(ClassifierService.resolveTargetFile(validLegacy, OPEN_TASKS))
                .isEqualTo("20260601-1-buy-bread.md");

        // hallucinated filename that doesn't exist in openTasks — must not be trusted
        Map<String, Object> hallucinated = new HashMap<>();
        hallucinated.put("op", "done");
        hallucinated.put("target_file", "20260101-999999-made-up.md");
        assertThat(ClassifierService.resolveTargetFile(hallucinated, OPEN_TASKS)).isNull();
    }
}
