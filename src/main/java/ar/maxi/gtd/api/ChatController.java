package ar.maxi.gtd.api;

import ar.maxi.gtd.service.ClassifierService;
import ar.maxi.gtd.service.VaultService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

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
    public ResponseEntity<Map<String, Object>> chat(@RequestBody Map<String, String> body) {
        String message = body.get("message");
        if (message == null || message.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "message is required"));
        }

        Map<String, Object> item = classifier.classify(message);
        String bucket = (String) item.get("bucket");

        if ("now".equals(bucket) || "discard".equals(bucket)) {
            return ResponseEntity.ok(Map.of(
                "filed", false,
                "bucket", bucket,
                "message", item.getOrDefault("message", "No archivado.")
            ));
        }

        String filename = vault.write(item);
        return ResponseEntity.ok(Map.of(
            "filed", true,
            "bucket", bucket,
            "file", filename,
            "item", item
        ));
    }
}
