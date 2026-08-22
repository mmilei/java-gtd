---
name: gtd-triage
description: "Runs the GTD capture/triage decision tree on an incoming item (task, idea, note, email, etc.) and files it as a Markdown note directly into this vault — the same GTD decision logic the java-gtd backend runs, implemented as a Claude Code skill instead of a REST endpoint. Triggers on: capture this, triage this, what do I do with this, new task, process my inbox, clarify this item."
allowed-tools: Read, Write, Bash, Grep
---

# gtd-triage: capture and triage, as a Claude Code skill

This skill is a second implementation of java-gtd's classification pipeline — same GTD decision tree, same bucket taxonomy, same note format, same vault — but running as an AI coding agent skill instead of a Spring AI backend. It exists to demonstrate that the domain logic (capture → actionability → 2-minute rule → delegate → today/backlog/someday) isn't tied to one runtime: it works equally well as a stateless REST classifier serving a web frontend, or as an interactive skill inside a coding agent session, and the two can share the same vault without stepping on each other.

Where they differ on purpose: the backend runs a two-tier LLM pipeline (a fast triage pass, then a confidence-gated enrichment or resolver pass) because it has no human in the loop to ask. This skill runs inside an interactive session, so instead of a resolver fallback it just asks one clarifying question when something is genuinely ambiguous.

Don't interrogate the user with every question below when the answer is already clear from context — ask only what you don't know yet.

---

## The Decision Tree

```
1. Is there a concrete action?
   NO  → 2a (keep or discard)
   YES → 2b (actionable path)

2a. Is it worth keeping?
   NO  → Discard. Tell the user; file nothing.
   YES → Is it a possible future action, or pure reference?
         → someday   (incubating idea, possible future action)
         → reference (pure info, no action expected)

2b. Can it be done in 2 minutes?
   YES → Do it now. File nothing (tell the user).
   NO  → Is it delegated to another PERSON (not an artifact, not your own future action)?
         YES → waiting (ask who — a name, not a PR/ticket/deploy)
         NO  → Does it need to happen today?
               YES → today
               NO  → backlog (with or without a due date)
```

Five terminal buckets: `today`, `backlog`, `waiting`, `someday`, `reference` — identical to the buckets the backend's `ClassifierService` produces (see [architecture.md](../../../docs/architecture.md)).

**`waiting` is for people, not artifacts.** "Waiting on my own PR to merge" is not delegation — that's a next action you own, sitting undone. It belongs in `backlog` (or `today` if it blocks active work), with the blocker noted in the body.

---

## Triage Flow

### Step 1 — Understand the item

Read what the user gave you. Extract or confirm what the item IS, and any explicit hints (urgency, person involved, date). If ambiguous, ask ONE clarifying question before continuing.

### Step 2 — Walk the tree

Ask only what isn't already obvious — actionability, the 2-minute rule, delegation, today-vs-backlog, and a due date if one applies.

### Step 3 — Determine bucket and title

Pick one of: `today`, `backlog`, `waiting`, `someday`, `reference`. Choose a short, action-oriented title (verb + object, e.g. "Call dentist for appointment").

### Step 3.5 — Reuse existing vocabulary before writing tags/project/area

The backend's classifier injects `known_tags` / `known_projects` / `valid_areas` straight into its prompt so a task it files never invents a near-duplicate of something that already exists. This skill does the equivalent lookup against the vault on disk before choosing a value:

