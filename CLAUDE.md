# java-gtd — instrucciones para agentes

## Qué es esto

REST API Spring Boot 3 + Spring AI + Groq (Llama 3.3-70b) que clasifica lenguaje natural
en buckets GTD y escribe notas Markdown a un vault Obsidian.

## Leer siempre primero

- **`TODO.md`** — pendientes del proyecto. Leelo al arrancar, actualizalo al terminar.
- **`README.md`** — endpoints completos y ejemplos de uso.

## Reglas de trabajo

- **Nunca pushear a master directamente.** Siempre rama → push → el usuario crea el PR y mergea.
- **Nunca frontend dentro del backend.** El frontend vive en `workspace/test-node/gtd-frontend`.
  No agregar nada en `src/main/resources/static/`.
- Antes de commitear, correr `mvn test`. Los 27 tests tienen que pasar.

## Estructura del proyecto

```
src/main/java/ar/maxi/gtd/
  api/          — controllers REST + GlobalExceptionHandler
  service/      — VaultService, ClassifierService, UndoStack
  util/         — MarkdownSerializer

src/main/resources/
  prompts/      — classifier.st, classifier-fallback.st (templates del LLM)
  application.properties

src/test/       — 27 tests: BucketControllerTest, ChatControllerTest,
                  UndoControllerTest, VaultServiceTest
```

## Stack

- Spring Boot 3.3.5 / Spring AI 1.0.0-M6
- Groq via endpoint OpenAI-compatible
- SnakeYAML para frontmatter de los .md
- Virtual threads habilitados (`spring.threads.virtual.enabled=true`)

## Config local

Crear `src/main/resources/application-local.properties` (gitignored):
```properties
gtd.vault.path=D:/ruta/a/tu/vault
```

Y setear `GROQ_API_KEY` como variable de entorno.
