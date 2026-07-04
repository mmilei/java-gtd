package ar.maxi.gtd.api;

import ar.maxi.gtd.service.Actor;
import ar.maxi.gtd.service.ChatMessage;
import ar.maxi.gtd.service.ClassifierService;
import ar.maxi.gtd.service.ClassifierService.ClassifyResult;
import ar.maxi.gtd.service.EventLog;
import ar.maxi.gtd.service.TranscriptLog;
import ar.maxi.gtd.service.VaultService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api")
public class ChatController {

    private static final Logger log = LoggerFactory.getLogger(ChatController.class);

    private final ClassifierService classifier;
    private final VaultService vault;
    private final TranscriptLog transcript;
    private final EventLog eventLog;
    private final ObjectMapper mapper;

    public ChatController(ClassifierService classifier, VaultService vault, TranscriptLog transcript, EventLog eventLog, ObjectMapper mapper) {
        this.classifier = classifier;
        this.vault = vault;
        this.transcript = transcript;
        this.eventLog = eventLog;
        this.mapper = mapper;
    }

    @PostMapping("/chat")
    public ResponseEntity<ChatResponse> chat(@RequestBody Map<String, String> body) {
        String message = body.get("message");
        if (message == null || message.isBlank()) {
            return ResponseEntity.badRequest().body(
                new ChatResponse(false, List.of(Map.of("error", "message is required")))
            );
        }
        transcript.append("user", message, false);

        List<Map<String, Object>> openTasks = vault.listAllFlat();
        ClassifyResult result = classifier.classifyAll(message, openTasks);
        List<Map<String, Object>> ops = result.ops();

        List<Map<String, Object>> results = new ArrayList<>();
        List<Map<String, Object>> discardedOps = new ArrayList<>();

        for (Map<String, Object> op : ops) {
            Map<String, Object> dispatched = dispatch(op, result.usedFallback());
            results.add(dispatched);

            String bucket = (String) op.get("bucket");
            if ("discard".equals(bucket)) {
                discardedOps.add(op);
            }
        }

        if (!discardedOps.isEmpty()) {
            vault.logDiscard(message, discardedOps);
        }

        // the assistant transcript entry is appended after dispatch so its id can be attached,
        // as chat_ref, to any op still waiting on POST /api/chat/confirm
        ChatMessage assistantMsg = transcript.append("assistant", serializeOps(results), result.usedFallback());
        List<Map<String, Object>> withChatRef = results.stream().map(r -> {
            if (!Boolean.TRUE.equals(r.get("requires_confirmation"))) return r;
            Map<String, Object> withRef = new LinkedHashMap<>(r);
            withRef.put("chat_ref", assistantMsg.id());
            return withRef;
        }).collect(Collectors.toList());

        return ResponseEntity.ok(new ChatResponse(result.usedFallback(), withChatRef));
    }

