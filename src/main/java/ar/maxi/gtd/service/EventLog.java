package ar.maxi.gtd.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * Durable, append-only log of every vault mutation (JSONL, one line per event) plus the undo
 * kind that reverses one. Never rewrites history except for rotation (moving old lines to
 * .vault-meta/archive/, never deleting) — "what's undoable" is derived fresh from the log on
 * every call rather than cached in memory, so a restart needs no separate replay step and can
 * never drift from what's actually on disk.
 */
@Service
public class EventLog {

    private static final Logger log = LoggerFactory.getLogger(EventLog.class);
    private static final int MAX_ACTIVE_EVENTS = 1000;
    private static final int MAX_UNDO_DEPTH = 50;

    private final Path eventsFile;
    private final Path archiveDir;
    private final ObjectMapper mapper = new ObjectMapper();
    private final AtomicLong idCounter;

    public EventLog(@Value("${gtd.vault.path}") String vaultPath) {
        Path metaDir = Path.of(vaultPath, ".vault-meta");
        this.eventsFile = metaDir.resolve("events.jsonl");
        this.archiveDir = metaDir.resolve("archive");
        this.idCounter = new AtomicLong(lastIdNumber(readAll()));
    }

    public synchronized Event append(Event event) {
        Event stamped = event.withId(nextId());
        try {
            Files.createDirectories(eventsFile.getParent());
            Files.writeString(eventsFile, mapper.writeValueAsString(stamped) + "\n",
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        rotateIfNeeded();
        return stamped;
    }

    public List<Event> tail(int limit, Actor actorFilter, String opFilter) {
        List<Event> filtered = readAll().stream()
            .filter(e -> actorFilter == null || actorFilter.toJson().equals(e.actor()))
            .filter(e -> opFilter == null || opFilter.equals(e.op()))
            .collect(Collectors.toList());
        if (limit <= 0 || filtered.size() <= limit) return filtered;
        return filtered.subList(filtered.size() - limit, filtered.size());
    }

    /** Most recent mutation not already undone — undo is strictly sequential, one step at a time. */
    public Optional<Event> nextUndoable() {
        List<Event> mutations = undoneFiltered(readAll());
        return mutations.isEmpty() ? Optional.empty() : Optional.of(mutations.get(mutations.size() - 1));
    }

    /** Non-destructive peek of what's available to undo, most recent first, capped at MAX_UNDO_DEPTH. */
    public List<Event> undoableStack() {
        List<Event> mutations = undoneFiltered(readAll());
        List<Event> capped = mutations.size() > MAX_UNDO_DEPTH
            ? mutations.subList(mutations.size() - MAX_UNDO_DEPTH, mutations.size())
            : mutations;
        List<Event> mostRecentFirst = new ArrayList<>(capped);
        Collections.reverse(mostRecentFirst);
        return mostRecentFirst;
    }

    private static List<Event> undoneFiltered(List<Event> all) {
        Set<String> undoneIds = all.stream()
            .filter(e -> "undo".equals(e.kind()) && e.undoes() != null)
            .map(Event::undoes)
            .collect(Collectors.toSet());
        return all.stream()
            .filter(e -> "mutation".equals(e.kind()))
            .filter(e -> !undoneIds.contains(e.id()))
            .collect(Collectors.toList());
    }

    private String nextId() {
        return "e-" + String.format("%06d", idCounter.incrementAndGet());
    }

    private static long lastIdNumber(List<Event> events) {
        if (events.isEmpty()) return 0;
        try {
            return Long.parseLong(events.get(events.size() - 1).id().replaceFirst("^e-", ""));
        } catch (Exception e) {
            return 0;
        }
    }

    /** Tolerant of corrupted/partial lines (e.g. a crash mid-write) — skips them rather than failing startup. */
    private List<Event> readAll() {
        if (!Files.exists(eventsFile)) return List.of();
        try {
            List<Event> events = new ArrayList<>();
            for (String line : Files.readAllLines(eventsFile)) {
                if (line.isBlank()) continue;
                try {
                    events.add(mapper.readValue(line, Event.class));
                } catch (Exception e) {
                    log.warn("EventLog: skipping corrupt line: {}", e.getMessage());
                }
            }
            return events;
        } catch (IOException e) {
            log.warn("EventLog: could not read {}: {}", eventsFile, e.getMessage());
            return List.of();
        }
    }

    /** Compacts events.jsonl to the last MAX_ACTIVE_EVENTS, moving the overflow to .vault-meta/archive/events-YYYY-MM.jsonl — never deletes. */
    private void rotateIfNeeded() {
        List<Event> all = readAll();
        if (all.size() <= MAX_ACTIVE_EVENTS) return;

        int cut = all.size() - MAX_ACTIVE_EVENTS;
        List<Event> overflow = all.subList(0, cut);
        List<Event> keep = all.subList(cut, all.size());
        String month = overflow.get(overflow.size() - 1).ts().substring(0, 7); // YYYY-MM
        Path archiveFile = archiveDir.resolve("events-" + month + ".jsonl");

        try {
            Files.createDirectories(archiveDir);
            StringBuilder overflowLines = new StringBuilder();
            for (Event e : overflow) overflowLines.append(mapper.writeValueAsString(e)).append('\n');
            Files.writeString(archiveFile, overflowLines.toString(), StandardOpenOption.CREATE, StandardOpenOption.APPEND);

            StringBuilder activeLines = new StringBuilder();
            for (Event e : keep) activeLines.append(mapper.writeValueAsString(e)).append('\n');
            Files.writeString(eventsFile, activeLines.toString(),
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException e) {
            log.warn("EventLog: rotation failed, active file left over MAX_ACTIVE_EVENTS: {}", e.getMessage());
        }
    }
}
