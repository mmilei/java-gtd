package com.gtd.service;

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
    void buildPromptSubstitutesKnownProjectsAndValidAreasPlaceholders() {
        String template = "today={today} ctx={user_context} tasks={open_tasks} projects={known_projects} areas={valid_areas} msg={message}";
        String result = ClassifierService.buildPrompt(
                template, "2026-07-07", "profile", "[]", "java-gtd, frontend-gtd", "personal, friends", "fix the bug");
        assertThat(result).isEqualTo(
                "today=2026-07-07 ctx=profile tasks=[] projects=java-gtd, frontend-gtd areas=personal, friends msg=fix the bug");
        assertThat(result).doesNotContain("{known_projects}");
        assertThat(result).doesNotContain("{valid_areas}");
    }

    @Test
    void formatKnownProjectsJoinsWithCommasOrMarksNoneYet() {
        assertThat(ClassifierService.formatKnownProjects(List.of())).isEqualTo("(none yet)");
        assertThat(ClassifierService.formatKnownProjects(List.of("java-gtd")))
                .isEqualTo("java-gtd");
        assertThat(ClassifierService.formatKnownProjects(List.of("java-gtd", "frontend-gtd")))
                .isEqualTo("java-gtd, frontend-gtd");
    }

    @Test
    void filterRelevantTasksShouldPrioritizeTitleKeywordOverlap() {
        List<Map<String, Object>> tasks = List.of(
                Map.of("file", "1.md", "title", "Buy bread", "bucket", "backlog"),
                Map.of("file", "2.md", "title", "Buy milk", "bucket", "backlog"),
                Map.of("file", "3.md", "title", "Fix the deploy pipeline", "bucket", "backlog"),
                Map.of("file", "4.md", "title", "Call the dentist", "bucket", "today")
        );

        // message clearly overlaps the deploy task — it must come first, within the limit
        List<Map<String, Object>> filtered =
                ClassifierService.filterRelevantTasks(tasks, "the deploy pipeline is broken again", 2);
        assertThat(filtered).hasSize(2);
        assertThat(filtered.get(0).get("title")).isEqualTo("Fix the deploy pipeline");
    }

    @Test
    void filterRelevantTasksShouldReturnAllWhenUnderLimit() {
        List<Map<String, Object>> tasks = List.of(
                Map.of("file", "1.md", "title", "Buy bread", "bucket", "backlog"),
                Map.of("file", "2.md", "title", "Buy milk", "bucket", "backlog")
        );
        // fewer tasks than the limit → passed through untouched, no filtering/reordering
        assertThat(ClassifierService.filterRelevantTasks(tasks, "anything at all", 5)).isEqualTo(tasks);
    }

    @Test
    void filterRelevantTasksShouldFallBackToOriginalOrderWhenNoOverlap() {
        List<Map<String, Object>> tasks = List.of(
                Map.of("file", "1.md", "title", "Buy bread", "bucket", "backlog"),
                Map.of("file", "2.md", "title", "Buy milk", "bucket", "backlog"),
                Map.of("file", "3.md", "title", "Call the dentist", "bucket", "today")
        );
        // a plain create sharing no word with any title → keep the first `limit` in original order,
        // never an arbitrary/empty-looking selection that could drop a would-be target task
        List<Map<String, Object>> filtered =
                ClassifierService.filterRelevantTasks(tasks, "comprar entradas para el recital", 2);
        assertThat(filtered).hasSize(2);
        assertThat(filtered.get(0).get("file")).isEqualTo("1.md");
        assertThat(filtered.get(1).get("file")).isEqualTo("2.md");
    }

    @Test
    void filterRelevantTasksShouldMatchAccentInsensitively() {
        // Same bug class resolveTargetFile already fixed once ("verificacion" vs "Verificación"):
        // an accented title must overlap its unaccented paraphrase, or the pre-filter would evict
        // exactly the task the user is referring to.
        List<Map<String, Object>> tasks = List.of(
                Map.of("file", "1.md", "title", "Buy bread", "bucket", "backlog"),
                Map.of("file", "2.md", "title", "Buy milk", "bucket", "backlog"),
                Map.of("file", "3.md", "title", "Comprar colchón para papá", "bucket", "backlog"),
                Map.of("file", "4.md", "title", "Call the dentist", "bucket", "today")
        );
        List<Map<String, Object>> filtered =
                ClassifierService.filterRelevantTasks(tasks, "marca como hecho lo del colchon de papa", 2);
        assertThat(filtered.get(0).get("file")).isEqualTo("3.md");
    }

    @Test
    void filterRelevantTasksShouldPadWithOriginalOrderWhenFewMatches() {
        // A single incidental keyword match must not evict every non-matching task: the remaining
        // slots are padded in original order so a would-be target task stays in context.
        List<Map<String, Object>> tasks = List.of(
                Map.of("file", "1.md", "title", "Buy bread", "bucket", "backlog"),
                Map.of("file", "2.md", "title", "Buy milk", "bucket", "backlog"),
                Map.of("file", "3.md", "title", "Call the dentist", "bucket", "today"),
                Map.of("file", "4.md", "title", "Fix the deploy pipeline", "bucket", "backlog")
        );
        List<Map<String, Object>> filtered =
                ClassifierService.filterRelevantTasks(tasks, "deploy something new", 3);
        assertThat(filtered).hasSize(3);
        assertThat(filtered.get(0).get("file")).isEqualTo("4.md"); // the only keyword match, first
        assertThat(filtered.get(1).get("file")).isEqualTo("1.md"); // then original order
        assertThat(filtered.get(2).get("file")).isEqualTo("2.md");
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
