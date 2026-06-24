# java-gtd

REST API that classifies natural language input into GTD buckets and files the results as Markdown notes in an Obsidian vault.

Built with **Spring Boot 3** + **Spring AI** + **Groq** (Llama 3.3-70b).

---

## How it works

Send a message in plain language. The LLM runs the GTD decision tree (is it actionable? can it be done in 2 min? does it need delegation?) and returns a structured classification. If the item is worth filing, it gets written to the vault as a Markdown note with YAML frontmatter.

```
POST /api/chat
{"message": "llamar al médico la semana que viene"}

→ { "filed": true, "bucket": "backlog", "file": "20260624-181203-llamar-al-medico.md" }
```

---

## Endpoints

| Method | Path | Description |
|--------|------|-------------|
| `POST` | `/api/chat` | Classify and file a natural language item |
| `GET` | `/api/today` | List open items in the *today* bucket |
| `GET` | `/api/buckets` | List all open items grouped by bucket |
| `GET` | `/api/buckets/{bucket}` | List open items in a specific bucket |
| `POST` | `/api/items/{filename}/done` | Mark an item as done |

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
