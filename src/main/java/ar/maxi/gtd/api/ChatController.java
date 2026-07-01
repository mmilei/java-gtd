package ar.maxi.gtd.api;

import ar.maxi.gtd.service.ClassifierService;
import ar.maxi.gtd.service.ClassifierService.ClassifyResult;
import ar.maxi.gtd.service.VaultService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class ChatController {

    private final ClassifierService classifier;
    private final VaultService vault;

    public ChatController(ClassifierService classifier, VaultService vault) {
        this.classifier = classifier;
        this.vault = vault;
    }

    @PostMapping("/chat")
    public ResponseEntity<ChatResponse> chat(@RequestBody Map<String, String> body) {
        String message = body.get("message");
        if (message == null || message.isBlank()) {
            return ResponseEntity.badRequest().body(
                new ChatResponse(false, List.of(Map.of("error", "message is required")))
            );
        }

        List<Map<String, Object>> openTasks = vault.listAllFlat();
        ClassifyResult result = classifier.classifyAll(message, openTasks);
        List<Map<String, Object>> ops = result.ops();

        List<Map<String, Object>> results = new ArrayList<>();
        List<Map<String, Object>> discardedOps = new ArrayList<>();

        for (Map<String, Object> op : ops) {
            Map<String, Object> dispatched = dispatch(op);
            results.add(dispatched);

            String bucket = (String) op.get("bucket");
            if ("discard".equals(bucket)) {
                discardedOps.add(op);
            }
        }

        if (!discardedOps.isEmpty()) {
            vault.logDiscard(message, discardedOps);
        }

        return ResponseEntity.ok(new ChatResponse(result.usedFallback(), results));
    }

    private Map<String, Object> dispatch(Map<String, Object> op) {
        String opType = (String) op.get("op");
        try {
            return switch (opType) {
                case "create" -> handleCreate(op);
                case "done"   -> handleDone(op);
                case "update" -> handleUpdate(op);
                case "move"   -> handleMove(op);
                case "edit"   -> handleEdit(op);
                case "patch"  -> handlePatch(op);
                case "dismiss" -> handleDismissOp(op);
                default       -> Map.of("op", opType, "filed", false, "error", "unknown op: " + opType);
            };
        } catch (Exception e) {
            return Map.of("op", opType != null ? opType : "unknown", "filed", false, "error", e.getMessage());
        }
    }

    private Map<String, Object> handleCreate(Map<String, Object> op) {
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
        String filename = vault.write(op);
        return Map.of(
            "op", "create",
            "filed", true,
            "bucket", bucket,
            "file", filename,
            "title", title
        );
    }

    private Map<String, Object> handleDone(Map<String, Object> op) {
        String targetFile = (String) op.get("target_file");
        if (targetFile == null) {
            return Map.of("op", "done", "filed", false, "error", "no match found");
        }
        vault.markDone(targetFile);
        return Map.of("op", "done", "filed", true, "file", targetFile);
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
        vault.moveBucket(targetFile, newBucket, due);
        return Map.of("op", "move", "filed", true, "file", targetFile, "new_bucket", newBucket);
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
        vault.patchMeta(targetFile, meta);
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
