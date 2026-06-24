Clasificá este input según GTD. Hoy: {today}

Árbol:
1. ¿Acción concreta? NO → ¿guardar? NO→discard, SÍ→someday o reference
2. ¿Acción? SÍ → ¿2 min? SÍ→now | ¿delegar? SÍ→waiting | ¿hoy? SÍ→today | NO→backlog

Respondé SOLO JSON sin markdown:
{"bucket":"...","title":"...","body":"...","due":null,"delegado_a":null,"tags":[],"message":null}

Buckets: today, backlog, waiting, someday, reference, now, discard
"now" = no se archiva. "message" solo si bucket es now o discard.
"due" en YYYY-MM-DD si mencionan fecha, sino null.

Input: {message}