    /**
     * Approves an LLM-proposed edit/update/dismiss that came back from /api/chat with
     * requires_confirmation=true. Distinct from the generic PUT /body and POST /dismiss endpoints
     * (which BucketController exposes for direct user edits) so the event log can tell "user typed
     * this by hand" apart from "user approved what the LLM proposed" — see decision 7 of the
     * historial/undo/confirmed plan.
     */
    @PostMapping("/chat/confirm")
    public ResponseEntity<Map<String, Object>> confirm(@RequestBody Map<String, String> body) {
        String targetFile = body.get("target_file");
        String op = body.get("op");
        String chatRef = body.get("chat_ref");
        if (targetFile == null || op == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "target_file and op are required"));
        }
        try {
            switch (op) {
                case "edit", "update" -> vault.replaceBody(targetFile, body.getOrDefault("proposed_body", ""), Actor.LLM, "confirmed", chatRef);
                case "dismiss" -> vault.dismissItem(targetFile, Actor.LLM, "confirmed", chatRef);
                default -> {
                    return ResponseEntity.badRequest().body(Map.of("error", "unsupported op for confirm: " + op));
                }
            }
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(Map.of("confirmed", true, "file", targetFile, "op", op));
    }

    /** Raw transcript, interleaved with whether each pending requires_confirmation op has since been resolved — lets the frontend rehydrate cards on page reload. */
    @GetMapping("/chat/history")
    public List<Map<String, Object>> history(@RequestParam(defaultValue = "50") int limit) {
        // Read the event log once for the whole response instead of once per op — deserializeOpsWithResolution
        // used to call eventLog.tail(0, null, null) inside a loop over every requires_confirmation op.
        Set<String> confirmedKeys = eventLog.tail(0, null, null).stream()
            .filter(e -> "confirmed".equals(e.confirmation()) && e.chatRef() != null)
            .map(e -> e.chatRef() + "|" + e.file())
            .collect(Collectors.toSet());
        return transcript.tail(limit).stream().map(m -> toHistoryEntry(m, confirmedKeys)).collect(Collectors.toList());
    }

    private Map<String, Object> toHistoryEntry(ChatMessage m, Set<String> confirmedKeys) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("id", m.id());
        entry.put("ts", m.ts());
        entry.put("role", m.role());
        if (!"assistant".equals(m.role())) {
            entry.put("text", m.text());
            return entry;
        }
        entry.put("fallback", m.fallback());
        entry.put("ops", deserializeOpsWithResolution(m, confirmedKeys));
        return entry;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> deserializeOpsWithResolution(ChatMessage assistantMsg, Set<String> confirmedKeys) {
        List<Map<String, Object>> ops;
        try {
            ops = mapper.readValue(assistantMsg.text(), List.class);
        } catch (Exception e) {
            return List.of();
        }
        for (Map<String, Object> op : ops) {
            if (!Boolean.TRUE.equals(op.get("requires_confirmation"))) continue;
            String targetFile = (String) op.get("target_file");
            boolean resolved = targetFile != null && confirmedKeys.contains(assistantMsg.id() + "|" + targetFile);
            op.put("resolved", resolved);
        }
        return ops;
    }

    private String serializeOps(List<Map<String, Object>> ops) {
        try {
            return mapper.writeValueAsString(ops);
        } catch (Exception e) {
            return "[]";
        }
    }

    private Map<String, Object> dispatch(Map<String, Object> op, boolean usedFallback) {
        String opType = (String) op.get("op");
        try {
            return switch (opType) {
                case "create" -> handleCreate(op, usedFallback);
                case "done"   -> handleDone(op);
                case "update" -> handleUpdate(op);
                case "move"   -> handleMove(op);
                case "edit"   -> handleEdit(op);
                case "patch"  -> handlePatch(op);
                case "dismiss" -> handleDismissOp(op);
                default       -> Map.of("op", opType, "filed", false, "error", "unknown op: " + opType);
            };
        } catch (Exception e) {
            log.error("dispatch failed for op {}", opType, e);
            return Map.of("op", opType != null ? opType : "unknown", "filed", false, "error", "internal error processing operation");
        }
    }

    private Map<String, Object> handleCreate(Map<String, Object> op, boolean usedFallback) {
        String bucket = (String) op.get("bucket");
        String title  = op.get("title") != null ? (String) op.get("title") : "";
        if ("now".equals(bucket) || "discard".equals(bucket)) {
            return Map.of(
                "op", "create",
                "filed", false,
                "bucket", bucket,
                "title", title,
                "message", op.getOrDefault("message", "No archivado.")
            );
        }
        // usedFallback is a proxy for low classifier confidence, not LLM self-assessment (which
        // tends to always claim certainty) — confirmed:false queues the task for later review
        // instead of blocking or silently trusting a shaky classification. Copy rather than
        // mutate op in place: it may be an immutable Map (Jackson-deserialized ops are mutable,
        // but callers/tests aren't guaranteed to hand us one).
        Map<String, Object> toWrite = op;
        if (usedFallback) {
            toWrite = new java.util.LinkedHashMap<>(op);
            toWrite.put("confirmed", false);
        }
        String filename = vault.write(toWrite, Actor.LLM);
        return Map.of(
            "op", "create",
            "filed", true,
            "bucket", bucket,
            "file", filename,
            "title", title,
            "confirmed", !usedFallback
        );
    }

    private Map<String, Object> handleDone(Map<String, Object> op) {
        String targetFile = (String) op.get("target_file");
        if (targetFile == null) {
            return Map.of("op", "done", "filed", false, "error", "no match found");
        }
        Map<String, Object> current = vault.read(targetFile);
        vault.markDone(targetFile, Actor.LLM);
        return Map.of(
            "op", "done",
            "filed", true,
            "file", targetFile,
            "title", current.getOrDefault("title", targetFile)
        );
    }

    private Map<String, Object> handleEdit(Map<String, Object> op) {
        String targetFile = (String) op.get("target_file");
        if (targetFile == null) {
            return Map.of("op", "edit", "filed", false, "error", "no match found");
        }
        String proposedBody = (String) op.getOrDefault("new_body", "");
        Map<String, Object> current = vault.read(targetFile);
        return Map.of(
            "op", "edit",
            "filed", false,
            "requires_confirmation", true,
            "target_file", targetFile,
            "title", current.getOrDefault("title", targetFile),
            "current_body", current.getOrDefault("body", ""),
            "proposed_body", proposedBody
        );
    }

    private Map<String, Object> handleDismissOp(Map<String, Object> op) {
        String targetFile = (String) op.get("target_file");
        if (targetFile == null) {
            return Map.of("op", "dismiss", "filed", false, "error", "no match found");
        }
        Map<String, Object> current = vault.read(targetFile);
        return Map.of(
            "op", "dismiss",
            "filed", false,
            "requires_confirmation", true,
            "target_file", targetFile,
            "title", current.getOrDefault("title", targetFile)
        );
    }

    private Map<String, Object> handleMove(Map<String, Object> op) {
        String targetFile = (String) op.get("target_file");
        if (targetFile == null) {
            return Map.of("op", "move", "filed", false, "error", "no match found");
        }
        String newBucket = (String) op.get("new_bucket");
        String due = (String) op.get("due");
        Map<String, Object> current = vault.read(targetFile);
        vault.moveBucket(targetFile, newBucket, due, Actor.LLM);
        return Map.of(
            "op", "move",
            "filed", true,
            "file", targetFile,
            "new_bucket", newBucket,
            "title", current.getOrDefault("title", targetFile)
        );
    }

    private Map<String, Object> handlePatch(Map<String, Object> op) {
        String targetFile = (String) op.get("target_file");
        if (targetFile == null) {
            return Map.of("op", "patch", "filed", false, "error", "no match found");
        }
        Map<String, Object> meta = new java.util.HashMap<>();
        if (op.containsKey("tags"))        meta.put("tags", op.get("tags"));
        if (op.containsKey("due"))         meta.put("due", op.get("due"));
        if (op.containsKey("today_since")) meta.put("today_since", op.get("today_since"));
        vault.patchMeta(targetFile, meta, Actor.LLM);
        return Map.of("op", "patch", "filed", true, "file", targetFile);
    }

    private Map<String, Object> handleUpdate(Map<String, Object> op) {
        String targetFile = (String) op.get("target_file");
        if (targetFile == null) {
            return Map.of("op", "update", "filed", false, "error", "no match found");
        }
        String append = (String) op.getOrDefault("append", "");
        Map<String, Object> current = vault.read(targetFile);
        String currentBody = (String) current.getOrDefault("body", "");
        String proposedBody = currentBody.isBlank() ? append : currentBody + "\n" + append;
        return Map.of(
            "op", "update",
            "filed", false,
            "requires_confirmation", true,
            "target_file", targetFile,
            "title", current.getOrDefault("title", targetFile),
            "current_body", currentBody,
            "proposed_body", proposedBody
        );
    }

    record ChatResponse(boolean fallback, List<Map<String, Object>> ops) {}
}
