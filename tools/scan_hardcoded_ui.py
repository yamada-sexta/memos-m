#!/usr/bin/env python3
"""Residual UI/i18n scan. Prints every candidate and its classification."""
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1] / "app/src/main"
patterns = [
 ("latex English UI", r'Equation Error|Invalid Syntax'),
 ("exception detail in UI", r'\be\.message\b|\.localizedMessage\b'),
 ("old plural consumer", r'publishSuccessFormat|R\.string\.drafts_publish_all_success'),
 ("old audit plural", r'profile_activity_count_plural'),
 ("widget error wrapper", r'widget_stats_error_format'),
 ("technical avatar description", r'filename\s*=\s*"avatar"'),
 ("broken kaomoji separator", r'Please stop… m\(_ _\)m'),
 ("dead English date", r'"Any"'),
]
files = list(ROOT.rglob("*.kt")) + list(ROOT.rglob("*.xml"))
found = 0
for label, pattern in patterns:
    rx = re.compile(pattern)
    for path in files:
        for lineno, line in enumerate(path.read_text(errors="replace").splitlines(), 1):
            if rx.search(line):
                rel = path.relative_to(ROOT)
                if label == "exception detail in UI" and str(rel).startswith("java/org/example/memosm/api/"):
                    print(f"CLASSIFIED [technical non-UI] {rel}:{lineno}: {line.strip()}")
                    continue
                print(f"CANDIDATE [{label}] {rel}:{lineno}: {line.strip()}")
                found += 1
if not found: print("No known residual candidates found.")
print(f"Classification: {'FAIL' if found else 'PASS'}; candidates={found}")
raise SystemExit(1 if found else 0)
