"""Command-line interface for inkwell's verification harness."""

from __future__ import annotations

import argparse
import sys
from pathlib import Path

from . import __version__
from .harness import run
from .report import write_report

EXIT_OK = 0
EXIT_ERROR = 2


def cmd_run(args: argparse.Namespace) -> int:
    workdir = Path(args.workdir)
    result = run(
        workdir,
        documents=args.documents,
        port=args.port,
        seed=args.seed,
        term_queries=args.term_queries,
        phrase_queries=args.phrase_queries,
    )
    report = write_report(result, workdir / "reports")
    print(
        f"inkwell {__version__}  {result['documents']:,} docs  {result['terms']:,} terms  "
        f"{result['postings_mb']} MB postings"
    )
    print(
        f"AND sets: {'ok' if result['checks']['sets_match'] else 'MISMATCH'}  "
        f"phrases: {'ok' if result['checks']['phrases_match'] else 'MISMATCH'}"
    )
    print(
        f"nDCG@10 {result['ndcg10']:.4f}  p50 {result['latency_p50_ms']} ms  "
        f"p95 {result['latency_p95_ms']} ms"
    )
    print(f"report: {report}")
    return EXIT_OK if all(result["checks"].values()) else EXIT_ERROR


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        prog="inkwell", description="Build a 50k-document index and verify search end to end."
    )
    parser.add_argument("--version", action="version", version=f"inkwell {__version__}")
    subparsers = parser.add_subparsers(dest="command", required=True)
    run_cmd = subparsers.add_parser("run", help="generate a corpus, start the server, verify")
    run_cmd.add_argument("--documents", type=int, default=20_000)
    run_cmd.add_argument("--port", type=int, default=8765)
    run_cmd.add_argument("--seed", type=int, default=31)
    run_cmd.add_argument("--term-queries", type=int, default=50)
    run_cmd.add_argument("--phrase-queries", type=int, default=30)
    run_cmd.add_argument("--workdir", default="inkwell-run")
    run_cmd.set_defaults(func=cmd_run)
    return parser


def main(argv: list[str] | None = None) -> int:
    parser = build_parser()
    args = parser.parse_args(argv)
    try:
        return int(args.func(args))
    except (ValueError, OSError) as error:
        print(f"inkwell: {error}", file=sys.stderr)
        return EXIT_ERROR


if __name__ == "__main__":
    sys.exit(main())
