# java-gtd

REST API that classifies natural language input into GTD buckets and files the results as Markdown notes in an Obsidian vault.

Built with **Spring Boot 3** + **Spring AI** + **Groq** (Llama 3.3-70b).

---

## How it works

Send a message in plain language. The LLM runs the GTD decision tree (is it actionable? can it be done in 2 min? does it need delegation?) and returns a structured classification. If the item is worth filing, it gets written to the vault as a Markdown note with YAML frontmatter.

One message can contain multiple tasks — create, done, update, move, edit, and dismiss operations are all supported in a single call.

```
POST /api/chat
{"message": "llamar al médico la semana que viene"}

→ [{ "op": "create", "filed": true, "bucket": "backlog", "file": "20260624-181203-llamar-al-medico.md" }]
```

Multi-task example:

```
POST /api/chat
{"message": "ya hice la cama, y al médico agregale que también hay que pedir turno para el dentista"}

→ [
    { "op": "done",   "filed": true, "file": "20260624-173158-hacer-la-cama.md" },
    { "op": "update", "filed": true, "file": "20260624-203748-llamar-al-medico.md", "appended": "también hay que pedir turno para el dentista" }
  ]
```

Items classified as `discard` are not filed but are logged to `.vault-meta/discard-log.jsonl` for later review. A two-level prompt strategy is used: lightweight prompt first, falling back to a more detailed one if the response fails to parse or everything comes back as `discard`/`now`.

---

## Endpoints

| Method | Path | Description |
|--------|------|-------------|
| `POST` | `/api/chat` | Classify and execute one or more GTD operations from a natural language message (create / done / update / move / edit / dismiss) |
| `GET` | `/api/today` | List open items in the *today* bucket |
| `GET` | `/api/buckets` | List all open items grouped by bucket |
| `GET` | `/api/buckets/{bucket}` | List open items in a specific bucket |
| `POST` | `/api/items/{filename}/done` | Mark an item as completed |
| `POST` | `/api/items/{filename}/dismiss` | Discard an item (decided not to do it) |
| `PUT` | `/api/items/{filename}/body` | Replace the body of an existing item — `{ "body": "..." }` |
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
- A [Groq](https://console.groq.com) API key

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

---

## Stack

- [Spring Boot 3.3](https://spring.io/projects/spring-boot)
- [Spring AI 1.0.0-M6](https://spring.io/projects/spring-ai) — OpenAI-compatible client
- [Groq](https://groq.com) — inference (Llama 3.3-70b-versatile)
- [SnakeYAML](https://bitbucket.org/snakeyaml/snakeyaml) — frontmatter serialization
