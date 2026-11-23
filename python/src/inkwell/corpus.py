"""Synthetic corpus with latent topics, used to give ranking a meaning.

Each document belongs to one topic. Its body mixes topic words with common words,
so a query built from topic words has a clear relevant set — the documents of that
topic — that is not simply "documents containing the words".
"""

from __future__ import annotations

import random
from dataclasses import dataclass
from pathlib import Path
from typing import Any

TOPICS = 60
TOPIC_WORDS = 40
COMMON_WORDS = 2_000


@dataclass
class Corpus:
    paths: dict[str, Path]
    stats: dict[str, int]


def _vocabulary(rng: random.Random, prefix: str, count: int) -> list[str]:
    return [f"{prefix}{index:04d}" for index in range(count)]


def build(directory: Path, documents: int = 50_000, seed: int = 31) -> dict[str, Any]:
    directory.mkdir(parents=True, exist_ok=True)
    rng = random.Random(seed)
    # Each topic needs its own vocabulary; reusing one list would make the
    # latent labels meaningless and every query match every topic.
    topic_words = [[f"t{topic:02d}w{index:02d}" for index in range(TOPIC_WORDS)] for topic in range(TOPICS)]
    common = _vocabulary(rng, "c", COMMON_WORDS)

    docs_path = directory / "documents.tsv"
    queries: list[dict[str, Any]] = []
    doc_topics: list[int] = []

    topics_path = directory / "topics.tsv"
    topic_handle = topics_path.open("w", encoding="utf-8", newline="\n")
    with docs_path.open("w", encoding="utf-8", newline="\n") as handle:
        for doc_id in range(documents):
            topic = rng.randrange(TOPICS)
            doc_topics.append(topic)
            words = topic_words[topic]
            secondary = None
            if rng.random() < 0.5:
                secondary = (topic + rng.randrange(1, TOPICS)) % TOPICS
            body = []
            for _ in range(rng.randint(40, 80)):
                roll = rng.random()
                if roll < 0.6:
                    body.append(rng.choice(words))
                elif roll < 0.75 and secondary is not None:
                    # Distractor words: a document may mention another topic's
                    # vocabulary without being relevant to it. This is what makes
                    # ranking measurable instead of a lookup.
                    body.append(rng.choice(topic_words[secondary]))
                else:
                    body.append(rng.choice(common))
            title = " ".join(rng.choice(words) for _ in range(5))
            handle.write(f"{doc_id}\t{title}\t{' '.join(body)}\n")
            topic_handle.write(f"{doc_id}\t{topic}\n")

    topic_handle.close()
    for index in range(100):
        topic = index % TOPICS
        chosen = rng.sample(topic_words[topic], rng.randint(2, 3))
        queries.append({"id": f"q{index:03d}", "kind": "topic", "query": " ".join(chosen), "topic": topic})

    payload = {
        "documents": documents,
        "topics": TOPICS,
        "queries": queries,
    }
    (directory / "queries.json").write_text(__import__("json").dumps(payload) + "\n", encoding="utf-8")
    return {"documents": documents, "queries": len(queries), "doc_topics": doc_topics}
