#!/usr/bin/env python3
"""Strict resource consistency checks for the supported MemosM locales."""
from pathlib import Path
import re
import sys
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1] / "app/src/main/res"
LOCALES = ["values", "values-pl", "values-de", "values-ja", "values-ko", "values-zh-rCN", "values-zh-rTW"]
PLURAL_NAMES = {"drafts_count_plural", "drafts_publish_all_confirmation_message_plural", "drafts_publish_all_success_plural"}

def parse(path):
    return ET.parse(path).getroot()

def resources(locale):
    out = {}
    for kind, filename in [("string", "strings.xml"), ("string", "strings_i18n.xml"), ("array", "strings.xml")]:
        path = ROOT / locale / filename
        if not path.exists(): continue
        for node in parse(path):
            if node.tag in {"string", "string-array"}:
                name = node.attrib.get("name")
                if name: out[name] = node
    return out

def plurals(locale):
    path = ROOT / locale / "plurals.xml"
    if not path.exists(): return {}
    return {n.attrib["name"]: n for n in parse(path) if n.tag == "plurals"}

def placeholders(text):
    return sorted(re.findall(r"%\d+\$[sd]", text or ""))

def main():
    failures = []
    print(f"Supported locales: {', '.join(LOCALES)}")
    print(f"Required plural groups: {len(PLURAL_NAMES)}")
    for locale in LOCALES:
        ps = plurals(locale)
        missing = PLURAL_NAMES - ps.keys(); extra = ps.keys() - PLURAL_NAMES
        print(f"{locale}: plurals={len(ps)} missing={sorted(missing)} extra={sorted(extra)}")
        if missing or extra: failures.append(f"{locale}: plural coverage")
    base = resources("values")
    pl = resources("values-pl")
    missing_pl = sorted(set(base) - set(pl))
    if missing_pl:
        print(f"values-pl: missing string/array keys: {missing_pl}")
        failures.append("values-pl: missing keys")
    for name, node in base.items():
        if node.tag == "string" and name in pl:
            if placeholders(node.text) != placeholders(pl[name].text):
                print(f"placeholder mismatch: values:{name} vs values-pl")
                failures.append(f"placeholder values-pl:{name}")
    for locale in LOCALES:
        current = resources(locale)
        if locale == "values-pl":
            continue
        for name, node in current.items():
            if node.tag == "string" and name in base:
                if placeholders(base[name].text) != placeholders(node.text):
                    print(f"placeholder mismatch: values:{name} vs {locale}")
                    failures.append(f"placeholder {locale}:{name}")
    # Verify the exact three groups and no audit-named production resources.
    audit = list(ROOT.rglob("*audit*"))
    if audit:
        print("audit-named production files:", [str(p.relative_to(ROOT)) for p in audit]); failures.append("audit files")
    print(f"Result: {'FAIL' if failures else 'PASS'}")
    return 1 if failures else 0

if __name__ == "__main__": sys.exit(main())
