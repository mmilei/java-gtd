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
 ClassifierService       ← runs the GTD decision tree, then a per-op follow-up
  ├─ Prompt A — Triage    (classifier-triage.st / classifier-triage-fallback.st)
  │   two-level: cheap prompt first, detailed one only if level 1 fails to
  │   parse. Emits ops carrying a per-op `confirmed` flag. (LlmAction.TRIAGE)
  │
  ├─ for each filed "create" op, exactly one follow-up:
  │   ├─ confirmed   → Prompt B — Enrichment (classifier-enrich.st)
  │   │                  fills area/tags/project/location/estimate_minutes
  │   │                  (LlmAction.ENRICHMENT)
  │   └─ !confirmed  → Prompt C — Resolver (classifier-resolver.st)
  │                      last chance to re-decide bucket/area/tags/confirmed
  │                      before the task enters /api/unconfirmed (LlmAction.RESOLVER)
  │
  └─ resolveTargetFile()              deterministic title→filename resolution
        │                              for edit/move/done/dismiss ops
        ▼
 LlmProviderService      ← routes each LlmAction (Triage/Enrichment/Resolver)
                            independently to its own active provider
  ├─ Groq (Llama 3.3-70b, OpenAI-compatible endpoint)
  ├─ Anthropic (Claude, optional — anthropic.enabled=true, spring-ai-anthropic-spring-boot-starter)
  └─ Ollama (local, optional — ollama.enabled=true, 30s keep-alive + startup warmup)
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
| `ClassifierService` | Three-prompt pipeline (Triage → Enrichment\|Resolver), JSON parsing, target-file resolution for follow-up ops |
| `LlmProviderService` | Per-`LlmAction` (Triage/Enrichment/Resolver) provider routing (Groq/Ollama/Anthropic), availability checks, Ollama keep-alive + startup warmup |
| `VaultService` | All vault I/O: create, mutate, move between buckets, startup self-healing migrations, undo |
| `EventLog` | Durable, append-only mutation log (`.vault-meta/events.jsonl`) — backs undo and the history/events API |
| `TranscriptLog` | Durable, append-only raw chat log (`.vault-meta/transcript.jsonl`) — backs `GET /api/chat/history` |
| `MarkdownifyService` | AI enrichment of a note (rewrites body, infers tags) |
| `MarkdownSerializer` | YAML frontmatter ↔ map, resilient to malformed YAML |

## Key design decisions

