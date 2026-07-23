package com.gtd.api;

import com.gtd.service.LlmAction;
import com.gtd.service.LlmProviderService;
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
        String actionId = body.get("action");
        LlmAction action;
        try {
            action = LlmAction.valueOf(String.valueOf(actionId).trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", "unknown action: " + actionId));
        }
        String id = body.get("provider");
        if (!providers.select(action, id)) {
            return ResponseEntity.badRequest().body(Map.of("error", "provider not available: " + id));
        }
        return ResponseEntity.ok(Map.of("action", action.name(), "active", id.trim().toUpperCase()));
    }
}
