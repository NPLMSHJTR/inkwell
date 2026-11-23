"""End-to-end verification for inkwell.

The Java engine is treated as a black box behind HTTP. Python builds an
independent inverted index over the same tokens, answers the same queries by set
intersection, and compares document ids; ranking is scored against the generator's
latent topics with nDCG@10.
"""

from __future__ import annotations

import json
import math
import re
import subprocess
import time
import urllib.parse
import urllib.request
from pathlib import Path
from typing import Any

from . import corpus

ROOT = Path(__file__).resolve().parents[3]
CLASSES = ROOT / "inkwell-out"
TOKEN = re.compile(r"[a-z0-9]+")


def tokenize(text: str) -> list[str]:
    return TOKEN.findall(text.lower())


def compile_java() -> None:
    sources = sorted(str(path) for path in (ROOT / "java" / "src" / "inkwell").glob("*.java"))
    subprocess.run(["javac", "-d", str(CLASSES), *sources], check=True, capture_output=True)


def wait_for(port: int, timeout: float = 120.0) -> None:
    deadline = time.time() + timeout
    while time.time() < deadline:
        try:
            with urllib.request.urlopen(f"http://127.0.0.1:{port}/stats", timeout=2) as response:
                if response.status == 200:
                    return
        except Exception:
            time.sleep(0.2)
    raise TimeoutError("inkwell did not start")


def search(port: int, query: str, limit: int = 1000) -> dict[str, Any]:
    url = f"http://127.0.0.1:{port}/search?q={urllib.parse.quote(query)}&limit={limit}"
    with urllib.request.urlopen(url, timeout=10) as response:
        payload: dict[str, Any] = json.loads(response.read().decode("utf-8"))
        return payload


def load_documents(path: Path) -> tuple[list[list[str]], dict[str, set[int]]]:
    tokens: list[list[str]] = []
    postings: dict[str, set[int]] = {}
    with path.open(encoding="utf-8") as handle:
        for line in handle:
            parts = line.rstrip("\n").split("\t", 2)
            if len(parts) < 3:
                continue
            doc_id = int(parts[0])
            doc_tokens = tokenize(parts[1] + " " + parts[2])
            tokens.append(doc_tokens)
            for token in set(doc_tokens):
                postings.setdefault(token, set()).add(doc_id)
    return tokens, postings


def contains_phrase(tokens: list[str], phrase: list[str]) -> bool:
    width = len(phrase)
    return any(tokens[index : index + width] == phrase for index in range(len(tokens) - width + 1))


def ndcg(hits: list[int], relevant: set[int], k: int = 10) -> float:
    if not relevant:
        return 0.0
    dcg = sum(1.0 / math.log2(rank + 2) for rank, doc in enumerate(hits[:k]) if doc in relevant)
    ideal = sum(1.0 / math.log2(rank + 2) for rank in range(min(k, len(relevant))))
    return dcg / ideal if ideal else 0.0


def run(
    workdir: Path,
    documents: int = 20_000,
    port: int = 8765,
    seed: int = 31,
    term_queries: int = 50,
    phrase_queries: int = 30,
) -> dict[str, Any]:
    workdir.mkdir(parents=True, exist_ok=True)
    data = workdir / "data"
    started = time.perf_counter()
    corpus.build(data, documents=documents, seed=seed)
    corpus_ms = (time.perf_counter() - started) * 1000

    compile_java()
    process = subprocess.Popen(
        [
            "java",
            "-Xmx512m",
            "-cp",
            str(CLASSES),
            "inkwell.Server",
            "--docs",
            str(data / "documents.tsv"),
            "--port",
            str(port),
        ],
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
    )
    try:
        started = time.perf_counter()
        wait_for(port)
        startup_ms = (time.perf_counter() - started) * 1000
        with urllib.request.urlopen(f"http://127.0.0.1:{port}/stats", timeout=5) as response:
            stats = json.loads(response.read().decode("utf-8"))

        tokens, postings = load_documents(data / "documents.tsv")
        topics = {}
        for line in (data / "topics.tsv").read_text(encoding="utf-8").splitlines():
            doc_id, topic = line.split("\t")
            topics[int(doc_id)] = int(topic)

        queries = json.loads((data / "queries.json").read_text(encoding="utf-8"))["queries"]
        latencies: list[float] = []
        set_mismatches = []
        scores: list[float] = []

        for query in queries:
            terms = tokenize(query["query"])
            expected: set[int] = set()
            for term in terms:
                expected = postings.get(term, set()) if not expected else expected & postings.get(term, set())
            response = search(port, query["query"])
            latencies.append(response["took_ms"])
            actual = [hit["id"] for hit in response["hits"]]
            if set(actual) != expected:
                set_mismatches.append(
                    {"query": query["query"], "expected": len(expected), "actual": len(actual)}
                )
            relevant = {doc for doc, topic in topics.items() if topic == query["topic"]}
            scores.append(ndcg(actual, relevant))

        phrase_checks = []
        phrase_mismatches = []
        step = max(1, len(tokens) // phrase_queries)
        for probe in range(0, len(tokens), step)[:phrase_queries]:
            doc_tokens = tokens[probe]
            if len(doc_tokens) < 6:
                continue
            position = 0
            phrase = doc_tokens[position : position + 2]
            query_text = f'"{phrase[0]} {phrase[1]}"'
            candidates = postings.get(phrase[0], set()) & postings.get(phrase[1], set())
            expected_phrase = {doc for doc in candidates if contains_phrase(tokens[doc], phrase)}
            response = search(port, query_text)
            actual_phrase = {hit["id"] for hit in response["hits"]}
            phrase_checks.append(len(expected_phrase))
            if actual_phrase != expected_phrase:
                phrase_mismatches.append(
                    {"phrase": query_text, "expected": len(expected_phrase), "actual": len(actual_phrase)}
                )

        latencies.sort()
        return {
            "documents": stats["documents"],
            "terms": stats["terms"],
            "postings_mb": round(stats["postings_bytes"] / 1e6, 1),
            "corpus_ms": round(corpus_ms, 0),
            "startup_ms": round(startup_ms, 0),
            "term_queries": len(queries),
            "set_mismatches": set_mismatches,
            "phrase_queries": len(phrase_checks),
            "phrase_mismatches": phrase_mismatches,
            "ndcg10": round(sum(scores) / len(scores), 4),
            "latency_p50_ms": round(latencies[len(latencies) // 2], 2),
            "latency_p95_ms": round(latencies[int(len(latencies) * 0.95)], 2),
            "checks": {
                "sets_match": not set_mismatches,
                "phrases_match": not phrase_mismatches,
            },
        }
    finally:
        process.terminate()
        try:
            process.wait(timeout=10)
        except subprocess.TimeoutExpired:
            process.kill()
