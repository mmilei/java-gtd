![Java](https://img.shields.io/badge/Java-21-blue?logo=openjdk)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.3-brightgreen?logo=springboot)
![Spring AI](https://img.shields.io/badge/Spring%20AI-1.0-green?logo=spring)
![Version](https://img.shields.io/badge/version-2.1.1-orange)
![CI](https://github.com/mmilei/java-gtd/actions/workflows/ci.yml/badge.svg)
![License](https://img.shields.io/badge/license-MIT-lightgrey)

# java-gtd

> **Not your typical TODO app.** You talk to it; an LLM organizes your day — GTD-inspired classification, plain Markdown filed straight into your Obsidian vault.

Say *"call the dentist next week"* — and it becomes a structured, tagged, scheduled Markdown note in your Obsidian vault, filed into the right GTD bucket by an LLM.

**[Live Demo →](https://mmilei.github.io/gtd-frontend)** · [Frontend repo →](https://github.com/mmilei/gtd-frontend)

---

## What it does

You send:

```
POST /api/chat  { "message": "call the dentist next week" }
```

It returns:

```json
{
  "fallback": false,
  "ops": [{ "op": "create", "filed": true, "bucket": "backlog", "title": "Call the dentist", "due": "2026-07-06", "file": "20260702-090000-call-the-dentist.md" }]
}
```

…and writes a Markdown note with full YAML frontmatter (bucket, due date, context tags, time estimate) straight into your Obsidian vault.

Inspired by [Getting Things Done](https://en.wikipedia.org/wiki/Getting_Things_Done) (GTD), David Allen's productivity method: capture everything, decide if it's actionable, then organize it by *when and how* it gets done rather than by topic. The LLM runs that decision tree over your words — actionable or not, 2-minute rule, delegate, today vs backlog vs someday. It also understands follow-ups — *"move that to today"*, *"mark the dentist one as done"*, *"rewrite the note"* — resolving which existing task you mean and asking for confirmation before destructive edits.

## Highlights

- **Second-brain native** — every task lands as frontmattered Markdown in your Obsidian vault: portable, greppable, versionable, no database. Ready for an LLM-maintained knowledge base in the style of Karpathy's [LLM Wiki](https://gist.github.com/karpathy/442a6bf555914893e9891c11519de94f) — this API is the capture layer, your vault is the brain.
- **Multi-provider LLM** — Groq (Llama 3.3-70b) or a local Ollama model, switchable at runtime via API. Cloud-quality or fully offline.
- **Conversational task management** — create, edit, move, complete, and dismiss tasks in plain language; multi-operation messages supported.
- **Confirmation & durable undo** — body edits and dismissals require explicit confirmation, tracked separately from direct edits so the audit trail knows who approved what; every mutation is undoable (`POST /api/undo`) via a durable, append-only event log that survives a restart.
- **Time estimates** — the classifier infers `estimate_minutes` so the frontend can project when your day ends.
- **Voice input** — audio transcription endpoint feeding the same pipeline.

## Day-to-day workflow

The API is a **capture-and-organize layer that sits on top of a folder you already own** — your Obsidian vault. A typical loop:

1. **Capture, in plain language.** Throughout the day you fire messages at `POST /api/chat` — typed or dictated (the transcription endpoint turns a voice memo into the same text). *"pay the gas bill friday"*, *"someday read the Crafting Interpreters book"*, *"ask Cami for the signed contract"*. No forms, no picking a list.
2. **The LLM files it for you.** Each message runs through the GTD decision tree and lands as a Markdown note in the right bucket folder — `brain/today/`, `brain/backlog/`, `brain/waiting/`, `brain/someday/`, or `brain/resources/` — with `due`, context `tags`, a rough `estimate_minutes`, and (when clear) `project`/`location`/`area` already filled in.
3. **Follow up conversationally.** Later messages operate on what's already there: *"move the gas bill to today"*, *"mark the contract one as done"*, *"rewrite the note on X"*. The backend resolves which task you mean by title; destructive edits come back as a confirmation you approve before anything is overwritten. Slipped up? `POST /api/undo` walks the last mutation back.
4. **Live in Obsidian.** Because every task is just a frontmattered `.md` file on disk, you open the same vault in Obsidian and get the full second-brain experience for free — backlinks, Dataview queries over `tags`/`due`/`area`, graph view, and hand-editing. Edits you make in Obsidian and edits the API makes are the same files; on the next startup `VaultService` quietly self-heals anything that drifted (see [docs/architecture.md](docs/architecture.md)).

### How it plugs into Obsidian

- **The vault is the source of truth**, not a database. Point `GTD_VAULT_PATH` at your existing Obsidian vault (or a subfolder) and the API writes into `brain/<bucket>/`. Nothing is locked away — the notes stay portable, greppable, and versionable with git.
- **Frontmatter is the contract.** Each note carries `type`, `bucket`, `status`, `created`, `due`, `tags`, etc. — exactly the fields Obsidian's Properties UI, Dataview, and Bases read, so you can build task dashboards over the same data the API classifies into.
- **Two-way editing.** The API never assumes it's the only writer: it re-reads notes on each operation and runs idempotent startup migrations, so a note you moved or retagged by hand in Obsidian is respected, not clobbered.
- **Frontend optional.** The [gtd-frontend](https://github.com/mmilei/gtd-frontend) web client ([live demo](https://mmilei.github.io/gtd-frontend)) is a convenient face over these endpoints, but the whole loop works headless — `curl`, a shortcut, or Obsidian itself.

## Stack

| Layer | Tech |
|-------|------|
| Language | Java 21 |
| Framework | Spring Boot 3.3 |
| AI integration | Spring AI + OpenAI-compatible APIs |
| LLM providers | Groq (Llama 3.3-70b) · Ollama (local) |
| Storage | Obsidian vault (Markdown + YAML frontmatter) |
| Tests | JUnit 5 · Mockito — 110 tests |

## Dependencies

The runtime footprint is deliberately small — four direct dependencies:

| Dependency | Purpose |
|------------|---------|
| `spring-boot-starter-web` | REST API layer (controllers, JSON serialization, embedded server) |
| `spring-ai-openai-spring-boot-starter` | Groq integration via its OpenAI-compatible API, plus Whisper audio transcription |
| `spring-ai-ollama-spring-boot-starter` | Local LLM fallback (Ollama), enabled through `application-local.properties` |
| `spring-boot-starter-test` | JUnit 5 + Mockito + AssertJ test stack (test scope only) |

Everything else (vault storage, event log, undo) is plain Java on the filesystem — no database, no message broker.

## Quick start

```bash
git clone https://github.com/mmilei/java-gtd && cd java-gtd
export GROQ_API_KEY=gsk_...          # free tier: console.groq.com
export GTD_VAULT_PATH=/path/to/vault
mvn spring-boot:run                  # → http://localhost:8080
```

Requires Java 21+ and Maven 3.9+. Full configuration options (local properties, custom prompts, Ollama setup): [docs/setup.md](docs/setup.md).

## Documentation

| Doc | Contents |
|-----|----------|
| [docs/api.md](docs/api.md) | Full REST API reference — 24 endpoints |
| [docs/architecture.md](docs/architecture.md) | Services, classification pipeline, GTD buckets, event log & undo, vault layout |
| [docs/setup.md](docs/setup.md) | Configuration, prompt templates, provider switching |

## Why Java + Spring AI?

Most AI demos use Python. java-gtd proves they don't have to.

If your team already runs Spring Boot, you can add LLM-powered features to your existing codebase today — no Python sidecar, no extra layer, no context switch. Spring AI speaks the OpenAI-compatible API that every major provider implements; this project swaps between a cloud LLM and a local one with a single call.

This project is a reference for Java developers who want to integrate language models into production backends without abandoning their stack.

---

*Natural language → GTD-inspired classification → Obsidian vault, powered by LLMs.*

Built by [Maximiliano Milei](https://linkedin.com/in/maximiliano-milei-48901894)
