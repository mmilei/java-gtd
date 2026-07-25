package com.gtd.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class ClassifierServiceTest {

    /** Builds a ClassifierService with a stubbed LLM and a minimal vault stub — reads the real
     * classpath Triage templates ("sample" mode), so classifyAll's retry orchestration runs end to end. */
    private static ClassifierService serviceWith(LlmProviderService llm) {
        VaultService vault = mock(VaultService.class);
        when(vault.readContextFile(anyString())).thenReturn("");
        when(vault.knownProjects()).thenReturn(List.of());
        when(vault.knownTags()).thenReturn(List.of());
        when(vault.validAreas()).thenReturn(List.of("finanzas", "hogar"));
        return new ClassifierService(llm, new ObjectMapper(), vault, "sample");
    }

    @Test
    void malformedLevel1JsonTriggersFormatRetryAndFlagsFallback() {
        // the format retry (JSON didn't parse) still works: level-1 garbage → retry template → valid ops.
        // The recovered create is confirmed, so it now also triggers Prompt B — hence a 3rd complete().
        LlmProviderService llm = mock(LlmProviderService.class);
        when(llm.complete(any(), anyString()))
                .thenReturn("sorry, I cannot output JSON right now")   // level 1: unparseable
                .thenReturn("[{\"op\":\"create\",\"bucket\":\"backlog\",\"title\":\"X\",\"confirmed\":true}]") // retry: valid
                .thenReturn("{\"area\":null,\"tags\":[]}");            // Prompt B enrichment
        ClassifierService.ClassifyResult r = serviceWith(llm).classifyAll("do the thing", List.of());
        assertThat(r.usedFallback()).isTrue();
        assertThat(r.ops()).hasSize(1);
        assertThat(r.ops().get(0)).containsEntry("op", "create");
        // level1 + format retry both go to TRIAGE; the recovered confirmed create fires one ENRICHMENT
        verify(llm, times(2)).complete(eq(LlmAction.TRIAGE), anyString());
        verify(llm, times(1)).complete(eq(LlmAction.ENRICHMENT), anyString());
    }

    // ---- Split B/C pipeline (step 4) ----

    @Test
    void confirmedTrueCreateTriggersPromptBAndMergesEnrichment() {
        // (a) a confirmed create fires exactly one Prompt B call; its fields land on the op
        LlmProviderService llm = mock(LlmProviderService.class);
        when(llm.complete(any(), anyString()))
                .thenReturn("[{\"op\":\"create\",\"bucket\":\"backlog\",\"title\":\"Comprar pilas\",\"body\":\"para el mouse\",\"confirmed\":true}]") // A
                .thenReturn("{\"area\":\"hogar\",\"tags\":[\"compras\"],\"project\":null,\"location\":\"super\",\"estimate_minutes\":15}");           // B
        ClassifierService.ClassifyResult r = serviceWith(llm).classifyAll("comprar pilas para el mouse", List.of());
        Map<String, Object> op = r.ops().get(0);
        assertThat(op).containsEntry("area", "hogar");
        assertThat(op).containsEntry("location", "super");
        assertThat(op).containsEntry("estimate_minutes", 15);
        assertThat(op.get("tags")).isEqualTo(List.of("compras"));
        verify(llm, times(1)).complete(eq(LlmAction.TRIAGE), anyString());
        verify(llm, times(1)).complete(eq(LlmAction.ENRICHMENT), anyString());
        verify(llm, never()).complete(eq(LlmAction.RESOLVER), anyString());
    }

    @Test
    void promptBParseFailureFilesTaskUnenrichedWithoutRetry() {
        // (b) Prompt B returns non-JSON → the task is filed with what Prompt A gave it, no retry
        LlmProviderService llm = mock(LlmProviderService.class);
        when(llm.complete(any(), anyString()))
                .thenReturn("[{\"op\":\"create\",\"bucket\":\"backlog\",\"title\":\"Comprar pilas\",\"body\":\"para el mouse\",\"confirmed\":true}]") // A
                .thenReturn("sorry, no json here");                                                                                                   // B unparseable
        ClassifierService.ClassifyResult r = serviceWith(llm).classifyAll("comprar pilas para el mouse", List.of());
        Map<String, Object> op = r.ops().get(0);
        assertThat(op).doesNotContainKey("area");
        assertThat(op).doesNotContainKey("location");
        assertThat(op).doesNotContainKey("tags");
        // A (TRIAGE) + one B (ENRICHMENT) attempt, no retry, no RESOLVER
        verify(llm, times(1)).complete(eq(LlmAction.TRIAGE), anyString());
        verify(llm, times(1)).complete(eq(LlmAction.ENRICHMENT), anyString());
    }

    @Test
    void confirmedFalseCreateTriggersPromptCWhichCanResolve() {
        // (c)+(d) an unconfirmed create fires Prompt C; a resolving C upgrades confirmed and fills fields
        LlmProviderService llm = mock(LlmProviderService.class);
        when(llm.complete(any(), anyString()))
                .thenReturn("[{\"op\":\"create\",\"bucket\":\"backlog\",\"title\":\"Resolver alquiler\",\"body\":\"no sabe\",\"confirmed\":false}]") // A
                .thenReturn("{\"bucket\":\"backlog\",\"area\":\"finanzas\",\"tags\":[\"finanzas\"],\"confirmed\":true}");                              // C resolves
        ClassifierService.ClassifyResult r = serviceWith(llm).classifyAll("tema del alquiler", List.of());
        Map<String, Object> op = r.ops().get(0);
        assertThat(op).containsEntry("confirmed", true);
        assertThat(op).containsEntry("area", "finanzas");
        assertThat(op.get("tags")).isEqualTo(List.of("finanzas"));
        assertThat(r.usedFallback()).isFalse();
        // unconfirmed create routes A (TRIAGE) → C (RESOLVER), never B
        verify(llm, times(1)).complete(eq(LlmAction.TRIAGE), anyString());
        verify(llm, times(1)).complete(eq(LlmAction.RESOLVER), anyString());
        verify(llm, never()).complete(eq(LlmAction.ENRICHMENT), anyString());
    }

    @Test
    void confirmedFalseCreateStaysUnconfirmedWhenPromptCCannotResolve() {
        // (e) Prompt C returns confirmed:false (or can't) → the op keeps confirmed:false, exactly as today
        LlmProviderService llm = mock(LlmProviderService.class);
        when(llm.complete(any(), anyString()))
                .thenReturn("[{\"op\":\"create\",\"bucket\":\"backlog\",\"title\":\"Resolver alquiler\",\"confirmed\":false}]") // A
                .thenReturn("{\"bucket\":\"backlog\",\"confirmed\":false}");                                                     // C can't resolve
        ClassifierService.ClassifyResult r = serviceWith(llm).classifyAll("tema del alquiler", List.of());
        assertThat(r.ops().get(0)).containsEntry("confirmed", false);
        assertThat(r.usedFallback()).isFalse();
        verify(llm, times(1)).complete(eq(LlmAction.TRIAGE), anyString());
        verify(llm, times(1)).complete(eq(LlmAction.RESOLVER), anyString());
    }

    @Test
    void nonCreateOpsNeverCallPromptBOrC() {
        // (f) a done op resolves against open tasks and dispatches with a single A call — no B/C
        LlmProviderService llm = mock(LlmProviderService.class);
        when(llm.complete(any(), anyString()))
                .thenReturn("[{\"op\":\"done\",\"target_title\":\"Buy bread\"}]"); // A only
        ClassifierService.ClassifyResult r = serviceWith(llm).classifyAll("mark buy bread as done", OPEN_TASKS);
        assertThat(r.ops().get(0)).containsEntry("op", "done");
        // a done op is a single TRIAGE call — never enrichment/resolver
        verify(llm, times(1)).complete(eq(LlmAction.TRIAGE), anyString());
        verify(llm, never()).complete(eq(LlmAction.ENRICHMENT), anyString());
        verify(llm, never()).complete(eq(LlmAction.RESOLVER), anyString());
    }

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
                .isEqualTo("prompts/classifier-triage-custom.st");
        assertThat(ClassifierService.templateResourcePath("custom", true))
                .isEqualTo("prompts/classifier-triage-fallback-custom.st");
    }

    @Test
    void templateResourcePathDefaultsToSampleForSampleModeOrUnknownValue() {
        assertThat(ClassifierService.templateResourcePath("sample", false))
                .isEqualTo("prompts/classifier-triage.st");
        assertThat(ClassifierService.templateResourcePath("sample", true))
                .isEqualTo("prompts/classifier-triage-fallback.st");

        // unrecognized/null values must never silently fall through to the gitignored
        // custom templates, which don't exist in CI or a public clone
        assertThat(ClassifierService.templateResourcePath("something-else", false))
                .isEqualTo("prompts/classifier-triage.st");
        assertThat(ClassifierService.templateResourcePath(null, false))
                .isEqualTo("prompts/classifier-triage.st");
    }

    @Test
    void enrichTemplatePathFollowsSampleCustomSplitLikeTriage() {
        assertThat(ClassifierService.enrichTemplatePath("custom"))
                .isEqualTo("prompts/classifier-enrich-custom.st");
        assertThat(ClassifierService.enrichTemplatePath("sample"))
                .isEqualTo("prompts/classifier-enrich.st");
        assertThat(ClassifierService.enrichTemplatePath("something-else"))
                .isEqualTo("prompts/classifier-enrich.st");
        // resolver never consults the switch — single committed template, same path always
        assertThat(ClassifierService.RESOLVER_TEMPLATE_PATH)
                .isEqualTo("prompts/classifier-resolver.st");
    }

    @Test
    void buildEnrichmentPromptSubstitutesMessageTitleBodyBucketAndContext() {
        String template = "msg={message} t={title} b={body} bk={bucket} p={known_projects} tg={known_tags} a={valid_areas}";
        String result = ClassifierService.buildEnrichmentPrompt(
                template, "comprar pilas", "Comprar pilas", "para el mouse", "backlog",
                "java-gtd", "compras, hogar", "hogar, finanzas");
        assertThat(result).isEqualTo(
                "msg=comprar pilas t=Comprar pilas b=para el mouse bk=backlog p=java-gtd tg=compras, hogar a=hogar, finanzas");
        assertThat(result).doesNotContain("{message}");
        assertThat(result).doesNotContain("{title}");
        assertThat(result).doesNotContain("{bucket}");
    }

    @Test
    void buildPromptSubstitutesKnownProjectsKnownTagsAndValidAreasPlaceholders() {
        String template = "today={today} ctx={user_context} tasks={open_tasks} projects={known_projects} tags={known_tags} areas={valid_areas} msg={message}";
        String result = ClassifierService.buildPrompt(
                template, "2026-07-07", "profile", "[]", "java-gtd, frontend-gtd", "compras, salud", "personal, friends", "fix the bug");
        assertThat(result).isEqualTo(
                "today=2026-07-07 ctx=profile tasks=[] projects=java-gtd, frontend-gtd tags=compras, salud areas=personal, friends msg=fix the bug");
        assertThat(result).doesNotContain("{known_projects}");
        assertThat(result).doesNotContain("{known_tags}");
        assertThat(result).doesNotContain("{valid_areas}");
    }

    @Test
    void formatCsvOrNoneYetJoinsWithCommasOrMarksNoneYet() {
        assertThat(ClassifierService.formatCsvOrNoneYet(List.of())).isEqualTo("(none yet)");
        assertThat(ClassifierService.formatCsvOrNoneYet(List.of("java-gtd")))
                .isEqualTo("java-gtd");
        assertThat(ClassifierService.formatCsvOrNoneYet(List.of("java-gtd", "frontend-gtd")))
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
