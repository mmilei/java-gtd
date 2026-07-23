#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
classifier-bench.py — harness de diagnostico para el classifier GTD de java-gtd.

Corre el prompt REAL de produccion (classifier_custom.st) contra Groq y modelos
Ollama candidatos, con 5 mensajes fijos, y verifica 2 checks automaticos:
  - area siempre dentro del enum valid_areas
  - body no vacio cuando el mensaje trae contexto (>5 palabras) y el op es create

Solo stdlib. Intérprete: `python` (3.10+). Herramienta descartable, no produccion.

Uso:
    python scripts/classifier-bench.py                       # lista completa
    python scripts/classifier-bench.py qwen2.5:7b-instruct   # solo ese modelo
    python scripts/classifier-bench.py groq qwen3-coder:30b  # varios candidatos

Cada arg es un nombre de modelo Ollama, o la palabra "groq" para el motor Groq.
Modelos no pulleados o Groq sin API key: se loguea y se saltea, no crashea.
"""

import json
import os
import sys
import time
import urllib.request
import urllib.error
from datetime import date

# --- rutas ---
HERE = os.path.dirname(os.path.abspath(__file__))
# Prompt A (Triage) — the confirmed-per-op template. Falls back to the pre-split classifier_custom.st
# if the triage file isn't present (e.g. running against an older checkout).
_TRIAGE = os.path.join(HERE, "..", "src", "main", "resources", "prompts", "classifier-triage-custom.st")
_LEGACY = os.path.join(HERE, "..", "src", "main", "resources", "prompts", "classifier_custom.st")
TEMPLATE_PATH = _TRIAGE if os.path.exists(_TRIAGE) else _LEGACY
USER_PROFILE_PATH = os.path.join(HERE, "..", "..", "..", "..", "_context", "user-profile.md")

# --- fixtures (harness, no fieles 1:1 a la logica Java) ---
VALID_AREAS = "personal,amistad,ejercicio,trabajo,salud,finanzas,hogar,aprendizaje"
KNOWN_PROJECTS = "java-gtd, gtd-frontend, pikon-app, presupuestador, parrilla"
KNOWN_TAGS = "compras, hogar, salud, llamadas, trabajo, finanzas, aprendizaje, mascotas, código"
OPEN_TASKS = json.dumps([
    {"file": "20260630-091100-lavar-ropa.md", "title": "Lavar ropa", "bucket": "today"},
    {"file": "20260628-140200-llamar-al-dentista.md", "title": "Llamar al dentista", "bucket": "backlog"},
    {"file": "20260625-101500-pagar-expensas.md", "title": "Pagar expensas", "bucket": "backlog"},
    {"file": "20260620-183000-comprar-comida-gato.md", "title": "Comprar comida para el gato", "bucket": "today"},
], ensure_ascii=False)

# lista de areas parseada para el check (case-insensitive)
VALID_AREAS_SET = {a.strip().lower() for a in VALID_AREAS.split(",")}

# --- 5 mensajes de test fijos (mismos que el test a mano del 07-22) ---
TEST_MESSAGES = [
    "comprar pilas para el mouse",
    "Tengo que resolver el tema del alquiler, no sé bien cómo encararlo.",
    "arreglar el bug del tab en los tags de gtd-frontend, es para el proyecto frontend-gtd, urgente para hoy",
    "ya lavé la ropa, marcala como hecha",
    "asdkj qwerty asdasd",
]

# --- candidatos por defecto (los no pulleados fallan graceful) ---
DEFAULT_TARGETS = [
    ("ollama", "qwen2.5:7b-instruct"),
    ("ollama", "mistral-nemo:12b-instruct-2407-q4_K_M"),
    ("ollama", "llama3.1:8b-instruct-q4_K_M"),
    ("ollama", "mistral:7b-instruct-v0.3"),
    ("ollama", "qwen3-coder:30b"),
    ("groq", "llama-3.3-70b-versatile"),
]

GROQ_URL = "https://api.groq.com/openai/v1/chat/completions"
OLLAMA_URL = "http://localhost:11434/api/chat"


def load_user_context():
    """Primeras ~15 lineas del user-profile como contexto realista; fallback generico."""
    try:
        with open(USER_PROFILE_PATH, encoding="utf-8") as f:
            lines = [ln.rstrip() for ln in f.readlines()[:15]]
        return "\n".join(lines).strip()
    except OSError:
        return ("Maxi, Argentina (voseo). TDAH: preferir listas cortas. "
                "Areas activas: trabajo (Java/Spring/AI), salud, finanzas, hogar, vinculos, ocio.")


def build_prompt(template, message, user_context):
    """Reemplazos manuales — NO str.format(): el .st tiene JSON crudo con llaves."""
    out = template
    for placeholder, value in (
        ("{today}", str(date.today())),
        ("{user_context}", user_context),
        ("{open_tasks}", OPEN_TASKS),
        ("{known_projects}", KNOWN_PROJECTS),
        ("{known_tags}", KNOWN_TAGS),
        ("{valid_areas}", VALID_AREAS),
        ("{message}", message),
    ):
        out = out.replace(placeholder, value)
    return out


def http_post_json(url, payload, headers=None, timeout=180):
    data = json.dumps(payload).encode("utf-8")
    req = urllib.request.Request(url, data=data, method="POST")
    req.add_header("Content-Type", "application/json")
    # Groq esta detras de Cloudflare, que banea el User-Agent default de urllib (error 1010)
    req.add_header("User-Agent", "classifier-bench/1.0")
    for k, v in (headers or {}).items():
        req.add_header(k, v)
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        return json.loads(resp.read().decode("utf-8"))


def call_groq(model, prompt):
    key = os.environ.get("GROQ_API_KEY")
    if not key:
        raise RuntimeError("GROQ_API_KEY no seteada")
    body = {"model": model, "messages": [{"role": "user", "content": prompt}]}
    resp = http_post_json(GROQ_URL, body, headers={"Authorization": "Bearer " + key})
    return resp["choices"][0]["message"]["content"]


def call_ollama(model, prompt):
    body = {
        "model": model,
        "messages": [{"role": "user", "content": prompt}],
        "stream": False,
        "keep_alive": "30s",
    }
    resp = http_post_json(OLLAMA_URL, body)
    return resp["message"]["content"]


def strip_fences(text):
    """Quita fences ```json ... ``` si vienen envolviendo la respuesta."""
    t = text.strip()
    if t.startswith("```"):
        t = t[3:]
        if t[:4].lower() == "json":
            t = t[4:]
        end = t.rfind("```")
        if end != -1:
            t = t[:end]
    return t.strip()


def parse_ops(raw):
    """Parsea la respuesta a lista de ops. Devuelve (ops|None, error|None)."""
    cleaned = strip_fences(raw)
    try:
        data = json.loads(cleaned)
    except json.JSONDecodeError as e:
        return None, "JSON invalido: %s" % e
    if isinstance(data, dict):
        data = [data]
    if not isinstance(data, list):
        return None, "esperaba array de ops, vino %s" % type(data).__name__
    return data, None


def check_area_valido(ops):
    """Todo op con area no-null debe estar en VALID_AREAS (case-insensitive)."""
    for op in ops:
        if not isinstance(op, dict):
            continue
        area = op.get("area")
        if area is None:
            continue
        if str(area).strip().lower() not in VALID_AREAS_SET:
            return False, "area '%s' fuera del enum" % area
    return True, ""


# Mensaje disenado a proposito para ser ambiguo (backlog vs someday, "no se como encararlo").
# El nuevo Prompt A debe marcar confirmed:false en su op create — valida el DISENO del campo,
# no solo que el modelo copie bien el enum.
AMBIGUOUS_MESSAGE = "Tengo que resolver el tema del alquiler, no sé bien cómo encararlo."


def check_confirmed_ambiguous(message, ops):
    """Para el mensaje ambiguo: algun op create debe traer confirmed:false. n/a para el resto."""
    if message != AMBIGUOUS_MESSAGE:
        return True, "n/a (no es el caso ambiguo)"
    creates = [o for o in ops if isinstance(o, dict) and o.get("op") == "create"]
    if not creates:
        return False, "no hubo op create para evaluar confirmed"
    if any(o.get("confirmed") is False for o in creates):
        return True, ""
    return False, "confirmed no es false (%s)" % [o.get("confirmed") for o in creates]


def check_body_no_vacio_si_corresponde(message, ops):
    """Si el mensaje trae contexto (>5 palabras) y hay un op create, body no vacio."""
    if len(message.split()) <= 5:
        return True, "n/a (mensaje corto)"
    for op in ops:
        if not isinstance(op, dict):
            continue
        if op.get("op") == "create":
            body = op.get("body")
            if body is None or str(body).strip() == "":
                return False, "op create con body vacio"
    return True, ""


def run_one(engine, model, message, template, user_context):
    """Corre un (engine, model, message). Devuelve dict con resultado o error."""
    prompt = build_prompt(template, message, user_context)
    t0 = time.perf_counter()
    try:
        raw = call_groq(model, prompt) if engine == "groq" else call_ollama(model, prompt)
    except (urllib.error.URLError, urllib.error.HTTPError, RuntimeError, KeyError, TimeoutError) as e:
        elapsed = time.perf_counter() - t0
        return {"error": "%s: %s" % (type(e).__name__, e), "latency": elapsed}
    elapsed = time.perf_counter() - t0

    ops, perr = parse_ops(raw)
    if perr:
        return {"error": perr, "latency": elapsed, "raw": raw}

    area_ok, area_why = check_area_valido(ops)
    body_ok, body_why = check_body_no_vacio_si_corresponde(message, ops)
    conf_ok, conf_why = check_confirmed_ambiguous(message, ops)
    return {
        "latency": elapsed,
        "raw": raw,
        "ops": ops,
        "area_ok": area_ok, "area_why": area_why,
        "body_ok": body_ok, "body_why": body_why,
        "conf_ok": conf_ok, "conf_why": conf_why,
    }


def selftest():
    """Offline asserts sobre las funciones puras (parse/checks). Sin red."""
    assert strip_fences("```json\n[{\"a\":1}]\n```") == '[{"a":1}]'
    assert strip_fences('[{"a":1}]') == '[{"a":1}]'
    ops, err = parse_ops('[{"op":"create","area":"hogar"}]')
    assert err is None and ops[0]["area"] == "hogar"
    ops, err = parse_ops('{"op":"create"}')  # dict -> lista de 1
    assert err is None and len(ops) == 1
    _, err = parse_ops("no soy json")
    assert err is not None
    assert check_area_valido([{"area": "hogar"}])[0] is True
    assert check_area_valido([{"area": "finance"}])[0] is False   # el bug de qwen2.5
    assert check_area_valido([{"area": None}])[0] is True
    long = "una dos tres cuatro cinco seis siete"
    assert check_body_no_vacio_si_corresponde(long, [{"op": "create", "body": ""}])[0] is False
    assert check_body_no_vacio_si_corresponde(long, [{"op": "create", "body": "algo"}])[0] is True
    assert check_body_no_vacio_si_corresponde("corto", [{"op": "create", "body": ""}])[0] is True
    # confirmed: solo evalua el mensaje ambiguo; false pasa, true/ausente falla
    assert check_confirmed_ambiguous("otro mensaje", [{"op": "create"}])[0] is True   # n/a
    assert check_confirmed_ambiguous(AMBIGUOUS_MESSAGE, [{"op": "create", "confirmed": False}])[0] is True
    assert check_confirmed_ambiguous(AMBIGUOUS_MESSAGE, [{"op": "create", "confirmed": True}])[0] is False
    assert check_confirmed_ambiguous(AMBIGUOUS_MESSAGE, [{"op": "create"}])[0] is False  # ausente = no dudo
    print("selftest OK")


def main():
    args = [a for a in sys.argv[1:] if a.strip()]
    if args == ["--selftest"]:
        selftest()
        return
    if args:
        targets = [("groq", "llama-3.3-70b-versatile") if a == "groq" else ("ollama", a) for a in args]
    else:
        targets = DEFAULT_TARGETS

    template = open(TEMPLATE_PATH, encoding="utf-8").read()
    user_context = load_user_context()

    print("=" * 78)
    print("classifier-bench — %s | today=%s" % (date.today().isoformat(), date.today()))
    print("valid_areas:", VALID_AREAS)
    print("targets:", ", ".join("%s/%s" % t for t in targets))
    print("=" * 78)

    summary = []  # (label, msg_idx, latency, area_ok, body_ok, note)

    for engine, model in targets:
        label = "%s/%s" % (engine, model)
        print("\n" + "#" * 78)
        print("# MODELO:", label)
        print("#" * 78)
        for i, message in enumerate(TEST_MESSAGES, 1):
            print("\n--- [%s] msg#%d: %s" % (label, i, message))
            res = run_one(engine, model, message, template, user_context)
            if "error" in res and "ops" not in res:
                print("  ERROR (%.2fs): %s" % (res["latency"], res["error"]))
                if res.get("raw"):
                    print("  raw:", res["raw"][:400])
                summary.append((label, i, res["latency"], None, None, res["error"][:40]))
                # si el primer mensaje ya falla por modelo ausente / API, saltar el resto
                if "URLError" in res["error"] or "HTTPError" in res["error"] or "GROQ_API_KEY" in res["error"]:
                    print("  -> salteando resto de mensajes de %s (motor/modelo no disponible)" % label)
                    for j in range(i + 1, len(TEST_MESSAGES) + 1):
                        summary.append((label, j, 0.0, None, None, "skip (motor no disp.)"))
                    break
                continue

            print("  latency: %.2fs" % res["latency"])
            print("  raw:", res["raw"].strip()[:600])
            a = "PASS" if res["area_ok"] else "FAIL"
            b = "PASS" if res["body_ok"] else "FAIL"
            c = "PASS" if res["conf_ok"] else "FAIL"
            print("  check_area_valido:                %s %s" % (a, ("" if res["area_ok"] else "-> " + res["area_why"])))
            print("  check_body_no_vacio_si_corresponde: %s %s" % (b, ("" if res["body_ok"] else "-> " + res["body_why"]) or res["body_why"]))
            print("  check_confirmed_ambiguous:          %s %s" % (c, ("" if res["conf_ok"] else "-> " + res["conf_why"]) or res["conf_why"]))
            note = "" if res["conf_ok"] else "confirmed: " + res["conf_why"]
            summary.append((label, i, res["latency"], res["area_ok"], res["body_ok"], note))

    # --- tabla resumen ---
    print("\n" + "=" * 78)
    print("RESUMEN  (modelo x msg x latencia x checks)")
    print("=" * 78)
    print("%-42s %-4s %-8s %-6s %-6s %s" % ("modelo", "msg", "lat(s)", "area", "body", "nota"))
    print("-" * 78)
    for label, i, lat, area_ok, body_ok, note in summary:
        area = "-" if area_ok is None else ("PASS" if area_ok else "FAIL")
        body = "-" if body_ok is None else ("PASS" if body_ok else "FAIL")
        print("%-42s %-4d %-8.2f %-6s %-6s %s" % (label, i, lat, area, body, note))


if __name__ == "__main__":
    main()
