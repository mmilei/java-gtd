# java-gtd — instrucciones para agentes

## Qué es esto

REST API Spring Boot 3 + Spring AI + Groq (Llama 3.3-70b) que clasifica lenguaje natural
en buckets GTD y escribe notas Markdown a un vault Obsidian.

## Leer siempre primero

- **`README.md`** — endpoints completos y ejemplos de uso.
- Pendientes del proyecto: `brain/inbox/` y `brain/someday/` en el vault Obsidian (tag `java-gtd`).

## Reglas de trabajo

- **Nunca pushear a master directamente.** Siempre rama → push → el usuario crea el PR y mergea.
- **Nunca frontend dentro del backend.** El frontend vive en `workspace/test-node/gtd-frontend`.
  No agregar nada en `src/main/resources/static/`.
- Antes de commitear, correr `mvn test`. Los 27 tests tienen que pasar.

---

## Estructura de archivos

### Mínimo para compilar y levantar

```
pom.xml                                          ← dependencias Maven

src/main/java/ar/maxi/gtd/
  GtdApplication.java                            ← @SpringBootApplication, main()

  api/
    BucketController.java                        ← GET /api/buckets, /today, /items/*, /stats, /history
    ChatController.java                          ← POST /api/chat (clasifica + despacha ops)
    UndoController.java                          ← POST /api/undo
    GlobalExceptionHandler.java                  ← @RestControllerAdvice centralizado

  service/
    ClassifierService.java                       ← llama al LLM, dos niveles de prompt
    VaultService.java                            ← lee/escribe archivos .md en el vault
    UndoStack.java                               ← Deque<UndoEntry> cap 10, thread-safe

  util/
    MarkdownSerializer.java                      ← parse/serialize YAML frontmatter

src/main/resources/
  application.properties                         ← config base (GROQ_API_KEY, GTD_VAULT_PATH)
  application-local.properties                   ← gitignored, overrides locales
  prompts/
    classifier.st                                ← prompt nivel 1, inglés — sample público (portfolio)
    classifier-fallback.st                       ← prompt nivel 2 (retry), inglés — sample público
    classifier_custom.st                         ← gitignored — versión argentina (voseo), uso personal
    classifier-fallback-custom.st                ← gitignored — retry en español
```

`classifier.template` (`application.properties`, default `sample`) elige el par de prompts.
`custom` carga `classifier_custom.st`/`classifier-fallback-custom.st` — solo existen local,
seteado en `application-local.properties` (gitignored). `ClassifierService.templateResourcePath()`
resuelve el path, sin IO, testeado directo.

Sin cualquiera de estos archivos, la app no compila o no levanta.

### Vault Obsidian (generado en runtime, no en el repo)

```
<GTD_VAULT_PATH>/
  wiki/
    gtd/
      actions/          ← tareas: today, backlog, waiting, someday
    references/         ← bucket reference + saves de Claude
  .vault-meta/
    discard-log.jsonl   ← log append-only de ops descartadas
```

`VaultService` crea `wiki/gtd/actions/` y `wiki/references/` en startup si no existen.

### Tests

```
src/test/java/ar/maxi/gtd/
  api/
    BucketControllerTest.java    ← 14 tests, @WebMvcTest + @MockBean VaultService
    ChatControllerTest.java      ← 6 tests,  @WebMvcTest + @MockBean Classifier + Vault
    UndoControllerTest.java      ← 3 tests,  @WebMvcTest + @MockBean UndoStack
  service/
    VaultServiceTest.java        ← 4 tests,  instancia real con @TempDir
```

---

## Stack

- Spring Boot 3.3.5 / Spring AI 1.0.0-M6
- Groq via endpoint OpenAI-compatible (`https://api.groq.com/openai`)
- SnakeYAML para frontmatter de los .md
- Virtual threads habilitados (`spring.threads.virtual.enabled=true`)
- Java 21+ (se corre con JDK 26 — Surefire tiene `-Dnet.bytebuddy.experimental=true`)

## Config local

Crear `src/main/resources/application-local.properties` (gitignored):
```properties
gtd.vault.path=D:/ruta/a/tu/vault
```

Y setear `GROQ_API_KEY` como variable de entorno.
