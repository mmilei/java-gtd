package com.gtd.api;

import com.gtd.service.Actor;
import com.gtd.service.MarkdownifyService;
import com.gtd.service.VaultService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.Set;

@RestController
@RequestMapping("/api")
public class BucketController {

    private final VaultService vault;
    private final MarkdownifyService markdownify;

    public BucketController(VaultService vault, MarkdownifyService markdownify) {
        this.vault = vault;
        this.markdownify = markdownify;
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

    @GetMapping("/tags")
    public Map<String, Map<String, Integer>> tags() {
        return vault.tagCounts();
    }

    /** Low-confidence review queue: active tasks the classifier filed with confirmed:false. The user clears each via POST /api/items/{filename}/confirm. */
    @GetMapping("/unconfirmed")
    public List<Map<String, Object>> unconfirmed() {
        return vault.listUnconfirmed();
    }

    /** The configured `area` vocabulary (gtd.areas), in config order — the frontend builds its area UI from this. */
    @GetMapping("/areas")
    public List<String> areas() {
        return vault.validAreas();
    }

    private static final Set<String> CREATABLE_BUCKETS = Set.of("today", "backlog", "waiting", "someday", "reference");

    /** Files a task straight from user-entered fields — no classifier involved, unlike POST /api/chat. */
    @PostMapping("/items")
    public ResponseEntity<Map<String, Object>> createItem(@RequestBody Map<String, Object> body) {
        String bucket = (String) body.get("bucket");
        if (bucket == null || !CREATABLE_BUCKETS.contains(bucket)) {
            return ResponseEntity.badRequest().body(Map.of("error", "bucket must be one of " + CREATABLE_BUCKETS));
        }
        String title = body.get("title") != null ? String.valueOf(body.get("title")).strip() : "";
        if (title.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "title is required"));
        }
        Map<String, Object> item = new HashMap<>(body);
        item.put("title", title);
        String filename = vault.write(item, Actor.USER);
        return ResponseEntity.ok(Map.of("filed", true, "file", filename, "bucket", bucket, "title", title));
    }

    @PostMapping("/items/{filename}/done")
    public ResponseEntity<Map<String, Object>> markDone(@PathVariable String filename) {
        vault.markDone(filename, Actor.USER);
        return ResponseEntity.ok(Map.of("done", true, "file", filename));
    }

    @PostMapping("/items/{filename}/dismiss")
    public ResponseEntity<Map<String, Object>> dismiss(@PathVariable String filename) {
        vault.dismissItem(filename, Actor.USER);
        return ResponseEntity.ok(Map.of("dismissed", true, "file", filename));
    }

    /** Flips a low-confidence task's confirmed:false -> true after the user reviewed it — distinct from POST /api/chat/confirm, which approves an edit/update/dismiss the LLM proposed before it's ever written. */
    @PostMapping("/items/{filename}/confirm")
    public ResponseEntity<Map<String, Object>> confirmItem(@PathVariable String filename) {
        vault.patchMeta(filename, Map.of("confirmed", true), Actor.USER);
        return ResponseEntity.ok(Map.of("confirmed", true, "file", filename));
    }

    @PutMapping("/items/{filename}/meta")
    public ResponseEntity<Map<String, Object>> patchMeta(
            @PathVariable String filename,
            @RequestBody Map<String, Object> meta) {
        vault.patchMeta(filename, meta, Actor.USER);
        return ResponseEntity.ok(Map.of("updated", true, "file", filename));
    }

    @PutMapping("/items/{filename}/body")
    public ResponseEntity<Map<String, Object>> replaceBody(
            @PathVariable String filename,
            @RequestBody Map<String, String> body) {
        String newBody = body.get("body");
        if (newBody == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "body is required"));
        }
        vault.replaceBody(filename, newBody, Actor.USER);
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
        vault.moveBucket(filename, bucket, body.get("due"), Actor.USER);
        return ResponseEntity.ok(Map.of("moved", true, "file", filename, "bucket", bucket));
    }

    @PostMapping("/items/{filename}/markdownify")
    public ResponseEntity<Map<String, Object>> markdownify(@PathVariable String filename) {
        try {
            Map<String, Object> item = vault.read(filename);
            String title  = (String) item.getOrDefault("title", "");
            String body   = (String) item.getOrDefault("body", "");
            String bucket = (String) item.getOrDefault("bucket", "backlog");
            @SuppressWarnings("unchecked")
            List<String> tags = (List<String>) item.getOrDefault("tags", List.of());
            MarkdownifyService.EnrichResult result = markdownify.enrich(title, body, bucket, tags);
            vault.replaceBody(filename, result.body(), Actor.USER);
            vault.patchMeta(filename, Map.of("tags", result.tags(), "markdownified", true), Actor.USER);
            return ResponseEntity.ok(Map.of(
                "file", filename,
                "body", result.body(),
                "tags", result.tags()
            ));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
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
