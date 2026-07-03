package ar.maxi.gtd.api;

import ar.maxi.gtd.service.Event;
import ar.maxi.gtd.service.EventLog;
import ar.maxi.gtd.service.VaultService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api")
public class UndoController {

    private final EventLog eventLog;
    private final VaultService vault;

    public UndoController(EventLog eventLog, VaultService vault) {
        this.eventLog = eventLog;
        this.vault = vault;
    }

    /**
     * synchronized so two near-simultaneous requests (e.g. a double-clicked Undo button) can't
     * both read the same nextUndoable() target before either has appended its undo marker — the
     * second one would otherwise redo the same restore, or hit a FileAlreadyExistsException from
     * a move whose source the first request already consumed.
     */
    @PostMapping("/undo")
    public synchronized ResponseEntity<Map<String, Object>> undo() {
        Optional<Event> target = eventLog.nextUndoable();
        if (target.isEmpty()) {
            return ResponseEntity.ok(Map.of("undone", false, "reason", "empty stack"));
        }
        Event e = target.get();
        try {
            vault.undoEvent(e);
        } catch (Exception ex) {
            return ResponseEntity.internalServerError()
                .body(Map.of("undone", false, "reason", ex.getMessage()));
        }
        eventLog.append(Event.undoOf(e));
        return ResponseEntity.ok(Map.of(
            "undone", true,
            "file", e.file(),
            "op", e.op(),
            "restored_to", e.pathBefore() != null ? e.pathBefore() : "deleted"
        ));
    }

    @GetMapping("/undo/stack")
    public List<Map<String, Object>> stack() {
        return eventLog.undoableStack().stream().map(e -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("file", e.file());
            m.put("title", e.title());
            m.put("op", e.op());
            m.put("actor", e.actor());
            m.put("ts", e.ts());
            return m;
        }).collect(Collectors.toList());
    }
}
