![Java](https://img.shields.io/badge/Java-21-blue?logo=openjdk)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.3-brightgreen?logo=springboot)
![Spring AI](https://img.shields.io/badge/Spring%20AI-1.0-green?logo=spring)
![Version](https://img.shields.io/badge/version-2.0.0-orange)
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
- **Confirmation & undo** — body edits and dismissals require explicit confirmation; every mutation is undoable (`POST /api/undo`).
- **Time estimates** — the classifier infers `estimate_minutes` so the frontend can project when your day ends.
- **Voice input** — audio transcription endpoint feeding the same pipeline.

## Stack

| Layer | Tech |
|-------|------|
| Language | Java 21 |
| Framework | Spring Boot 3.3 |
| AI integration | Spring AI + OpenAI-compatible APIs |
| LLM providers | Groq (Llama 3.3-70b) · Ollama (local) |
| Storage | Obsidian vault (Markdown + YAML frontmatter) |
| Tests | JUnit 5 · Mockito — 70 tests |

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
| [docs/api.md](docs/api.md) | Full REST API reference — 19 endpoints |
| [docs/architecture.md](docs/architecture.md) | Services, classification pipeline, GTD buckets, undo stack, vault layout |
| [docs/setup.md](docs/setup.md) | Configuration, prompt templates, provider switching |

## Why Java + Spring AI?

Most AI demos use Python. java-gtd proves they don't have to.

If your team already runs Spring Boot, you can add LLM-powered features to your existing codebase today — no Python sidecar, no extra layer, no context switch. Spring AI speaks the OpenAI-compatible API that every major provider implements; this project swaps between a cloud LLM and a local one with a single call.

This project is a reference for Java developers who want to integrate language models into production backends without abandoning their stack.

---

*Natural language → GTD-inspired classification → Obsidian vault, powered by LLMs.*

Built by [Maximiliano Milei](https://linkedin.com/in/maximiliano-milei-48901894)
