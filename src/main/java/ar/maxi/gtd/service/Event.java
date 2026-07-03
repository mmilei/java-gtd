package ar.maxi.gtd.service;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;

/**
 * One line of the durable {@code .vault-meta/events.jsonl} log. A {@code mutation} event records
 * enough of the before/after state to invert the change (see VaultService#undoEvent); a
 * {@code undo} event records which mutation it reversed via {@code undoes}, so replay never needs
 * more than a single linear scan of the log.
 */
public record Event(
    String id,
    String ts,
    String actor,
    String kind,
    String op,
    String file,
    String title,
    @JsonProperty("path_before") String pathBefore,
    @JsonProperty("path_after") String pathAfter,
    @JsonProperty("previous_content") String previousContent,
    String confirmation,
    String undoes,
    @JsonProperty("chat_ref") String chatRef
) {

    public static Event mutation(Actor actor, String op, String file, String title,
                                  String pathBefore, String pathAfter, String previousContent,
                                  String confirmation, String chatRef) {
        return new Event("", Instant.now().toString(), actor.toJson(), "mutation", op,
            file, title, pathBefore, pathAfter, previousContent, confirmation, null, chatRef);
    }

    /** path_before/path_after are swapped relative to the original — undoing a move is itself a move. */
    public static Event undoOf(Event original) {
        return new Event("", Instant.now().toString(), Actor.USER.toJson(), "undo", null,
            original.file(), original.title(), original.pathAfter(), original.pathBefore(),
            null, "none", original.id(), null);
    }

    public Event withId(String newId) {
        return new Event(newId, ts, actor, kind, op, file, title, pathBefore, pathAfter,
            previousContent, confirmation, undoes, chatRef);
    }
}
