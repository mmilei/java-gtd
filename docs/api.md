[← README](../README.md) · [Architecture](architecture.md) · [Setup](setup.md)

# API reference

REST API that classifies natural language into GTD buckets and files the results as Markdown notes in an Obsidian vault. 24 endpoints, all under `/api`.

## The chat endpoint

Send a message in plain language. The LLM runs a GTD-inspired decision tree (actionable? 2-minute rule? delegate? today/backlog/someday?) and returns one or more operations. Creations are filed immediately; destructive edits ask for confirmation first.

```
POST /api/chat
{"message": "review the pull request before end of day"}

→ {
    "fallback": false,
    "ops": [{ "op": "create", "filed": true, "bucket": "today",
              "title": "Review the pull request",
              "file": "20260624-181203-review-the-pull-request.md" }]
  }
```

One message can contain multiple operations:

```
{"message": "I finished the code review, and move the deployment task to today"}

→ { "fallback": false,
    "ops": [
      { "op": "done", "filed": true, "file": "20260624-173158-code-review.md", "title": "Code review" },
      { "op": "move", "filed": true, "file": "20260624-150342-deployment.md", "title": "Deployment", "new_bucket": "today" }
    ] }
```

### Op types

| `op` | Effect | Response fields |
|------|--------|-----------------|
| `create` | New note filed to a bucket | `filed`, `bucket`, `title`, `file`, `confirmed` |
| `done` | Mark completed, moves the file to `brain/done/` | `filed`, `file`, `title` |
| `move` | Reclassify to another bucket (physical move, one folder per bucket) | `filed`, `file`, `title`, `new_bucket` |
| `edit` / `update` | Replace / append to body — **not applied**: returns `requires_confirmation: true` with `target_file`, `current_body`, `proposed_body`; client approves via `POST /api/chat/confirm` | `title`, diff fields, `chat_ref` |
| `dismiss` | Discard an existing task, moves the file to `brain/discard/` — also requires confirmation | `target_file`, `title`, `chat_ref` |
| `patch` | Update metadata (tags, due, today_since) | `filed`, `file` |

Notes classified as `now` or `discard` are not filed (`discard` is logged to `.vault-meta/discard-log.jsonl`). `fallback: true` means the detailed level-2 prompt was needed — see [architecture](architecture.md). When `fallback` is true, `create` also writes `confirmed: false` on the new task's frontmatter — a low-confidence flag reviewed later via `POST /api/items/{file}/confirm`.

## Approving an LLM proposal

`edit`, `update`, and `dismiss` never mutate the vault directly — they come back with `requires_confirmation: true` and a `chat_ref` pointing at the chat message that proposed them. The client approves the proposal:

```
POST /api/chat/confirm
{"target_file": "20260702-090000-call-the-dentist.md", "op": "edit",
 "proposed_body": "…", "chat_ref": "t-000045"}

→ { "confirmed": true, "file": "20260702-090000-call-the-dentist.md", "op": "edit" }
```

This is distinct from the generic `PUT /api/items/{file}/body` and `POST /api/items/{file}/dismiss` endpoints a human uses to edit a task directly from the UI. Both end up calling the same underlying mutation, but only the confirm path records `actor: llm` + `confirmation: confirmed` in the event log — direct edits record `actor: user`. That's what lets the history view (see below) tell "you typed this" apart from "you approved what the LLM suggested".

## Endpoints

### Chat & AI

| Method | Path | Description |
|--------|------|-------------|
| `POST` | `/api/chat` | Classify and execute GTD operations from natural language. Returns `{ fallback, ops[] }` |
| `POST` | `/api/chat/confirm` | Approve an `edit`/`update`/`dismiss` the LLM proposed — `{ "target_file", "op", "proposed_body"?, "chat_ref"? }` |
| `GET` | `/api/chat/history` | Raw chat transcript, each pending `requires_confirmation` op tagged `resolved: true/false` — `?limit=N` (default 50) |
| `POST` | `/api/transcribe` | Audio → text via Groq Whisper. multipart/form-data, `audio` field. Returns `{ text }` |
| `POST` | `/api/items/{filename}/markdownify` | AI-enrich a note: rewrites body, infers tags. Returns `{ file, body, tags }` |
| `GET` | `/api/providers` | LLM providers with live status. Returns `{ active, providers[] }` |
| `POST` | `/api/providers/select` | Switch active provider — `{ "provider": "groq" \| "ollama" }` |

### Reading

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/api/buckets` | All open items grouped by bucket |
| `GET` | `/api/buckets/{bucket}` | Open items in one bucket |
| `GET` | `/api/today` | Open items in *today* |
| `GET` | `/api/items/{filename}` | Single item (frontmatter + body) |
| `GET` | `/api/tags` | Unique tags with per-bucket counts |
| `GET` | `/api/stats` | Item counts per bucket plus total |
| `GET` | `/api/history` | Recently completed/dismissed items, read straight from `brain/done`/`brain/discard` — `?limit=N` (default 20) |
| `GET` | `/api/review` | Weekly review data — `staleDays` (3), `dueDays` (7), `completedDays` (7). Returns `{ stale_today, due_this_week, completed_this_week, week_stats }` |
| `GET` | `/api/events` | Durable mutation log (`.vault-meta/events.jsonl`) — `?limit=N` (default 50), `?actor=user\|llm`, `?op=create\|move\|...` |
| `GET` | `/api/undo/stack` | Non-destructive peek at what's undoable, most recent first, capped at 50 |

### Mutations

| Method | Path | Description |
|--------|------|-------------|
| `POST` | `/api/items/{filename}/done` | Mark completed, move to `brain/done/` |
| `POST` | `/api/items/{filename}/dismiss` | Discard (decided not to do it), move to `brain/discard/` |
| `POST` | `/api/items/{filename}/move` | Reclassify — `{ "bucket": "...", "due": "YYYY-MM-DD" }` |
| `PUT` | `/api/items/{filename}/body` | Replace body — `{ "body": "..." }` |
| `PUT` | `/api/items/{filename}/meta` | Update metadata — any of `title`, `tags`, `due`, `today_since`, `delegado_a`, `area`, `estimate_minutes`, `confirmed` |
| `POST` | `/api/items/{filename}/confirm` | Flip a low-confidence task's `confirmed: false` → `true` after review |
| `POST` | `/api/undo` | Undo the most recent mutation, durable and restart-safe (`EventLog`-backed, cap 50) |

## Item shape

```json
{
  "type": "action",
  "title": "Call the dentist",
  "bucket": "today",
  "status": "open",
  "created": "2026-07-02",
  "due": "2026-07-03",
  "today_since": "2026-07-02",
  "estimate_minutes": 15,
  "tags": ["gtd", "action", "health", "calls"],
  "body": "…",
  "file": "20260702-090000-call-the-dentist.md"
}
```

Once a task is completed or dismissed, `status` becomes `done`/`dismissed`, the file moves to `brain/done/`/`brain/discard/`, and a write-once `done_date`/`discarded_date` is added (never overwritten by later edits). `confirmed: false` appears only on tasks the classifier filed with low confidence — its absence, `true`, or `null` all mean confirmed.

## Buckets

| Bucket | Meaning |
|--------|---------|
| `today` | Do it today |
| `backlog` | Do it eventually, no date |
| `waiting` | Delegated — waiting on someone |
| `someday` | Maybe someday |
| `reference` | No action needed, keep for reference |
| `now` | Do it right now (2-min rule) — not filed |
| `discard` | Not worth keeping — not filed |
