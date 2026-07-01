package ar.maxi.gtd.api;

import ar.maxi.gtd.service.LlmProviderService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api")
public class ProviderController {

    private final LlmProviderService providers;

    public ProviderController(LlmProviderService providers) {
        this.providers = providers;
    }

    @GetMapping("/providers")
    public Map<String, Object> list() {
        return providers.describeAll();
    }

    @PostMapping("/providers/select")
    public ResponseEntity<Map<String, Object>> select(@RequestBody Map<String, String> body) {
        String id = body.get("provider");
        if (!providers.select(id)) {
            return ResponseEntity.badRequest().body(Map.of("error", "provider not available: " + id));
        }
        return ResponseEntity.ok(Map.of("active", providers.describeAll().get("active")));
    }
}
