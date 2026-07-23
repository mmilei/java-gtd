[← README](../README.md) · [API reference](api.md) · [Architecture](architecture.md)

# Setup & configuration

## Prerequisites

- Java 21+
- Maven 3.9+
- A [Groq](https://console.groq.com) API key (free tier works)
- Optional: [Ollama](https://ollama.com) for local inference

## Quick start

```bash
export GROQ_API_KEY=gsk_...
export GTD_VAULT_PATH=/path/to/your/obsidian/vault
mvn spring-boot:run        # → http://localhost:8080
```

## Configuration

All settings live in `application.properties` and can be overridden per-machine in `src/main/resources/application-local.properties` (gitignored, loaded automatically if present).

| Property | Default | Purpose |
|----------|---------|---------|
| `gtd.vault.path` | `$GTD_VAULT_PATH` | Obsidian vault root the API reads/writes |
| `spring.ai.openai.api-key` | `$GROQ_API_KEY` | Groq key (OpenAI-compatible endpoint) |
| `spring.ai.openai.chat.options.model` | `llama-3.3-70b-versatile` | Cloud model |
| `classifier.template` | `sample` | Prompt pair: `sample` (public, English) or `custom` (gitignored, personal) |
| `ollama.enabled` | off | Set `true` to register the local Ollama provider |
| `spring.ai.ollama.base-url` | `http://localhost:11434` | Ollama server |
| `gtd.vault.migrations-enabled` | `true` | Startup self-healing migrations on the vault (today_since, timestamps, bucket mismatches, related_people list, one-time folder-per-bucket split) |

Example `application-local.properties`:

```properties
gtd.vault.path=D:/path/to/vault
classifier.template=custom
ollama.enabled=true
```

## Prompt templates

The classifier loads a two-level prompt pair from `src/main/resources/prompts/`:

- `classifier.st` + `classifier-fallback.st` — public English samples, used with `classifier.template=sample` (default).
- `classifier_custom.st` + `classifier-fallback-custom.st` — gitignored personal versions, used with `classifier.template=custom`.

Level 1 is a lightweight prompt; level 2 (fallback) is a detailed prompt with examples that runs only when level 1 output fails to parse or classifies everything as `discard`/`now`.

## LLM providers

Two providers are wired through Spring AI:

- **Groq** — default; Llama 3.3-70b via Groq's OpenAI-compatible endpoint.
- **Ollama** — optional local inference; enabled with `ollama.enabled=true`.

Check status and switch at runtime:

```bash
curl http://localhost:8080/api/providers
curl -X POST http://localhost:8080/api/providers/select -H "Content-Type: application/json" -d '{"provider":"ollama"}'
```

## Vault layout

The API creates these folders on startup if missing — one directory per bucket/state, no shared inbox:

```
<vault>/
  brain/today/       today actions
  brain/backlog/     backlog actions
  brain/waiting/     delegated, waiting on someone
  brain/someday/     someday/maybe items
  brain/resources/   reference material
  brain/done/        completed actions (done_date set once)
  brain/discard/     dismissed actions (discarded_date set once)
  .vault-meta/
    events.jsonl        durable, append-only mutation log (undo, GET /api/events)
    transcript.jsonl     durable, append-only raw chat log (GET /api/chat/history)
    discard-log.jsonl    append-only log of discarded ops (from chat, before filing)
    archive/             rotated-out events/transcript lines, never deleted
```

The first startup against an existing `brain/inbox/` vault (pre-2026-07 layout) runs a one-time migration (part of `gtd.vault.migrations-enabled`) that relocates every file into the folder matching its `bucket`/`status`; anything without a `bucket` field (non-GTD notes) is left untouched.

Notes are plain Markdown with YAML frontmatter — readable and editable from Obsidian or any editor while the API runs.

## Tests

```bash
mvn test
```

98 tests across controller suites (`@WebMvcTest` with mocked services), `VaultServiceTest` (real filesystem I/O via `@TempDir`), and `EventLogTest` (append-only log semantics).
