package ar.maxi.gtd.api;

import ar.maxi.gtd.service.ClassifierService;
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
    public ResponseEntity<List<Map<String, Object>>> chat(@RequestBody Map<String, String> body) {
        String message = body.get("message");
        if (message == null || message.isBlank()) {
            return ResponseEntity.badRequest().body(
                List.of(Map.of("error", "message is required"))
            );
        }

        List<Map<String, Object>> openTasks = vault.listAllFlat();
        List<Map<String, Object>> ops = classifier.classifyAll(message, openTasks);

        List<Map<String, Object>> results = new ArrayList<>();
        for (Map<String, Object> op : ops) {
            results.add(dispatch(op));
        }
        return ResponseEntity.ok(results);
    }

    private Map<String, Object> dispatch(Map<String, Object> op) {
        String opType = (String) op.get("op");
        try {
            return switch (opType) {
                case "create" -> handleCreate(op);
                case "done"   -> handleDone(op);
                case "update" -> handleUpdate(op);
                default       -> Map.of("op", opType, "filed", false, "error", "op desconocido: " + opType);
            };
        } catch (Exception e) {
            return Map.of("op", opType != null ? opType : "unknown", "filed", false, "error", e.getMessage());
        }
    }

    private Map<String, Object> handleCreate(Map<String, Object> op) {
        String bucket = (String) op.get("bucket");
        if ("now".equals(bucket) || "discard".equals(bucket)) {
            return Map.of(
                "op", "create",
                "filed", false,
                "bucket", bucket,
                "message", op.getOrDefault("message", "No archivado.")
            );
        }
        String filename = vault.write(op);
        return Map.of(
            "op", "create",
            "filed", true,
            "bucket", bucket,
            "file", filename
        );
    }

    private Map<String, Object> handleDone(Map<String, Object> op) {
        String targetFile = (String) op.get("target_file");
        if (targetFile == null) {
            return Map.of("op", "done", "filed", false, "error", "no match encontrado");
        }
        vault.markDone(targetFile);
        return Map.of("op", "done", "filed", true, "file", targetFile);
    }

    private Map<String, Object> handleUpdate(Map<String, Object> op) {
        String targetFile = (String) op.get("target_file");
        if (targetFile == null) {
            return Map.of("op", "update", "filed", false, "error", "no match encontrado");
        }
        String append = (String) op.getOrDefault("append", "");
        vault.appendToTask(targetFile, append);
        return Map.of("op", "update", "filed", true, "file", targetFile, "appended", append);
    }
}
