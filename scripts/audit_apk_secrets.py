#!/usr/bin/env python3
"""Fail CI if a Supabase secret/service-role credential is embedded in an APK."""
from __future__ import annotations

import base64
import json
import re
import sys
import zipfile
from pathlib import Path

SECRET_KEY = re.compile(rb"sb_secret_[A-Za-z0-9_-]{20,}", re.IGNORECASE)
JWT = re.compile(
    rb"eyJ[A-Za-z0-9_-]{20,}\.[A-Za-z0-9_-]{20,}\.[A-Za-z0-9_-]{20,}"
)


def audit(apk_path: Path) -> list[str]:
    findings: list[str] = []
    with zipfile.ZipFile(apk_path) as apk:
        for entry in apk.infolist():
            if entry.is_dir():
                continue
            content = apk.read(entry)
            if SECRET_KEY.search(content):
                findings.append(f"{entry.filename}: Supabase secret-key prefix found")
            for token in JWT.findall(content):
                parts = token.split(b".")
                try:
                    payload = parts[1] + b"=" * (-len(parts[1]) % 4)
                    claims = json.loads(base64.urlsafe_b64decode(payload))
                except (ValueError, json.JSONDecodeError):
                    continue
                if str(claims.get("role", "")).lower() == "service_role":
                    findings.append(f"{entry.filename}: service-role JWT found")
                    break
    return findings


def main() -> int:
    if len(sys.argv) != 2:
        print("Usage: audit_apk_secrets.py <apk>")
        return 2
    apk = Path(sys.argv[1])
    if not apk.is_file():
        print(f"APK not found: {apk}")
        return 2
    findings = audit(apk)
    if findings:
        print("FAIL: forbidden privileged credentials detected in APK:")
        for finding in findings:
            print(f"- {finding}")
        return 1
    print("PASS: no recognizable Supabase secret/service-role credential found in APK.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
