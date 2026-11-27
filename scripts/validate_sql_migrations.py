#!/usr/bin/env python3
"""Parse every checked-in Supabase migration before the Android build."""
from pathlib import Path
import sys

from pglast import parse_sql

ROOT = Path(__file__).resolve().parents[1]
MIGRATIONS = ROOT / "supabase" / "migrations"


def main() -> int:
    files = sorted(MIGRATIONS.glob("*.sql"))
    if not files:
        print("No SQL migrations found.", file=sys.stderr)
        return 1

    failed = False
    for path in files:
        try:
            statements = parse_sql(path.read_text(encoding="utf-8"))
            if not statements:
                raise ValueError("migration contains no SQL statements")
            print(f"OK {path.relative_to(ROOT)} ({len(statements)} statements)")
        except Exception as error:  # pglast exposes parser-specific exceptions
            failed = True
            print(f"FAIL {path.relative_to(ROOT)}: {error}", file=sys.stderr)

    return 1 if failed else 0


if __name__ == "__main__":
    raise SystemExit(main())
