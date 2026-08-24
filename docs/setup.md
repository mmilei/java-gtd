[← README](../README.md) · [API reference](api.md) · [Architecture](architecture.md)

# Setup & configuration

## Prerequisites

- Java 21+
- Maven 3.9+
- A [Groq](https://console.groq.com) API key (free tier works)
- Optional: [Ollama](https://ollama.com) for local inference
- Optional: an [Anthropic](https://console.anthropic.com) API key for the Claude provider

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
| `anthropic.enabled` | off | Set `true` to register the Anthropic (Claude) provider |
| `spring.ai.anthropic.api-key` | `$ANTHROPIC_API_KEY` | Anthropic key |
| `spring.ai.anthropic.chat.options.model` | `claude-sonnet-5` | Anthropic model |
| `gtd.vault.migrations-enabled` | `true` | Startup self-healing migrations on the vault (today_since, timestamps, bucket mismatches, one-time folder-per-bucket split) |

Example `application-local.properties`:

```properties
gtd.vault.path=D:/path/to/vault
classifier.template=custom
ollama.enabled=true
anthropic.enabled=true
```

## Prompt templates

The classifier loads three prompt templates from `src/main/resources/prompts/`, one per pipeline stage:

- **Triage** (`classifier-triage.st` + `classifier-triage-fallback.st`) — the two-level entry prompt; level 2 (fallback) runs only when level 1 output fails to parse or classifies everything as `discard`/`now`. Emits a per-op `confirmed` flag.
- **Enrichment** (`classifier-enrich.st`) — runs once per confirmed `create` op; fills `area`/`tags`/`project`/`location`/`estimate_minutes`.
- **Resolver** (`classifier-resolver.st`) — runs once per unconfirmed `create` op; last chance to re-decide `bucket`/`area`/`tags`/`confirmed` before the task enters the review queue (`/api/unconfirmed`).

`classifier.template=custom` swaps Triage and Enrichment for gitignored, Argentinized (voseo) personal versions (`classifier-triage-custom.st`, `classifier-triage-fallback-custom.st`, `classifier-enrich-custom.st`). Resolver has no custom variant — it's the rare ~13% path and stays a single committed template regardless of `classifier.template`.

## LLM providers

Three providers are wired through Spring AI, routed independently per pipeline stage (`LlmAction`: `TRIAGE` / `ENRICHMENT` / `RESOLVER`):

- **Groq** — default for every action; Llama 3.3-70b via Groq's OpenAI-compatible endpoint.
- **Ollama** — optional local inference; enabled with `ollama.enabled=true`. Kept warm with a 30s `keep_alive` plus a startup warmup call so the first real capture doesn't pay the ~42s cold-load. If Ollama fails its healthcheck, that one call falls back to Groq without changing the stored preference.
- **Anthropic** — optional Claude provider via Spring AI's native `spring-ai-anthropic-spring-boot-starter`; enabled with `anthropic.enabled=true` plus `ANTHROPIC_API_KEY`. No infra healthcheck (unlike Ollama) — it's a paid cloud API, so "available" just means the key is configured, not pinged on every `/api/providers` call.

Check status and switch at runtime, one stage at a time:

```bash
curl http://localhost:8080/api/providers
curl -X POST http://localhost:8080/api/providers/select \
  -H "Content-Type: application/json" \
  -d '{"action":"TRIAGE","provider":"ANTHROPIC"}'
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

157 tests across controller suites (`@WebMvcTest` with mocked services), `VaultServiceTest` (real filesystem I/O via `@TempDir`), and `EventLogTest` (append-only log semantics).
