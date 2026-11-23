#!/usr/bin/env python3
"""Run inkwell end to end: corpus, index, HTTP server, verification."""

from __future__ import annotations

import os
import shutil
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def main() -> int:
    missing = [tool for tool in ("javac", "java") if shutil.which(tool) is None]
    if missing:
        print(f"missing required tools: {', '.join(missing)}", file=sys.stderr)
        return 2
    env = dict(os.environ)
    env["PYTHONPATH"] = str(ROOT / "python" / "src")
    command = [sys.executable, "-m", "inkwell", "run", "--workdir", "inkwell-run"]
    print("$ " + " ".join(command))
    subprocess.run(command, cwd=ROOT, check=True, env=env)
    print()
    print(f"report: {ROOT / 'inkwell-run' / 'reports' / 'report.md'}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
