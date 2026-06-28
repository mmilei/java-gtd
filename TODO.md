# TODO — java-gtd

This file grows with the project. If you're an agent working on this repo, read it before starting and update it when you finish something or spot a gap.

---

## Backend — this repo

### Ideas / Future

- **Local Whisper (privacy)**: make the transcription provider configurable. Currently uses Groq Whisper (audio leaves the local session). Future: support `whisper.cpp` running locally in server mode (`--port 8081`), pointing Spring AI to `http://localhost:8081` as an alternative `base-url` for audio. Audio would never leave the local machine. Activatable via `gtd.transcription.provider=local|groq` in `application-local.properties`.

---

## Frontend — References and Tags (conceptual design)

Inspired by how Obsidian treats knowledge: tags are not just labels,
they are the connective tissue between ideas. References are not dead files,
they are a second brain with accumulated context.

### The current problem
References live in just another sidebar tab, treated like tasks without a checkbox.
Tags appear as decorative pills but do nothing. Wasted data.

---

### Concept 1 — Slide-in References Panel

A panel that opens from the right (`width: 420px`) without leaving the app.
Triggered by a fixed header button or the `R` keyboard shortcut.

**Inner view:**
- Search bar at the top (filters by title + body in real time, client-side only)
- Richer cards than the sidebar: 3 body lines, date, all tags visible
- Grouped by primary tag (the first non-gtd tag on the item)
- Click on card → opens the existing edit modal

**Why it's better than a tab:**
The sidebar has limited space and references need more body to be useful.
A dedicated side panel coexists with the kanban without overlapping anything.

---

### Concept 2 — Interactive Tag Bar

A horizontal row of pills above the item list (below the bucket tabs).
Shows all unique tags in the current bucket, sorted by frequency.

**Behavior:**
- Click a tag → filters current bucket items to those with that tag
- Click the same tag again → deselects (shows all again)
- Multi-select: Shift+click → AND between tags (only items with BOTH tags)
- Pill with count: `hardware-store (2)` shows how many items in the bucket have that tag

**Cross-bucket variant:**
A special "View by tag" mode that, when clicking a tag, shows all items
across ALL buckets that have it, grouped by bucket. Like a federated search.

---

### Concept 3 — Galaxy View (references as a network)

References panel with a `List / Network` toggle. In Network mode:
- Simple SVG/Canvas graph: nodes = references, edges = shared tags
- Nodes cluster by gravitational attraction based on shared tags
- Click on node → highlights its connections + opens the modal
- Hover on node → tooltip with title + body snippet

Implementation: no D3 to avoid adding deps. Plain 2D Canvas with simple physics
(spring force between nodes with shared tags, general repulsion). ~200 lines.

**Why this is powerful:** you see knowledge clusters emerge.
"cat" and "fridge" have nothing in common. "hardware-store" and "fridge" might.
The graph shows you without you having to think about it.

---

### Concept 4 — Tag as capture context

When typing in the chat and using `#tag`, the frontend highlights it in real time
(like Obsidian wikilinks). On send, that tag is suggested to the LLM in the prompt
so it factors it into classification. Small change to the chat prompt.

---

### Suggested implementation order

1. **Tag Bar** (Concept 2) — highest impact, lowest effort. Frontend only, data already exists.
2. **References Panel** (Concept 1) — second. Needs some CSS and slide logic.
3. **Cross-bucket tag** (variant of 2) — third. Requires adding `GET /api/tags` endpoint in backend.
4. **Galaxy View** (Concept 3) — last. Most spectacular, requires more time.

---

### Backend needed for cross-bucket tags

New endpoint `GET /api/tags` → returns:
```json
{
  "hardware-store": { "today": 0, "backlog": 2, "waiting": 0, "someday": 0, "reference": 1 },
  "cat":            { "today": 0, "backlog": 1, "waiting": 0, "someday": 0, "reference": 0 }
}
```
Computable in `VaultService` from the items already loaded.
