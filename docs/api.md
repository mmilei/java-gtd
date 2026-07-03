[← README](../README.md) · [Architecture](architecture.md) · [Setup](setup.md)

# API reference

REST API that classifies natural language into GTD buckets and files the results as Markdown notes in an Obsidian vault. 19 endpoints, all under `/api`.

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
| `create` | New note filed to a bucket | `filed`, `bucket`, `title`, `file` |
| `done` | Mark completed | `filed`, `file`, `title` |
| `move` | Reclassify to another bucket | `filed`, `file`, `title`, `new_bucket` |
| `edit` / `update` | Replace / append to body — **not applied**: returns `requires_confirmation: true` with `target_file`, `current_body`, `proposed_body`; client applies via `PUT /api/items/{file}/body` | `title`, diff fields |
| `dismiss` | Discard an existing task — also requires confirmation | `target_file`, `title` |
| `patch` | Update metadata (tags, due, today_since) | `filed`, `file` |

Notes classified as `now` or `discard` are not filed (`discard` is logged to `.vault-meta/discard-log.jsonl`). `fallback: true` means the detailed level-2 prompt was needed — see [architecture](architecture.md).

## Endpoints

### Chat & AI

| Method | Path | Description |
|--------|------|-------------|
| `POST` | `/api/chat` | Classify and execute GTD operations from natural language. Returns `{ fallback, ops[] }` |
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
| `GET` | `/api/history` | Recently completed/dismissed items — `?limit=N` (default 20) |
| `GET` | `/api/review` | Weekly review data — `staleDays` (3), `dueDays` (7), `completedDays` (7). Returns `{ stale_today, due_this_week, completed_this_week, week_stats }` |

### Mutations

| Method | Path | Description |
|--------|------|-------------|
| `POST` | `/api/items/{filename}/done` | Mark completed |
| `POST` | `/api/items/{filename}/dismiss` | Discard (decided not to do it) |
| `POST` | `/api/items/{filename}/move` | Reclassify — `{ "bucket": "...", "due": "YYYY-MM-DD" }` |
| `PUT` | `/api/items/{filename}/body` | Replace body — `{ "body": "..." }` |
| `PUT` | `/api/items/{filename}/meta` | Update metadata — any of `title`, `tags`, `due`, `today_since`, `delegado_a`, `area`, `estimate_minutes` |
| `POST` | `/api/undo` | Undo the last mutation (in-memory stack, cap 10, resets on restart) |

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
