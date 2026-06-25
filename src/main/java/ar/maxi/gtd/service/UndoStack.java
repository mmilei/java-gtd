package ar.maxi.gtd.service;

import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Optional;

@Component
public class UndoStack {

    private static final int CAPACITY = 10;

    public record UndoEntry(String filename, Path originalPath, String previousContent) {}

    private final Deque<UndoEntry> stack = new ArrayDeque<>();

    public synchronized void push(UndoEntry entry) {
        if (stack.size() >= CAPACITY) stack.removeLast();
        stack.push(entry);
    }

    public synchronized Optional<UndoEntry> pop() {
        return stack.isEmpty() ? Optional.empty() : Optional.of(stack.pop());
    }
}
