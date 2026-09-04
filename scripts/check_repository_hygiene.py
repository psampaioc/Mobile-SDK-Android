#!/usr/bin/env python3
"""Fail on tracked build products, private keys, or likely committed credentials."""

from __future__ import annotations

import re
import subprocess
import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
ARTIFACT_SUFFIXES = {".apk", ".aab", ".apks", ".jks", ".keystore", ".p12"}
PRIVATE_KEY = re.compile(rb"-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----")
ASSIGNMENT = re.compile(
    rb"(?im)^\s*(DJI_API_KEY|API_KEY|PASSWORD|SECRET|TOKEN)\s*[:=]\s*['\"]?([^\s'\"#]+)"
)
SAFE_VALUES = {b"", b"placeholder", b"changeme", b"example", b"dummy", b"<redacted>"}


def candidate_files() -> list[Path]:
    result = subprocess.run(
        ["git", "ls-files", "--cached", "--others", "--exclude-standard", "-z"],
        cwd=ROOT,
        check=True,
        capture_output=True,
    )
    return [ROOT / Path(name.decode()) for name in result.stdout.split(b"\0") if name]


def main() -> int:
    failures: list[str] = []
    files = candidate_files()
    for path in files:
        relative = path.relative_to(ROOT)
        # A cleanup may remove a tracked path before its deletion is committed.
        # `git ls-files` still lists it, but there is no worktree content to scan.
        if not path.is_file():
            continue
        if path.suffix.lower() in ARTIFACT_SUFFIXES:
            failures.append(f"tracked binary/credential artifact: {relative}")
            continue
        try:
            data = path.read_bytes()
        except OSError as exc:
            failures.append(f"cannot inspect {relative}: {exc}")
            continue
        if b"\0" in data[:8192]:
            continue
        if PRIVATE_KEY.search(data):
            failures.append(f"private key material: {relative}")
        for match in ASSIGNMENT.finditer(data):
            value = match.group(2).strip().lower()
            if value not in SAFE_VALUES and not value.startswith((b"${", b"%", b"system.getenv")):
                line = data.count(b"\n", 0, match.start()) + 1
                failures.append(
                    f"likely credential assignment: {relative}:{line} ({match.group(1).decode()})"
                )

    if failures:
        print("Repository hygiene check failed:", file=sys.stderr)
        for failure in failures:
            print(f"  - {failure}", file=sys.stderr)
        return 1
    print(f"Repository hygiene check passed ({len(files)} tracked/untracked files inspected).")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
