#!/usr/bin/env python3
"""Validate Android localization resources, placeholders, arrays, and used plurals."""
from __future__ import annotations
from collections import Counter
from pathlib import Path
import re
import subprocess
import sys
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1] / "app/src/main/res"
LOCALES = ["values", "values-pl", "values-de", "values-ja", "values-ko", "values-zh-rCN", "values-zh-rTW"]
LOCALE_LABELS = {"values":"EN", "values-pl":"PL", "values-de":"DE", "values-ja":"JA", "values-ko":"KO", "values-zh-rCN":"zh-CN", "values-zh-rTW":"zh-TW"}
REQUIRED_CATEGORIES = {"values":{"one","other"}, "values-pl":{"one","few","many","other"}, "values-de":{"one","other"}, "values-ja":{"other"}, "values-ko":{"other"}, "values-zh-rCN":{"other"}, "values-zh-rTW":{"other"}}
PLACEHOLDER_RE = re.compile(r"(?<!%)%(?:(\d+)\$)?([-+ 0#]*\d*(?:\.\d+)?)([sdif])|(?<!%)%(?!%)")

def xml_files(locale: str):
    return sorted(p for p in (ROOT / locale).glob("*.xml") if p.is_file())

def text(node):
    return "".join(node.itertext()) if node is not None else ""

def collect(locale: str):
    result = {"string": {}, "array": {}, "plurals": {}}
    duplicates = []
    for path in xml_files(locale):
        try: root = ET.parse(path).getroot()
        except ET.ParseError as e: raise SystemExit(f"XML parse error {path}: {e}")
        for node in root:
            kind = {"string":"string", "string-array":"array", "plurals":"plurals"}.get(node.tag)
            if not kind or not node.get("name"): continue
            name = node.get("name")
            if name in result[kind]: duplicates.append(f"{kind}:{name} ({result[kind][name][0].name}, {path.name})")
            result[kind][name] = (path, node)
    return result, duplicates

def placeholders(value):
    found = []
    i = 0
    while i < len(value or ""):
        if value[i:i+2] == "%%": i += 2; continue
        m = PLACEHOLDER_RE.match(value or "", i)
        if m:
            # Bare % is not an argument; malformed forms are reported by mismatch if counterpart differs.
            if m.group(3): found.append((int(m.group(1)) if m.group(1) else None, m.group(3)))
            i = m.end(); continue
        i += 1
    return found

def node_values(kind, node):
    if kind == "string": return [text(node)]
    if kind == "array": return [text(x) for x in node.findall("item")]
    return {x.get("quantity"): text(x) for x in node.findall("item")}

def compare_placeholders(label, source, target, failures):
    if isinstance(source, dict):
        for cat in sorted(set(source) | set(target)):
            if placeholders(source.get(cat, "")) != placeholders(target.get(cat, "")):
                print(f"placeholder mismatch: {label}[{cat}]"); failures.append(label)
    else:
        if placeholders(source) != placeholders(target):
            print(f"placeholder mismatch: {label}"); failures.append(label)

def used_plurals():
    names = set()
    for p in (Path(__file__).resolve().parents[1] / "app/src/main").rglob("*.kt"):
        names.update(re.findall(r"R\.plurals\.([A-Za-z0-9_]+)", p.read_text(errors="replace")))
    return names

def main():
    failures = []
    data = {}; duplicates = []
    for locale in LOCALES:
        data[locale], dup = collect(locale); duplicates += [f"{locale}: {x}" for x in dup]
    if duplicates:
        print("duplicate resource names:", *duplicates, sep="\n"); failures += duplicates
    base = data["values"]; pl = data["values-pl"]
    base_strings = base["string"]; translatable = {k:v for k,v in base_strings.items() if v[1].get("translatable") != "false"}
    print(f"raw default strings: {len(base_strings)}")
    print(f"translatable default strings: {len(translatable)}")
    print(f"Polish translated strings: {len(pl['string'])}")
    print(f"arrays and item counts: " + ", ".join(f"{k}={len(v[1].findall('item'))}" for k,v in sorted(base['array'].items())))
    for locale in LOCALES:
        cur=data[locale]
        if locale == "values": continue
        missing = sorted(set(translatable) - set(cur["string"]))
        extra = sorted(set(cur["string"]) - set(base_strings))
        if locale == "values-pl":
            overrides = sorted(k for k,v in pl["string"].items() if k in base_strings and base_strings[k][1].get("translatable") == "false")
            print("nontranslatable overrides:", overrides or "none")
            if overrides: failures.append("nontranslatable overrides")
        print(f"coverage {LOCALE_LABELS[locale]}: {len(set(translatable)&set(cur['string']))}/{len(translatable)}")
        print(f"missing keys ({LOCALE_LABELS[locale]}): {missing or 'none'}")
        print(f"extra keys ({LOCALE_LABELS[locale]}): {extra or 'none'}")
        if locale == "values-pl" and missing: failures.append(f"missing {locale}")
        if locale == "values-pl" and extra: failures.append(f"extra {locale}")
        for name, (path,node) in translatable.items():
            if name in cur["string"]: compare_placeholders(f"{LOCALE_LABELS[locale]}:{name}", text(node), text(cur["string"][name][1]), failures)
        for name,(path,node) in base["array"].items():
            if name not in cur["array"]:
                print(f"missing array: {LOCALE_LABELS[locale]}:{name}")
                if locale == "values-pl": failures.append(f"missing array {locale}:{name}")
                continue
            src=node_values("array",node); dst=node_values("array",cur["array"][name][1])
            if len(src)!=len(dst) or any(not x.strip() for x in dst):
                print(f"array shape mismatch: {LOCALE_LABELS[locale]}:{name}")
                if locale == "values-pl": failures.append(f"array shape {locale}:{name}")
            for i,(a,b) in enumerate(zip(src,dst)): compare_placeholders(f"{LOCALE_LABELS[locale]}:{name}[{i}]",a,b,failures)
    names=used_plurals(); print("used plural names:", sorted(names))
    if names != {"drafts_count_plural","drafts_publish_all_confirmation_message_plural","drafts_publish_all_success_plural"}:
        print("unexpected used plural names"); failures.append("used plural names")
    print("plural category mismatches:")
    for locale in LOCALES:
        for name in sorted(names):
            node=data[locale]["plurals"].get(name)
            cats={x.get("quantity") for x in node[1].findall("item")} if node else set()
            required=REQUIRED_CATEGORIES[locale]
            if cats != required: print(f"{LOCALE_LABELS[locale]}:{name}: {sorted(cats)} expected {sorted(required)}"); failures.append(f"plural {locale}:{name}")
            if node and locale != "values":
                src=data["values"]["plurals"][name][1]
                src_items={x.get("quantity"): text(x) for x in src.findall("item")}
                for cat in required:
                    source_text=src_items.get(cat, src_items.get("other", ""))
                    compare_placeholders(f"{LOCALE_LABELS[locale]}:{name}[{cat}]", source_text, text(node[1].find(f"item[@quantity='{cat}']")), failures)
    print("placeholder mismatches:", "present" if failures and any(str(x).startswith(('PL:','DE:','JA:','KO:','zh')) for x in failures) else "none")
    audit=list(ROOT.rglob("*audit*"))
    if audit: print("audit-named production files:", [str(p.relative_to(ROOT)) for p in audit]); failures.append("audit files")
    print(f"Result: {'FAIL' if failures else 'PASS'}")
    return 1 if failures else 0
if __name__ == "__main__": sys.exit(main())
