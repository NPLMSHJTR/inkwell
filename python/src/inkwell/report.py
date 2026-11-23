"""Markdown report for inkwell."""

from __future__ import annotations

from pathlib import Path
from typing import Any


def write_report(result: dict[str, Any], out_dir: Path) -> Path:
    out_dir.mkdir(parents=True, exist_ok=True)
    lines = [
        "# inkwell report",
        "",
        f"- documents: {result['documents']:,}",
        f"- terms: {result['terms']:,}",
        f"- postings size: {result['postings_mb']} MB",
        f"- corpus generation: {result['corpus_ms']:.0f} ms",
        f"- index build and server start: {result['startup_ms']:.0f} ms",
        "",
        "## Correctness",
        "",
        "| check | result |",
        "| --- | --- |",
        f"| AND query sets equal to the Python index | "
        f"{'yes' if result['checks']['sets_match'] else 'NO'} "
        f"({len(result['set_mismatches'])} mismatches of {result['term_queries']}) |",
        f"| phrase query sets equal to brute force | "
        f"{'yes' if result['checks']['phrases_match'] else 'NO'} "
        f"({len(result['phrase_mismatches'])} mismatches of {result['phrase_queries']}) |",
        "",
        "## Ranking and latency",
        "",
        "| metric | value |",
        "| --- | --- |",
        f"| nDCG@10 against latent topics | {result['ndcg10']:.4f} |",
        f"| query latency p50 | {result['latency_p50_ms']} ms |",
        f"| query latency p95 | {result['latency_p95_ms']} ms |",
        "",
        "## Reading nDCG",
        "",
        "Relevance is defined by the generator: every document has a latent topic, "
        "and a topic query is relevant to all documents of that topic. The engine "
        "only sees words, so 1.0 is unreachable — a perfect score would mean it "
        "recovered the hidden label from term statistics alone.",
    ]
    path = out_dir / "report.md"
    path.write_text("\n".join(lines) + "\n", encoding="utf-8", newline="\n")
    return path
