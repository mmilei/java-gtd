package com.gtd.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class EventLogTest {

    @Test
    void shouldAppendAndAssignSequentialIds(@TempDir Path tempDir) {
        EventLog log = new EventLog(tempDir.toString());

        Event a = log.append(Event.mutation(Actor.USER, "create", "a.md", "A", null, "/a.md", null, "none", null));
        Event b = log.append(Event.mutation(Actor.USER, "create", "b.md", "B", null, "/b.md", null, "none", null));

        assertThat(a.id()).isEqualTo("e-000001");
        assertThat(b.id()).isEqualTo("e-000002");
    }

    @Test
    void shouldResumeIdCounterAcrossRestarts(@TempDir Path tempDir) {
        EventLog first = new EventLog(tempDir.toString());
        first.append(Event.mutation(Actor.USER, "create", "a.md", "A", null, "/a.md", null, "none", null));
        first.append(Event.mutation(Actor.USER, "create", "b.md", "B", null, "/b.md", null, "none", null));

        EventLog restarted = new EventLog(tempDir.toString());
        Event c = restarted.append(Event.mutation(Actor.USER, "create", "c.md", "C", null, "/c.md", null, "none", null));

        assertThat(c.id()).isEqualTo("e-000003");
    }

    @Test
    void shouldFilterTailByActorAndOp(@TempDir Path tempDir) {
        EventLog log = new EventLog(tempDir.toString());
        log.append(Event.mutation(Actor.USER, "create", "a.md", "A", null, "/a.md", null, "none", null));
        log.append(Event.mutation(Actor.LLM, "move", "b.md", "B", "/b1.md", "/b2.md", "content", "none", null));

        List<Event> llmOnly = log.tail(10, Actor.LLM, null);
        assertThat(llmOnly).hasSize(1);
        assertThat(llmOnly.get(0).file()).isEqualTo("b.md");

        List<Event> moveOnly = log.tail(10, null, "move");
        assertThat(moveOnly).hasSize(1);
        assertThat(moveOnly.get(0).op()).isEqualTo("move");
    }

    @Test
    void shouldCapTailToLimitKeepingMostRecent(@TempDir Path tempDir) {
        EventLog log = new EventLog(tempDir.toString());
        for (int i = 0; i < 5; i++) {
            log.append(Event.mutation(Actor.USER, "create", "f" + i + ".md", "F" + i, null, "/f" + i + ".md", null, "none", null));
        }

        List<Event> lastTwo = log.tail(2, null, null);
        assertThat(lastTwo).extracting(Event::file).containsExactly("f3.md", "f4.md");
    }

    @Test
    void shouldToleratesCorruptLinesWithoutFailingToRead(@TempDir Path tempDir) throws Exception {
        EventLog log = new EventLog(tempDir.toString());
        log.append(Event.mutation(Actor.USER, "create", "a.md", "A", null, "/a.md", null, "none", null));

        Path eventsFile = tempDir.resolve(".vault-meta/events.jsonl");
        Files.writeString(eventsFile, "{not valid json\n", StandardOpenOption.APPEND);

        Event b = log.append(Event.mutation(Actor.USER, "create", "b.md", "B", null, "/b.md", null, "none", null));

        List<Event> all = log.tail(0, null, null);
        assertThat(all).extracting(Event::file).containsExactly("a.md", "b.md");
        assertThat(b.id()).isEqualTo("e-000002");
    }

    @Test
    void nextUndoableShouldReturnMostRecentMutationNotYetUndone(@TempDir Path tempDir) {
        EventLog log = new EventLog(tempDir.toString());
        Event a = log.append(Event.mutation(Actor.USER, "create", "a.md", "A", null, "/a.md", null, "none", null));
        Event b = log.append(Event.mutation(Actor.USER, "create", "b.md", "B", null, "/b.md", null, "none", null));

        Optional<Event> next = log.nextUndoable();
        assertThat(next).isPresent();
        assertThat(next.get().id()).isEqualTo(b.id());

        log.append(Event.undoOf(b));

        Optional<Event> afterUndo = log.nextUndoable();
        assertThat(afterUndo).isPresent();
        assertThat(afterUndo.get().id()).isEqualTo(a.id());
    }

    @Test
    void nextUndoableShouldBeEmptyOnceEverythingIsUndone(@TempDir Path tempDir) {
        EventLog log = new EventLog(tempDir.toString());
        Event a = log.append(Event.mutation(Actor.USER, "create", "a.md", "A", null, "/a.md", null, "none", null));
        log.append(Event.undoOf(a));

        assertThat(log.nextUndoable()).isEmpty();
    }

    @Test
    void undoableStackShouldReturnMostRecentFirstAndExcludeUndone(@TempDir Path tempDir) {
        EventLog log = new EventLog(tempDir.toString());
        Event a = log.append(Event.mutation(Actor.USER, "create", "a.md", "A", null, "/a.md", null, "none", null));
        Event b = log.append(Event.mutation(Actor.USER, "create", "b.md", "B", null, "/b.md", null, "none", null));
        log.append(Event.undoOf(b));

        List<Event> stack = log.undoableStack();
        assertThat(stack).hasSize(1);
        assertThat(stack.get(0).id()).isEqualTo(a.id());
    }

    @Test
    void undoOfShouldSwapPathBeforeAndAfterRelativeToOriginal(@TempDir Path tempDir) {
        Event original = Event.mutation(Actor.LLM, "move", "a.md", "A", "/before/a.md", "/after/a.md", "old content", "none", null)
            .withId("e-000005");

        Event undo = Event.undoOf(original);

        assertThat(undo.pathBefore()).isEqualTo("/after/a.md");
        assertThat(undo.pathAfter()).isEqualTo("/before/a.md");
        assertThat(undo.undoes()).isEqualTo("e-000005");
        assertThat(undo.kind()).isEqualTo("undo");
    }
}
