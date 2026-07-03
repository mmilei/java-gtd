package ar.maxi.gtd.service;

/** One line of the durable .vault-meta/transcript.jsonl log — the raw chat exchange, verbatim. */
public record ChatMessage(
    String id,
    String ts,
    String role,   // "user" | "assistant"
    String text,   // user's raw message, or the JSON-serialized ops list for an assistant turn
    boolean fallback
) {
    public ChatMessage withId(String newId) {
        return new ChatMessage(newId, ts, role, text, fallback);
    }
}
