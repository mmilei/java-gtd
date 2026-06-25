# TODO — java-gtd

Este archivo crece con el proyecto. Si sos un agente que está trabajando en este repo, leelo antes de arrancar y actualizalo cuando termines algo o detectes algo pendiente.

---

## Backend — este repo

### Ideas / Futuro

- **markdownify**: endpoint o flujo que toma el `body` de una tarea (texto vago, sin estructura) y lo pasa por el LLM para enriquecerlo — extraer tags relevantes, formatear con markdown, identificar subtareas implícitas. Se activaría desde el modal de edición del frontend con un botón "✨ Mejorar". El LLM ya está integrado vía Groq, sería un prompt nuevo en `prompts/`.
