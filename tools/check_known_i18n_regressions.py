#!/usr/bin/env python3
"""Checks a curated set of known localization regressions."""
from pathlib import Path
import re
import sys
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1] / "app/src/main"
RES = ROOT / "res"
patterns = [
 ("stale Polish invalid-instance text", r"Nieprawidłowa instancja Memos lub adres URL serwera"),
 ("stale Polish attachment filename", r">Plik<"),
 ("Equation Error / Invalid Syntax", r"Equation Error|Invalid Syntax"),
 ("raw exception detail in known UI paths", r"\be\.message\b|\.localizedMessage\b"),
 ("publishSuccessFormat", r"publishSuccessFormat"),
 ("widget_stats_error_format", r"widget_stats_error_format"),
 ("filename = avatar", r'filename\s*=\s*"avatar"'),
 ("old audit resource filename", r"audit"),
]
found=0
# Technical non-translatable resources must not be overridden in Polish.
base={}
for p in (RES/'values').glob('*.xml'):
    for n in ET.parse(p).getroot():
        if n.tag=='string' and n.get('name'): base[n.get('name')]=n
for p in (RES/'values-pl').glob('*.xml'):
    for n in ET.parse(p).getroot():
        if n.tag=='string' and n.get('name') in base and base[n.get('name')].get('translatable')=='false':
            print(f"CANDIDATE [nontranslatable Polish override] {p.relative_to(ROOT)}:{n.get('name')}"); found+=1
files=list(ROOT.rglob('*.kt'))+list(ROOT.rglob('*.xml'))
for label,pattern in patterns:
    rx=re.compile(pattern)
    for path in files:
        rel=path.relative_to(ROOT)
        if 'check_known_i18n_regressions.py' in str(rel): continue
        for line_no,line in enumerate(path.read_text(errors='replace').splitlines(),1):
            if rx.search(line):
                # API diagnostics are technical, not UI regressions.
                if label.startswith('raw exception') and str(rel).startswith('java/org/example/memosm/api/'):
                    continue
                print(f"CANDIDATE [{label}] {rel}:{line_no}: {line.strip()}"); found+=1
for path in RES.rglob('*audit*'):
    print(f"CANDIDATE [old audit resource filename] {path.relative_to(RES)}"); found+=1
print(f"Curated regression check: {'FAIL' if found else 'PASS'}; candidates={found}")
sys.exit(1 if found else 0)
