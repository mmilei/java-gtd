package ar.maxi.gtd.api;

import ar.maxi.gtd.service.VaultService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class BucketController {

    private final VaultService vault;

    public BucketController(VaultService vault) {
        this.vault = vault;
    }

    @GetMapping("/today")
    public List<Map<String, Object>> today() {
        return vault.list("today");
    }

    @GetMapping("/buckets")
    public Map<String, List<Map<String, Object>>> allBuckets() {
        return vault.listAll();
    }

    @GetMapping("/buckets/{bucket}")
    public List<Map<String, Object>> byBucket(@PathVariable String bucket) {
        return vault.list(bucket);
    }

    @PostMapping("/items/{filename}/done")
    public ResponseEntity<Map<String, Object>> markDone(@PathVariable String filename) {
        vault.markDone(filename);
        return ResponseEntity.ok(Map.of("done", true, "file", filename));
    }

    @PostMapping("/items/{filename}/dismiss")
    public ResponseEntity<Map<String, Object>> dismiss(@PathVariable String filename) {
        vault.dismissItem(filename);
        return ResponseEntity.ok(Map.of("dismissed", true, "file", filename));
    }

    @PutMapping("/items/{filename}/body")
    public ResponseEntity<Map<String, Object>> replaceBody(
            @PathVariable String filename,
            @RequestBody Map<String, String> body) {
        String newBody = body.get("body");
        if (newBody == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "body is required"));
        }
        vault.replaceBody(filename, newBody);
        return ResponseEntity.ok(Map.of("updated", true, "file", filename));
    }

    @GetMapping("/items/{filename}")
    public ResponseEntity<Map<String, Object>> getItem(@PathVariable String filename) {
        try {
            return ResponseEntity.ok(vault.read(filename));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @PostMapping("/items/{filename}/move")
    public ResponseEntity<Map<String, Object>> moveItem(
            @PathVariable String filename,
            @RequestBody Map<String, String> body) {
        String bucket = body.get("bucket");
        if (bucket == null) return ResponseEntity.badRequest().body(Map.of("error", "bucket is required"));
        vault.moveBucket(filename, bucket, body.get("due"));
        return ResponseEntity.ok(Map.of("moved", true, "file", filename, "bucket", bucket));
    }

    @GetMapping("/stats")
    public Map<String, Object> stats() {
        return vault.stats();
    }

    @GetMapping("/history")
    public List<Map<String, Object>> history(
            @RequestParam(defaultValue = "20") int limit) {
        return vault.history(limit);
    }
}
