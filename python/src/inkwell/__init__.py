"""inkwell: a positional search engine verified end to end."""

from __future__ import annotations

__version__ = "0.1.0"

from .corpus import build  # noqa: E402
from .harness import run, tokenize  # noqa: E402

__all__ = ["__version__", "build", "run", "tokenize"]
