[← README](../README.md) · [API reference](api.md) · [Setup](setup.md)

# Architecture

## Classification pipeline

```
POST /api/chat  ("call the dentist tomorrow morning")
        │
        ▼
 ChatController          ← validates, dispatches each op returned by the classifier
        │
        ▼
 ClassifierService       ← runs the GTD decision tree on the LLM
  ├─ prompts/classifier.st            level 1: lightweight prompt
  ├─ prompts/classifier-fallback.st   level 2: detailed prompt with examples,
  │                                    runs only if level 1 fails to parse
  └─ resolveTargetFile()              deterministic title→filename resolution
        │                              for edit/move/done/dismiss ops
        ▼
 LlmProviderService      ← routes the call to the active provider
  ├─ Groq (Llama 3.3-70b, OpenAI-compatible endpoint)
  └─ Ollama (local, optional — ollama.enabled=true)
        │
        ▼
 VaultService            ← reads/writes .md notes, synchronized mutations
  └─ MarkdownSerializer  ← SnakeYAML frontmatter parse/serialize
        │
        ▼
 Obsidian vault (plain Markdown files on disk)
```

## Services

| Service | Responsibility |
|---------|---------------|
| `ClassifierService` | Two-level prompting, JSON parsing, target-file resolution for follow-up ops |
| `LlmProviderService` | Runtime provider switching (Groq/Ollama), availability checks |
| `VaultService` | All vault I/O: create, mutate, move between buckets, startup self-healing migrations |
| `MarkdownifyService` | AI enrichment of a note (rewrites body, infers tags) |
| `UndoStack` | Thread-safe `Deque` of pre-mutation snapshots, cap 10 |
| `MarkdownSerializer` | YAML frontmatter ↔ map, resilient to malformed YAML |

## Key design decisions

- **Two-level prompting** — a cheap prompt handles easy inputs; the detailed fallback runs only on parse failure or suspicious output. Cuts latency and cost on the common path. The response flags `fallback: true` when level 2 ran.
- **Deterministic target resolution** — the LLM identifies which existing task a follow-up refers to *by title*; the backend resolves the actual filename with accent-insensitive matching. The LLM never invents filenames.
- **Confirmation for destructive ops** — `edit`, `update`, and `dismiss` return `requires_confirmation: true` with a current/proposed body diff instead of mutating immediately; the client confirms via the regular REST endpoints.
- **Undo everywhere** — every mutation pushes a snapshot (`filename`, path, previous content) to an in-memory stack; `POST /api/undo` restores the last one. Resets on restart.
- **Plain Markdown storage** — no database. Notes are portable, greppable, and remain fully editable in Obsidian while the API runs. Writes are synchronized and moves are atomic with a fallback that leaves the file untouched at origin on failure.
- **Self-healing startup** — migrations normalize legacy notes on boot (missing `today_since`, malformed timestamps, bucket/folder mismatches), each individually toggleable.
- **Virtual threads** — `spring.threads.virtual.enabled=true`; blocking file and LLM I/O without pool tuning.

## GTD buckets

The buckets are inspired by the lists in David Allen's [Getting Things Done](https://en.wikipedia.org/wiki/Getting_Things_Done) workflow, plus two transient outcomes (`now`, `discard`) that the decision tree can produce but never files.

| Bucket | Meaning | Filed to |
|--------|---------|----------|
| `today` | Do it today | `brain/inbox/` |
| `backlog` | Do it eventually | `brain/inbox/` |
| `waiting` | Delegated, waiting on someone | `brain/inbox/` |
| `someday` | Maybe someday | `brain/someday/` |
| `reference` | Keep for reference, no action | `brain/resources/` |
| `now` | 2-minute rule: do it right now | not filed |
| `discard` | Not worth keeping | not filed — logged to `.vault-meta/discard-log.jsonl` |

## Note format

```markdown
---
type: action
title: "Call the dentist"
bucket: today
status: open
created: 2026-07-02
due: 2026-07-03
today_since: 2026-07-02
estimate_minutes: 15
tags: [gtd, action, health, calls]
---

Optional free-form Markdown body.
```

The classifier infers context tags and a time estimate from the message; the frontend uses `estimate_minutes` to project when the day's list finishes.

## Testing strategy

70 tests. Controllers are tested with `@WebMvcTest` + Mockito (HTTP contract, op dispatch, confirmation flow); `VaultServiceTest` exercises real filesystem I/O against `@TempDir` vaults, including the startup migrations and conflicting-move edge cases.
