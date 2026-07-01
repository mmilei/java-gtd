![Java](https://img.shields.io/badge/Java-21-blue?logo=openjdk)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.3-brightgreen?logo=springboot)
![Spring AI](https://img.shields.io/badge/Spring%20AI-1.0-green?logo=spring)
![License](https://img.shields.io/badge/license-MIT-lightgrey)
![Tests](https://img.shields.io/badge/tests-27%20passing-success)

# java-gtd

> Natural language → GTD classification → Obsidian vault, powered by LLMs.

A REST API that takes plain text input, classifies it using an LLM (Groq / Llama 3.3-70b via Spring AI), and writes the result as a structured Markdown note directly into an Obsidian vault.

**[Live Demo →](https://mmilei.github.io/gtd-frontend)** · [Frontend repo →](https://github.com/mmilei/gtd-frontend)

---

## What it does

You send: `"Call the dentist tomorrow morning"`

It returns:
```json
{
  "fallback": false,
  "ops": [{ "op": "create", "filed": true, "bucket": "today", "title": "Call the dentist", "file": "20260628-090000-call-the-dentist.md" }]
}
```

And writes a Markdown note with full frontmatter to your Obsidian vault — ready to appear in your GTD dashboard.

The LLM handles all the ambiguity: delegates, priorities, someday-maybe items, reference material. No rigid rules, no keyword matching.

---

## Stack

| Layer | Tech |
|-------|------|
| Language | Java 21 |
| Framework | Spring Boot 3.3.5 |
| AI integration | Spring AI 1.0.0-M6 + Groq API |
| LLM | Llama 3.3-70b (via Groq) |
| Storage | Obsidian vault (Markdown files) |
| Tests | JUnit 5 · Mockito — 27 tests |

[Full API reference →](docs/api.md)

---

## Architecture

```
POST /api/chat ("Call the dentist tomorrow morning")
        │
        ▼
 ClassifierService
  └─ Groq / Llama 3.3-70b
     └─ Two-level prompt: GTD decision tree
        (Is it actionable? 2-min rule? Delegate? Today/backlog/someday?)
        │
        ▼
 ClassifyResult { bucket, title, due, waitingOn }
        │
        ▼
 VaultService
  └─ MarkdownSerializer (SnakeYAML)
  └─ Writes .md to Obsidian vault path
```

**GTD buckets:** `today` · `backlog` · `waiting` · `someday` · `reference` · `now` · `discard`

---

## API

```
POST   /api/chat                          Classify and file natural language input
GET    /api/buckets                       All buckets with item counts
GET    /api/buckets/{bucket}              Items in a specific bucket
GET    /api/today                         Today's action list
GET    /api/tags                          Unique tags with counts per bucket
GET    /api/items/{filename}              Single item detail
POST   /api/items/{filename}/done         Mark as done
POST   /api/items/{filename}/dismiss      Dismiss item
POST   /api/items/{filename}/move         Move to another bucket
PUT    /api/items/{filename}/body         Update note body
GET    /api/stats                         Usage stats
GET    /api/history                       Classification history
POST   /api/undo                          Undo last operation (stack cap: 10)
```

---

## Frontend

The [gtd-frontend](https://github.com/mmilei/gtd-frontend) is a visual GTD dashboard built with Vite + Three.js + TailwindCSS.

**[→ Open the dashboard](https://mmilei.github.io/gtd-frontend)**

Features:
- Real-time bucket visualization
- Natural language input connected to this API
- 3D particle background (Three.js)

---

## Quick start

```bash
git clone https://github.com/mmilei/java-gtd
cd java-gtd
```

Set credentials:

```bash
export GROQ_API_KEY=gsk_...
export GTD_VAULT_PATH=/path/to/your/obsidian/vault
```

Or create `src/main/resources/application-local.properties` (gitignored):
```properties
gtd.vault.path=/path/to/your/obsidian/vault
```

Run:

```bash
mvn spring-boot:run
```

Requires Java 21+, Maven 3.9+, and a [Groq API key](https://console.groq.com) (free tier available).

---

## Tests

27 tests covering classification logic, bucket operations, undo stack, and vault I/O.

```bash
mvn test
```

---

## Why Java + Spring AI?

Most AI demos use Python. java-gtd proves they don't have to.

If your team already runs Spring Boot, you can add LLM-powered classification to your existing codebase today — no Python service, no extra layer, no context switch. Spring AI implements the same OpenAI-compatible API that every major model provider speaks; swapping Groq for another provider is a one-line config change.

This project is a reference for Java developers who want to integrate language models into production backends without abandoning their stack.

---

Built by [Maximiliano Milei](https://linkedin.com/in/maximiliano-milei-48901894)
