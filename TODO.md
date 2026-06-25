# TODO — java-gtd

Este archivo crece con el proyecto. Si sos un agente que está trabajando en este repo, leelo antes de arrancar y actualizalo cuando termines algo o detectes algo pendiente.

---

## Frontend — `workspace/test-node/gtd-frontend`

### 🎉 Animación satisfactoria al marcar DONE

**Idea:** cuando marcás una tarea como "done" (botón ✓ o tecla `d`), tiene que dar SATISFACCIÓN.
No el silencio actual. Opciones a combinar:

- **Explosión de partículas**: burst Three.js desde la card marcada (similar al arc de filing pero
  radial — las partículas explotan hacia afuera en lugar de viajar hacia una columna)
- **Sonido**: un "ding" o "pop" corto via Web Audio API (`AudioContext` + `OscillatorNode`)
  ej: frecuencia 880Hz → 1200Hz en 80ms, decay en 200ms
- **Card animation**: la card se escala, brilla en verde (`--b-someday`) y luego se desvanece
  antes de desaparecer del DOM — en lugar del refresh inmediato
- **Screen flash**: flash sutil verde en toda la pantalla (overlay con opacity transition)

**Ubicación en el código:**
- `gtd-frontend/js/board.js` línea ~130: `api.markDone(item.file).then(refresh)`
- Antes del `.then(refresh)` lanzar la animación, delay de ~600ms para que se vea antes de que
  desaparezca la card
- El burst de Three.js ya existe en `bg.js` → `burst(colEl)`. Crear `burstFromCard(cardEl)` que
  explote desde las coordenadas del card.

### Pendientes del AGENT.md

Ver `workspace/test-node/gtd-frontend/AGENT.md` para la lista completa. Resumen:
- Soporte move/edit/dismiss en `buildOpCard()` y `resolveIcon()`
- Botón dismiss en sidebar (junto al done-btn)
- Edición inline del body (click → textarea → PUT /body)
- Ctrl+Z → POST /api/undo → feedback visual

---

## Backend — este repo

_(vacío por ahora — agregar issues o ideas acá a medida que aparezcan)_