- **Tags**: `grep -rhoE 'tags:.*|- [a-z-]+$' brain/today/ brain/backlog/ brain/waiting/ brain/someday/` (or the vault's search tool, if one is wired up). Reuse an exact existing tag when one fits instead of a near-duplicate (`shopping` vs `groceries` vs `store`).
- **Projects**: existing `project:` values across `brain/` (`grep -rhoE 'project: .*' brain/`). Reuse an exact name when the task clearly belongs to a known codebase — never invent one for a generic task.
- **Areas**: read the configured area vocabulary the same way the backend does — via `GET /api/areas` if the backend is running, or the `gtd.areas` property directly. An `area` value must be one of these, or omitted.

### Step 4 — Determine the filing path

Same vault layout the backend maintains (see [architecture.md](../../../docs/architecture.md#gtd-buckets) and [setup.md](../../../docs/setup.md#vault-layout)) — one directory per bucket, no shared inbox:

| Bucket | Path |
|--------|------|
| `today` | `brain/today/<slug>.md` |
| `backlog` | `brain/backlog/<slug>.md` |
| `waiting` | `brain/waiting/<slug>.md` |
| `someday` | `brain/someday/<slug>.md` |
| `reference` | `brain/resources/<slug>.md` |

Terminal states (`done`, `dismissed`) live in `brain/done/` and `brain/discard/` — this skill never files directly into those; only the backend's `done`/`dismiss` endpoints (or a human, moving the file by hand) move a task there. Both are safe to interleave: `VaultService`'s self-healing startup migrations mean a file this skill creates or edits directly is respected, not clobbered, the next time the backend boots.

**Filename format:** `YYYYMMdd-HHmmss-slug.md` — same pattern the backend uses. Slug: lowercase, hyphens, max 50 chars.

### Step 5 — Write the file

Use the template matching the bucket (below). Fill in frontmatter from the triage answers.

**Optional fields `location` and `estimate_minutes`** mirror the backend's enrichment step — fill them in only when reasonably inferable from the task itself, never invent precision:
- `location`: the physical place the task implies (hardware store, pharmacy, bank…), only when the verb/object makes it clear.
- `estimate_minutes`: rough effort in whole minutes (5, 15, 30, 60, 120…), only when the duration is reasonably inferable. When unsure, omit the field rather than default to one.

### Step 6 — Confirm

Show a one-line summary of what changed: `+ file.md — created (title, bucket) #tag1 #tag2`. The tool output alone isn't enough — the user needs to see it in the conversation.

---

## Templates

**Actionable (today / backlog / waiting):**
```yaml
---
type: action
title: "Task title"
bucket: <today|backlog|waiting>
status: open
created: YYYY-MM-DD
tags: [<context-tag-1>, <context-tag-2>]
---
```
Add `due: YYYY-MM-DD` and `today_since: YYYY-MM-DD` for `today`. Add `related_people: "name"` for `waiting`. Add `project`, `location`, and/or `estimate_minutes` only when confidently inferable (see rules above) — omit otherwise, never invent one.

**Someday:**
```yaml
---
type: action
title: "Future idea or task"
bucket: someday
status: open
created: YYYY-MM-DD
tags: [someday]
---
```

**Reference:**
```yaml
---
type: reference
title: "Resource title"
created: YYYY-MM-DD
tags: [reference, <context-tag>]
---
```
References have no `bucket` or `status` — they aren't tasks, they have no lifecycle.

---

## Edge Cases

**Item is actionable but takes 2 minutes** — encourage doing it now. Offer to file it as `today` if the user wants a record anyway.

**Delegate target is unclear** — ask who, before filing to `waiting`. If the answer names a PR, ticket, or deploy rather than a person, it isn't delegation — file to `backlog`/`today` instead.

**Discard** — say so plainly: "I'd toss this — it's neither actionable nor useful as reference. Want me to file it anyway?" Respect the answer.

**Gibberish / noise** — keyboard mashing or text with no recognizable content goes straight to discard, same as the backend's Triage prompt does before it even walks the rest of the tree. Don't file it as backlog just because it superficially looks like "a task."

---

## Why this exists

Most Claude-plus-Obsidian setups treat the AI agent and the app as separate worlds — one writes files by hand, the other is a black-box API. This skill and the [java-gtd](../../../README.md) backend intentionally implement the *same* domain logic twice, against the *same* vault, so either one can be the entry point on any given day: a REST call from a phone shortcut, or a sentence typed straight into a coding agent session. Neither one owns the vault — they're two consumers of the same plain-Markdown source of truth, kept honest against drift by identical bucket paths, identical note format, and the same vocabulary-reuse discipline (`known_tags`/`known_projects`/`valid_areas` in the backend, the on-disk lookup in Step 3.5 here).
