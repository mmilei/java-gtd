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
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * Durable, append-only log of the raw chat exchange (user text + the LLM's raw ops response),
 * separate from EventLog's vault-mutation events. Rotated monthly rather than by count, since
 * decision 3 treats retention policy for chat differently from the vault event log: whole months
 * move to .vault-meta/archive/transcript-YYYY-MM.jsonl, never deleted.
 */
@Service
public class TranscriptLog {

    private static final Logger log = LoggerFactory.getLogger(TranscriptLog.class);

    private final Path transcriptFile;
    private final Path archiveDir;
    private final ObjectMapper mapper = new ObjectMapper();
    private final AtomicLong idCounter;
    /** Cheap gate for rotateStaleMonths(): skip the full read/reparse unless the calendar month actually rolled over. */
    private volatile String lastCheckedMonth;

    public TranscriptLog(@Value("${gtd.vault.path}") String vaultPath) {
        Path metaDir = Path.of(vaultPath, ".vault-meta");
        this.transcriptFile = metaDir.resolve("transcript.jsonl");
        this.archiveDir = metaDir.resolve("archive");
        this.idCounter = new AtomicLong(lastIdNumber(readAll()));
    }

    public synchronized ChatMessage append(String role, String text, boolean fallback) {
        ChatMessage stamped = new ChatMessage("", Instant.now().toString(), role, text, fallback).withId(nextId());
        try {
            Files.createDirectories(transcriptFile.getParent());
            Files.writeString(transcriptFile, mapper.writeValueAsString(stamped) + "\n",
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        rotateStaleMonths();
        return stamped;
    }

    /** synchronized on the same monitor as append()/rotateStaleMonths() — otherwise a read here can land mid-rotation and see a truncated transcript.jsonl. */
    public synchronized List<ChatMessage> tail(int limit) {
        List<ChatMessage> all = readAll();
        if (limit <= 0 || all.size() <= limit) return all;
        return all.subList(all.size() - limit, all.size());
    }

    private String nextId() {
        return "t-" + String.format("%06d", idCounter.incrementAndGet());
    }

    private static long lastIdNumber(List<ChatMessage> messages) {
        if (messages.isEmpty()) return 0;
        try {
            return Long.parseLong(messages.get(messages.size() - 1).id().replaceFirst("^t-", ""));
        } catch (Exception e) {
            return 0;
        }
    }

    /** Tolerant of corrupted/partial lines — skips them rather than failing startup. */
    private List<ChatMessage> readAll() {
        if (!Files.exists(transcriptFile)) return List.of();
        try {
            List<ChatMessage> messages = new ArrayList<>();
            for (String line : Files.readAllLines(transcriptFile)) {
                if (line.isBlank()) continue;
                try {
                    messages.add(mapper.readValue(line, ChatMessage.class));
                } catch (Exception e) {
                    log.warn("TranscriptLog: skipping corrupt line: {}", e.getMessage());
                }
            }
            return messages;
        } catch (IOException e) {
            log.warn("TranscriptLog: could not read {}: {}", transcriptFile, e.getMessage());
            return List.of();
        }
    }

    /**
     * Moves any entry from a past calendar month out to its own archive file, keeping only the
     * current month active. Gated on lastCheckedMonth so a normal append doesn't re-read/re-parse
     * the whole active file — the full pass only runs once per calendar month rollover.
     */
    private void rotateStaleMonths() {
        String currentMonth = Instant.now().toString().substring(0, 7);
        if (currentMonth.equals(lastCheckedMonth)) return;
        lastCheckedMonth = currentMonth;

        List<ChatMessage> all = readAll();
        Map<Boolean, List<ChatMessage>> partitioned = all.stream()
            .collect(Collectors.partitioningBy(m -> m.ts().startsWith(currentMonth)));
        List<ChatMessage> keep = partitioned.get(true);
        List<ChatMessage> stale = partitioned.get(false);
        if (stale.isEmpty()) return;

        Map<String, List<ChatMessage>> byMonth = stale.stream()
            .collect(Collectors.groupingBy(m -> m.ts().substring(0, 7)));
        try {
            Files.createDirectories(archiveDir);
            for (var entry : byMonth.entrySet()) {
                Path archiveFile = archiveDir.resolve("transcript-" + entry.getKey() + ".jsonl");
                StringBuilder sb = new StringBuilder();
                for (ChatMessage m : entry.getValue()) sb.append(mapper.writeValueAsString(m)).append('\n');
                Files.writeString(archiveFile, sb.toString(), StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            }
            StringBuilder activeLines = new StringBuilder();
            for (ChatMessage m : keep) activeLines.append(mapper.writeValueAsString(m)).append('\n');
            Files.writeString(transcriptFile, activeLines.toString(),
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException e) {
            log.warn("TranscriptLog: monthly rotation failed, stale entries left in active file: {}", e.getMessage());
        }
    }
}
