# TODO — java-gtd

Este archivo crece con el proyecto. Si sos un agente que está trabajando en este repo, leelo antes de arrancar y actualizalo cuando termines algo o detectes algo pendiente.

---

## Backend — este repo

### Ideas / Futuro

- **Whisper local (privacidad)**: hacer configurable el proveedor de transcripción. Hoy usa Groq Whisper (audio sale a la API). Futuro: soportar `whisper.cpp` corriendo localmente con su modo servidor (`--port 8081`), apuntando Spring AI a `http://localhost:8081` como `base-url` alternativo para audio. El audio nunca saldría de la sesión local. Activable con una propiedad `gtd.transcription.provider=local|groq` en `application-local.properties`.

---

## Frontend — Referencias y Tags (diseño conceptual)

Inspirado en cómo Obsidian trata el conocimiento: los tags no son solo etiquetas,
son el tejido conectivo entre ideas. Las referencias no son solo archivos muertos,
son el segundo cerebro con contexto acumulado.

### El problema actual
Las referencias viven en una solapa más del sidebar, tratadas como tareas sin check.
Los tags aparecen como pills decorativas pero no hacen nada. Son datos tirados.

---

### Concepto 1 — Panel de Referencias deslizable

Un panel que se abre desde la derecha (slide-in, `width: 420px`) sin salir de la app.
Se activa con un botón fijo en el header o con `R` como atajo de teclado.

**Vista interna:**
- Search bar arriba (filtra por título + body en tiempo real, solo client-side)
- Cards más ricas que las del sidebar: 3 líneas de body, fecha, todos los tags visibles
- Agrupadas por tag principal (el primer tag no-gtd del item)
- Click en card → abre el modal de edición existente

**Por qué es mejor que una solapa:**
El sidebar tiene poco espacio y las referencias necesitan más cuerpo para ser útiles.
Un panel lateral dedicado convive con el kanban sin pisar nada.

---

### Concepto 2 — Tag Bar interactiva

Una fila horizontal de pills sobre la lista de items (debajo de los tabs de bucket).
Muestra todos los tags únicos del bucket actual, ordenados por frecuencia.

**Comportamiento:**
- Click en un tag → filtra los items del bucket actual a los que tengan ese tag
- Click en el mismo tag → deselecciona (vuelve a mostrar todos)
- Multi-select: Shift+click → AND entre tags (solo items con AMBOS tags)
- Pill con número: `ferrería (2)` indica cuántos items en el bucket tienen ese tag

**Variante cross-bucket:**
Un modo especial "Ver por tag" que al clickear un tag muestra todos los items
de TODOS los buckets que lo tienen, agrupados por bucket. Como una búsqueda federada.

---

### Concepto 3 — Vista Galaxy (referencias como red)

Panel de referencias con un toggle `Lista / Red`. En modo Red:
- Grafo SVG/Canvas simple: nodos = referencias, aristas = tags compartidos
- Los nodos se agrupan por atracción gravitacional según tags en común
- Click en nodo → highlight de sus conexiones + abre el modal
- Hover en nodo → tooltip con título + snippet del body

Implementación: sin D3 para no agregar deps. Canvas 2D con física simple
(spring force entre nodos con tags en común, repulsión general). ~200 líneas.

**Por qué esto es poderoso:** visualizás clusters de conocimiento.
"gato" y "heladera" no tienen nada que ver. "ferrería" y "heladera" sí.
El grafo te lo muestra sin que tengas que pensar.

---

### Concepto 4 — Tag como contexto de captura

Cuando escribís en el chat y usás `#tag`, el frontend lo resalta en tiempo real
(como Obsidian con los wikilinks). Al enviar, ese tag se sugiere al LLM en el prompt
para que lo considere al clasificar. Pequeño cambio en el prompt del chat.

---

### Orden de implementación sugerido

1. **Tag Bar** (Concepto 2) — mayor impacto, menor esfuerzo. Solo frontend, datos ya existen.
2. **Panel de Referencias** (Concepto 1) — segundo. Necesita un poco de CSS y lógica de slide.
3. **Tag cross-bucket** (variante del 2) — tercero. Requiere agregar endpoint `GET /api/tags` en backend.
4. **Vista Galaxy** (Concepto 3) — último. El más espectacular, requiere más tiempo.

---

### Backend necesario para Tag cross-bucket

Nuevo endpoint `GET /api/tags` → devuelve:
```json
{
  "ferrería": { "today": 0, "backlog": 2, "waiting": 0, "someday": 0, "reference": 1 },
  "gato":     { "today": 0, "backlog": 1, "waiting": 0, "someday": 0, "reference": 0 }
}
```
Calculable en `VaultService` leyendo los items que ya se tienen.
