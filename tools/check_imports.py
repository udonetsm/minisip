#!/usr/bin/env python3
"""Проверка слоёв: кто кого может импортировать. Падает с кодом 1 при нарушении.

Правила:
  1. Пакет может импортировать только разрешённые пакеты (карта ALLOWED, без циклов).
  2. Из чужого пакета можно импортировать только его публичный интерфейс (IFACE).
     Реализации в пакетах package-private, так что компилятор тоже не даст, но
     здесь проверяем и текст.
  3. Публичные интерфейсы не мелькают кастомными типами в сигнатурах: только
     встроенные типы (String, int, byte[], boolean, ...), android.* и свои вложенные
     интерфейсы-колбэки.
"""
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent / "app/src/main/java/ru/minisip"

ALLOWED = {
    "net": set(),
    "media": {"net"},
    "sip": {"net", "media"},
    "screen": set(),
    "vpn": set(),
    "app": {"net", "media", "sip", "screen", "vpn"},
}
IFACE = {"net": "Udp", "media": "Media", "sip": "Sip", "screen": "Screen", "vpn": "Vpn"}

errors = []

# --- 1 и 2: импорты ---
for pkg, allowed in ALLOWED.items():
    for f in sorted((ROOT / pkg).glob("*.java")):
        for n, line in enumerate(f.read_text(encoding="utf-8").splitlines(), 1):
            m = re.match(r"\s*import\s+ru\.minisip\.(\w+)\.(\w+)", line)
            if not m:
                continue
            dep, cls = m.groups()
            where = f"{f.relative_to(ROOT)}:{n}"
            if dep == pkg:
                continue
            if dep not in allowed:
                errors.append(f"{where}: пакет '{pkg}' не может зависеть от '{dep}'")
            elif cls != IFACE[dep]:
                errors.append(f"{where}: из '{dep}' можно только {IFACE[dep]}, а не {cls}")

# --- ацикличность карты ---
def has_cycle():
    state = {}
    def dfs(p):
        if state.get(p) == 1:
            return True
        if state.get(p) == 2:
            return False
        state[p] = 1
        if any(dfs(d) for d in ALLOWED[p]):
            return True
        state[p] = 2
        return False
    return any(dfs(p) for p in ALLOWED)

if has_cycle():
    errors.append("карта зависимостей содержит цикл")

# --- 3: сигнатуры публичных интерфейсов ---
OK_TYPES = {"void", "int", "long", "boolean", "String", "byte[]", "short[]", "Context",
            "Listener", "Udp", "Media", "Sip", "Screen", "Vpn", "int...", "Object"}
for pkg, name in IFACE.items():
    f = ROOT / pkg / f"{name}.java"
    src = re.sub(r"/\*.*?\*/", "", f.read_text(encoding="utf-8"), flags=re.S)
    src = re.sub(r"//.*", "", src)
    for m in re.finditer(r"^\s*(?:default\s+|static\s+)?([\w\[\].]+)\s+(\w+)\s*\(([^)]*)\)", src, re.M):
        ret, meth, params = m.groups()
        if ret in ("interface", "return", "new"):
            continue
        types = [ret] + [p.strip().rsplit(" ", 1)[0] for p in params.split(",") if p.strip()]
        for t in types:
            if t not in OK_TYPES:
                errors.append(f"{pkg}/{name}.java: метод {meth}() использует тип '{t}'")

if errors:
    print("НАРУШЕНИЯ:")
    for e in errors:
        print("  -", e)
    sys.exit(1)
print("OK: зависимости пакетов ациклические, наружу торчат только интерфейсы и встроенные типы")
