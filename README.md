# java-gtd

![Java](https://img.shields.io/badge/Java-21-blue?logo=openjdk)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.3-brightgreen?logo=springboot)
![Spring AI](https://img.shields.io/badge/Spring%20AI-1.0-green?logo=spring)
![License](https://img.shields.io/badge/license-MIT-lightgrey)
![Tests](https://img.shields.io/badge/tests-27%20passing-success)

REST API that classifies natural language input into GTD (Getting Things Done) buckets and files the results as Markdown notes in an Obsidian vault.

Built with **Spring Boot 3** + **Spring AI** + **Groq** (Llama 3.3-70b).

---

## How it works

Send a message in plain language. The LLM runs the GTD decision tree (is it actionable? can it be done in 2 min? does it need delegation?) and returns a structured classification. If the item is worth filing, it gets written to the vault as a Markdown note with YAML frontmatter.

One message can contain multiple tasks — create, done, update, move, edit, and dismiss operations are all supported in a single call.

```
POST /api/chat
{"message": "review the pull request before end of day"}

→ {
    "fallback": false,
    "ops": [{ "op": "create", "filed": true, "bucket": "today", "title": "Review the pull request", "file": "20260624-181203-review-the-pull-request.md" }]
  }
```

Multi-task example:

```
POST /api/chat
{"message": "I finished the code review, and add to the deployment task that we need to run the migration scripts first"}

→ {
    "fallback": false,
    "ops": [
      { "op": "done",   "filed": true, "file": "20260624-173158-code-review.md" },
      { "op": "update", "filed": true, "file": "20260624-150342-deployment.md", "appended": "run migration scripts before deploying" }
    ]
  }
```

Items classified as `discard` are not filed but are logged to `.vault-meta/discard-log.jsonl` for later review. A two-level prompt strategy is used: lightweight prompt first, falling back to a more detailed one if the response fails to parse or everything comes back as `discard`/`now`. When the fallback triggers, `fallback: true` is returned:

```
POST /api/chat
{"message": "someday I'd like to learn to play the guitar"}

→ {
    "fallback": true,
    "ops": [{ "op": "create", "filed": true, "bucket": "someday", "title": "Learn to play the guitar", "file": "20260624-184512-learn-to-play-the-guitar.md" }]
  }
```

The lightweight prompt classified this as `discard` (no clear action), so the fallback prompt re-evaluated it and correctly filed it as `someday`.

---

## Architecture

```
HTTP request
     │
     ▼
ChatController           ← parses request, dispatches ops
     │
     ▼
ClassifierService        ← calls Groq via Spring AI
  ├─ classifier.st       ← lightweight prompt (level 1)
  └─ classifier-fallback.st  ← detailed prompt with examples (level 2, on parse failure)
     │
     ▼
VaultService             ← reads/writes .md files with YAML frontmatter
  └─ MarkdownSerializer  ← SnakeYAML parse/serialize, captures YAMLException
     │
     ▼
Obsidian vault (plain Markdown files on disk)
```

**Key design decisions:**
- **Two-level prompting:** cheap prompt first, expensive fallback only when needed. Reduces latency and cost on easy inputs.
- **Plain Markdown output:** vault files are regular `.md` with YAML frontmatter — no database, no lock-in, readable by any editor.
- **Multi-op in a single request:** one natural language message can create, complete, and update multiple items atomically.
- **Virtual threads:** enabled via `spring.threads.virtual.enabled=true` for non-blocking I/O on file operations.

---

## Endpoints

| Method | Path | Description |
|--------|------|-------------|
| `POST` | `/api/chat` | Classify and execute one or more GTD operations from a natural language message (create / done / update / move / edit / dismiss). Returns `{ fallback, ops[] }` |
| `GET` | `/api/buckets` | List all open items grouped by bucket |
| `GET` | `/api/buckets/{bucket}` | List open items in a specific bucket |
| `GET` | `/api/today` | List open items in the *today* bucket |
| `GET` | `/api/items/{filename}` | Fetch a single item by filename |
| `POST` | `/api/items/{filename}/done` | Mark an item as completed |
| `POST` | `/api/items/{filename}/dismiss` | Discard an item (decided not to do it) |
| `POST` | `/api/items/{filename}/move` | Reclassify an item to another bucket — `{ "new_bucket": "...", "due": "YYYY-MM-DD" }` |
| `PUT` | `/api/items/{filename}/body` | Replace the body of an existing item — `{ "body": "..." }` |
| `GET` | `/api/stats` | Item counts per bucket plus total |
| `GET` | `/api/history` | Recently completed/dismissed items, sorted by date — `?limit=N` (default 20) |
| `POST` | `/api/undo` | Undo the last mutating operation (in-memory stack, resets on restart) |

### Buckets

| Bucket | Meaning |
|--------|---------|
| `today` | Do it today |
| `backlog` | Do it eventually, no date |
| `waiting` | Delegated — waiting on someone |
| `someday` | Maybe someday |
| `reference` | No action needed, keep for reference |
| `now` | Do it right now (2-min rule) — not filed |
| `discard` | Not worth keeping — not filed |

---

## Setup

### Prerequisites

- Java 21+
- Maven 3.9+
- A [Groq](https://console.groq.com) API key (free tier works)

### Configuration

Set the `GROQ_API_KEY` environment variable:

```bash
export GROQ_API_KEY=gsk_...
```

Set the vault path — pick one option:

**Option A — env var:**
```bash
export GTD_VAULT_PATH=/path/to/your/obsidian/vault
```

**Option B — local properties file (gitignored):**

Create `src/main/resources/application-local.properties`:
```properties
gtd.vault.path=/path/to/your/obsidian/vault
```

This file is gitignored; the app loads it automatically if present.

### Run

```bash
mvn spring-boot:run
```

The server starts on `http://localhost:8080`.

### Tests

```bash
mvn test
```

27 tests across 4 suites: `BucketControllerTest` (14), `ChatControllerTest` (6), `UndoControllerTest` (3), `VaultServiceTest` (4). All use `@WebMvcTest` with mocked dependencies; `VaultServiceTest` uses `@TempDir` for real filesystem I/O.

---

## Stack

- [Spring Boot 3.3](https://spring.io/projects/spring-boot)
- [Spring AI 1.0.0-M6](https://spring.io/projects/spring-ai) — OpenAI-compatible client pointed at Groq
- [Groq](https://groq.com) — inference (Llama 3.3-70b-versatile)
- [SnakeYAML](https://bitbucket.org/snakeyaml/snakeyaml) — frontmatter serialization
