#!/usr/bin/env python3
"""Emit a GitHub Actions matrix for platforms that already have an origin template."""

import json
import sys
from pathlib import Path


def main() -> int:
    root = Path(sys.argv[1] if len(sys.argv) > 1 else ".").resolve()
    entries = []
    current = None
    spec = root / "platforms.yml"
    for raw in spec.read_text(encoding="utf-8").splitlines():
        line = raw.split("#", 1)[0].rstrip()
        if not line.strip():
            continue
        if not line.startswith(" "):
            current = None
            continue
        stripped = line.strip()
        if line.startswith("  ") and not line.startswith("   ") and stripped.endswith(":"):
            current = stripped[:-1]
        elif current and stripped.startswith("versions:"):
            value = stripped.split(":", 1)[1].strip().strip("[]")
            versions = [v.strip() for v in value.split(",") if v.strip()]
            if (root / "platforms" / current / "origin").is_dir():
                entries.extend({"platform": current, "version": v} for v in versions)
    print(json.dumps({"include": entries}))
    return 0


if __name__ == "__main__":
    sys.exit(main())