- **Triage → Enrichment/Resolver pipeline** — Prompt A (Triage) always runs two-level (cheap prompt, detailed fallback only on parse failure or suspicious output — the response flags `fallback: true` when level 2 ran) and emits a per-op `confirmed` flag reflecting real classification confidence, not just whether the fallback prompt fired. Every filed `create` op then gets exactly one follow-up: Prompt B (Enrichment) when confirmed, Prompt C (Resolver) when not. Both follow-ups are best-effort — a parse failure just leaves the op exactly as Prompt A left it.
- **Per-action provider routing** — Groq/Ollama/Anthropic are selected independently per `LlmAction` (`TRIAGE`/`ENRICHMENT`/`RESOLVER`), not globally: switching Triage to Ollama leaves Enrichment/Resolver wherever they were. `POST /api/providers/select` takes `{action, provider}`; `GET /api/providers` returns one entry per action. Ollama calls carry a 30s `keep_alive` plus a startup warmup thread so back-to-back pipeline calls don't each pay a cold model load; an Ollama healthcheck failure falls back to Groq for that one call without touching the stored preference. Anthropic (via `spring-ai-anthropic-spring-boot-starter`, native tool — no hand-rolled HTTP client) is off by default (`anthropic.enabled=false`) and has no infra fallback: it's a paid cloud API like Groq, so a runtime failure propagates to the caller instead of auto-switching.
- **Bounded open-tasks context** — before serializing the open tasks into the prompt, a keyword pre-filter (`filterRelevantTasks`) keeps the ~15 tasks most relevant to the message: titles sharing words with it come first (matching is accent/case-insensitive, so `colchón` overlaps `colchon`), and any remaining slots are padded with the other tasks in original list order — a `done`/`edit`/`move`/`dismiss` target whose title shares no word with the message is never evicted by a few incidental matches. A coarser 6000-char/80-item truncation remains as a final safety net.
- **Deterministic target resolution** — the LLM identifies which existing task a follow-up refers to *by title*; the backend resolves the actual filename with accent-insensitive matching. The LLM never invents filenames.
- **Confirmation for destructive ops** — `edit`, `update`, and `dismiss` return `requires_confirmation: true` with a current/proposed body diff and a `chat_ref`; the client approves via `POST /api/chat/confirm`, which records the approval as `actor: llm` in the event log, distinct from a human editing the same task directly.
- **Durable, restart-safe undo** — every mutation appends an event to `.vault-meta/events.jsonl` *after* its write succeeds (not before, avoiding a phantom entry if the write fails). `POST /api/undo` inverts the most recent not-yet-undone one by moving the file back from its post-mutation path to its pre-mutation path (if they differ) and restoring `previous_content` — one rule for create/edit/move/done/dismiss alike. Undo depth 50, survives a restart because nothing is cached in memory: "what's undoable" is derived fresh from the log every time. See [Event log & undo](#event-log--undo) below.
- **Plain Markdown storage** — no database. Notes are portable, greppable, and remain fully editable in Obsidian while the API runs. Writes are synchronized and moves are atomic with a fallback that leaves the file untouched at origin on failure.
- **Self-healing startup** — migrations normalize legacy notes on boot (missing `today_since`, malformed timestamps, bucket/folder mismatches, the one-time folder-per-bucket split), each individually toggleable. See [Startup migrations & kill-switches](#startup-migrations--kill-switches) below.
- **Virtual threads** — `spring.threads.virtual.enabled=true`; blocking file and LLM I/O without pool tuning.

## Startup migrations & kill-switches

`VaultService` runs a set of **self-healing migrations** in its constructor, on every application start. They exist because the vault is plain Markdown a human also edits in Obsidian: notes drift from the current schema (a hand-edited frontmatter, a value written by an older version, a `moveBucket` that half-failed), and each boot is a chance to quietly normalize what it finds back to the current shape.

All five share the same contract:

- **Self-healing** — they read the on-disk notes and fix what's out of shape, rather than assuming the data is already correct.
- **Idempotent** — once a note is normalized there's nothing left for that migration to do, so re-running it on the next boot is a no-op. They never double-apply, and running them a hundred times is the same as running them once.
- **Boot-time, unconditional** — they run on every startup (there's no "already migrated" flag file to gate them); the idempotency above is what makes that cheap and safe. Non-GTD notes (no `bucket` key — index pages, freeform ideas) are left untouched.

All five are gated together by a single boolean, `gtd.vault.migrations-enabled` in `application.properties`, defaulting to `true`. Setting it to `false` is a **kill-switch**: it skips all five on the next boot — an escape hatch for a bad interaction with unusual on-disk data, or simply to freeze the vault's current layout. Turning it off never deletes data; it only stops these normalization passes from running. (The five never needed independent toggles in practice — every deployment ran with all five on.)

| Migration | What it normalizes |
|-----------|--------------------|
| `migrateFolderSplit()` | One-time move of notes out of the legacy shared `brain/inbox/` (and done/dismissed items sitting in `someday`/`resources`) into the folder-per-bucket layout. |
| `migrateTodaySince()` | Backfills a missing `today_since` on `today` notes from their `created` date. |
| `migrateTimestamps()` | Rewrites full ISO datetime values left in frontmatter (`...T...`) to plain dates. |
| `migrateBucketMismatch()` | Relocates a note whose `bucket` field disagrees with the directory it sits in, and quarantines filename duplicates across bucket dirs (loser marked dismissed, moved to `brain/.archive/duplicates/` — never deleted). |
| `migrateRelatedPeopleToList()` | Rewrites a legacy scalar `related_people: Juan` as a single-element list `["Juan"]`. |

They are invoked in that order in the constructor — folder-split first (it moves files into the right directories) so that bucket-mismatch afterwards sees each note already in its bucket folder.

## Event log & undo

Every mutation — `create`, `move`, `done`, `dismiss`, `edit`/`update` (via `PUT`/`POST` or the confirm endpoint), `patch` — appends one JSON line to `.vault-meta/events.jsonl`:

```json
{
  "id": "e-000123", "ts": "2026-07-03T14:22:31Z",
  "actor": "user | llm", "kind": "mutation | undo", "op": "create | edit | update | move | dismiss | done | patch",
  "file": "20260703-...md", "title": "...",
  "path_before": "brain/backlog/...md", "path_after": "brain/today/...md",
  "previous_content": "... (null if op=create)",
  "confirmation": "none | confirmed", "undoes": "e-000120 (only on kind=undo)", "chat_ref": "t-000045 (null if manual)"
}
```

- **`actor`** distinguishes a direct edit (`BucketController`, e.g. `PUT /api/items/{file}/body`) from an LLM-dispatched one (`ChatController`).
- **`POST /api/undo`** finds the most recent `mutation` event without a matching `undo` event (`undoes` pointing at it), inverts it, and appends an `undo` event referencing it — so undo is strictly sequential and idempotent even across restarts.
- **Retention** — the log is never truncated by deleting; once it exceeds ~1000 active lines, the oldest are moved to `.vault-meta/archive/events-YYYY-MM.jsonl`.
- **Raw chat, separately** — `TranscriptLog` persists every user message and the LLM's raw ops response to `.vault-meta/transcript.jsonl` (rotated monthly to `.vault-meta/archive/`), so `GET /api/chat/history` can rehydrate a `requires_confirmation` card that's still pending after a page reload — it cross-references `EventLog` for a `confirmation: confirmed` event with a matching `chat_ref` to know whether it's already been resolved.

## GTD buckets

The buckets are inspired by the lists in David Allen's [Getting Things Done](https://en.wikipedia.org/wiki/Getting_Things_Done) workflow, plus two transient outcomes (`now`, `discard`) that the decision tree can produce but never files. `done` and `dismissed` are not classifier buckets — they're the terminal `status` a task reaches via `POST /api/items/{file}/done|dismiss`, moving it to its own folder while keeping its original `bucket` value for reference.

| Bucket | Meaning | Filed to |
|--------|---------|----------|
| `today` | Do it today | `brain/today/` |
| `backlog` | Do it eventually | `brain/backlog/` |
| `waiting` | Delegated, waiting on someone | `brain/waiting/` |
| `someday` | Maybe someday | `brain/someday/` |
| `reference` | Keep for reference, no action | `brain/resources/` |
| `now` | 2-minute rule: do it right now | not filed |
| `discard` | Not worth keeping | not filed — logged to `.vault-meta/discard-log.jsonl` |

Terminal states, reached from any bucket: `done` → `brain/done/` (`done_date` set once), `dismissed` → `brain/discard/` (`discarded_date` set once). One folder per bucket/state — no shared inbox, so every transition (including between `today`/`backlog`/`waiting`) is a plain atomic move.

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
tags: [health, calls]
---

Optional free-form Markdown body.
```

The classifier infers context tags and a time estimate from the message; the frontend uses `estimate_minutes` to project when the day's list finishes. It may also infer optional `project` (codebase the task belongs to), `location` (physical place implied by the task, e.g. `hardware store`), and `area` (life area — one of the closed vocabulary configured via `gtd.areas`, English by default and localizable in `application-local.properties`; matching is accent/case-insensitive, the canonical config spelling is persisted, and an out-of-vocabulary value is silently dropped) — each omitted when not clearly inferable. `confirmed: false` may also appear when Prompt A (Triage) or Prompt C (Resolver) ended the pipeline still unsure about the classification — absent, `true`, or `null` all mean confirmed (chosen so no existing task needs migrating).

## Testing strategy

157 tests. Controllers are tested with `@WebMvcTest` + Mockito (HTTP contract, op dispatch, confirmation flow); `VaultServiceTest` exercises real filesystem I/O against `@TempDir` vaults, including the startup migrations, the folder-per-bucket split, and undo end-to-end (create+undo, move+undo without leaving a duplicate); `EventLogTest` covers append/tail/rotation and tolerance of corrupted lines.
