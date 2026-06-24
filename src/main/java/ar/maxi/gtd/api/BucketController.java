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
}
