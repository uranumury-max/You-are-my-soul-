#!/usr/bin/env python3
"""Guard product-specific source invariants that SQL/Kotlin compilation alone cannot express."""
from pathlib import Path
import re
import sys

ROOT = Path(__file__).resolve().parents[1]


def fail(message: str) -> None:
    print(f"FAIL product invariant: {message}", file=sys.stderr)
    raise SystemExit(1)


def main() -> int:
    migrations = sorted((ROOT / "supabase" / "migrations").glob("*.sql"))
    numbers = [int(re.match(r"^\d{8}(\d{4})_", path.name).group(1)) for path in migrations]
    if numbers != list(range(1, 14)):
        fail(f"expected ordered migrations 001–013, found {numbers}")

    gallery = (ROOT / "app/src/main/java/com/karam/youaremysoul/AnimationGallerySection.kt").read_text(encoding="utf-8")
    scene_metadata = len(re.findall(r'SoulScene\("', gallery))
    scene_branches = {int(value) for value in re.findall(r"^\s+(\d+) -> \{", gallery, re.MULTILINE)}
    if scene_metadata != 21 or scene_branches != set(range(1, 22)):
        fail(f"gallery must contain 21 scene definitions and 21 render branches; found {scene_metadata}, {sorted(scene_branches)}")

    importer = (ROOT / "app/src/main/java/com/karam/youaremysoul/WorldMediaImporter.kt").read_text(encoding="utf-8")
    validator = (ROOT / "app/src/main/java/com/karam/youaremysoul/WorldEventValidation.kt").read_text(encoding="utf-8")
    expected_mimes = {"image/jpeg", "image/png", "image/webp"}
    for source, label in ((importer, "importer"), (validator, "event validator")):
        match = re.search(r"ALLOWED_MIME_TYPES\s*=\s*setOf\(([^)]*)\)", source)
        if label == "event validator":
            match = re.search(r"val allowedMime = mimeType in setOf\(([^)]*)\)", source)
        if not match:
            fail(f"could not find MIME allowlist in {label}")
        found = set(re.findall(r'"(image/[^"]+)"', match.group(1)))
        if found != expected_mimes:
            fail(f"{label} MIME allowlist mismatch: {sorted(found)}")

    couple = (ROOT / "supabase/migrations/202611280012_two_member_couple_world.sql").read_text(encoding="utf-8")
    for required in ("v_active_count >= 2", "for update", "world_members_enforce_two_active", "two active participants"):
        if required not in couple.lower():
            fail(f"couple capacity migration missing invariant: {required}")

    print("OK product invariants: 13 migrations, 21 scene renderers, aligned image MIME, two-member world")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
