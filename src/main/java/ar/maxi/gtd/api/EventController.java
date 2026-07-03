package ar.maxi.gtd.api;

import ar.maxi.gtd.service.Actor;
import ar.maxi.gtd.service.Event;
import ar.maxi.gtd.service.EventLog;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api")
public class EventController {

    private final EventLog eventLog;

    public EventController(EventLog eventLog) {
        this.eventLog = eventLog;
    }

    @GetMapping("/events")
    public List<Map<String, Object>> events(
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(required = false) String actor,
            @RequestParam(required = false) String op) {
        Actor actorFilter = actor == null ? null : Actor.valueOf(actor.toUpperCase());
        return eventLog.tail(limit, actorFilter, op).stream()
            .map(this::toResponse)
            .collect(Collectors.toList());
    }

    private Map<String, Object> toResponse(Event e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", e.id());
        m.put("ts", e.ts());
        m.put("actor", e.actor());
        m.put("kind", e.kind());
        m.put("op", e.op());
        m.put("file", e.file());
        m.put("title", e.title());
        m.put("confirmation", e.confirmation());
        m.put("undoes", e.undoes());
        return m;
    }
}
