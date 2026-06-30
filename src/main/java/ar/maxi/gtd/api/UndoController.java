package ar.maxi.gtd.api;

import ar.maxi.gtd.service.UndoStack;
import ar.maxi.gtd.service.UndoStack.UndoEntry;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.nio.file.Files;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api")
public class UndoController {

    private final UndoStack undoStack;

    public UndoController(UndoStack undoStack) {
        this.undoStack = undoStack;
    }

    @PostMapping("/undo")
    public ResponseEntity<Map<String, Object>> undo() {
        Optional<UndoEntry> entry = undoStack.pop();
        if (entry.isEmpty()) {
            return ResponseEntity.ok(Map.of("undone", false, "reason", "empty stack"));
        }
        UndoEntry e = entry.get();
        try {
            if (e.previousContent() == null) {
                Files.deleteIfExists(e.originalPath());
            } else {
                Files.createDirectories(e.originalPath().getParent());
                Files.writeString(e.originalPath(), e.previousContent());
            }
            return ResponseEntity.ok(Map.of(
                "undone", true,
                "file", e.filename(),
                "restored_to", e.originalPath().toString()
            ));
        } catch (IOException ex) {
            return ResponseEntity.internalServerError()
                .body(Map.of("undone", false, "reason", ex.getMessage()));
        }
    }
}
