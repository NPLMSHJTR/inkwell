# inkwell

**中文简介** — 一个可运行的搜索引擎：位置倒排索引、BM25 排序、短语与排除查询、HTTP API，以及内置搜索页。20,000 篇文档的索引在 0.6 秒内建好；10 万个答案由独立的 Python 索引逐条核对。

**English** — A runnable search engine: positional inverted index, BM25 ranking, phrase and exclusion queries, an HTTP API, and a built-in search page. The 20,000-document index builds in 0.6 seconds, and 100 result sets are checked against an independent Python index.

```console
$ python scripts/demo.py
inkwell 0.1.0  20,000 docs  4,400 terms  13.3 MB postings
AND sets: ok  phrases: ok
nDCG@10 1.0000  p50 0.11 ms  p95 0.22 ms
```

Then open `http://127.0.0.1:8765` and search.

## Architecture

```
documents.tsv ──► Java index ──► BM25 + phrase matcher ──► HTTP /search
                      │                                      │
                      ├── postings: docs[], offsets[], positions[]
                      │
Python corpus ────────┴──► independent index ──► result-set checks
TypeScript client ───────────────────────────► CLI queries against the API
```

| Component | Language | Role |
| --- | --- | --- |
| engine + server | Java 22 (built-in HTTP server) | index, ranking, phrase matching, UI |
| corpus + verifier | Python 3.11 | 20k documents, oracle index, nDCG, latency |
| client | TypeScript (Node 22) | command-line search against a running server |

## Results

| Metric | Value |
| --- | --- |
| Documents / terms | 20,000 / 4,400 |
| Postings size | 13.3 MB |
| Index build + server start | 593 ms |
| AND query sets equal to the Python index | 100/100 |
| Phrase query sets equal to brute force | 30/30 |
| Query latency p50 / p95 | 0.11 ms / 0.22 ms |
| nDCG@10 against latent topics | 1.0000 |

**Read the nDCG honestly.** Documents belong to a latent topic and draw most of
their words from it; a query built from topic words is relevant to that topic's
documents. BM25, which ranks by term frequency, recovers that label perfectly on
this data. A score of 1.0 says the ranker is self-consistent with the generator,
not that it would beat a production engine on real relevance judgments.

## Query syntax

| Form | Meaning |
| --- | --- |
| `alpha beta` | both terms required |
| `"alpha beta"` | exact phrase, positions must be consecutive |
| `alpha -beta` | alpha present, beta excluded |

## Quick start

```bash
python scripts/demo.py          # builds, verifies, and leaves the server running for the demo
```

Or manually:

```bash
javac -d inkwell-out java/src/inkwell/*.java
java -Xmx512m -cp inkwell-out inkwell.Server --docs inkwell-run/data/documents.tsv --port 8765
node --experimental-strip-types client/search.ts "t00w01 t00w05" --limit 5
```

Resource use is deliberately modest: 512 MB heap, 20,000 documents, and the
verification harness stops the server when it finishes.

## Limits

- **In-memory only.** No persistence, no incremental updates; every start rebuilds
  from the TSV.
- **No stemming or synonyms.** Tokenisation is lowercase `[a-z0-9]+`; "running"
  and "run" are different terms.
- **No compression.** Postings are raw int arrays, which is why 13 MB is spent on
  20,000 documents.
- **Single node, no sharding.** The index is a `HashMap` in one JVM; concurrency
  is four HTTP handler threads reading immutable postings.

## Repository layout

```
java/src/inkwell/Engine.java    index, BM25, phrase matching, snippets
java/src/inkwell/Server.java    HTTP API and the embedded search page
python/src/inkwell/corpus.py    topic corpus and queries
python/src/inkwell/harness.py   independent index, checks, nDCG, latency
client/search.ts                command-line client
scripts/demo.py                 the whole pipeline in one command
```

## Development

```bash
PYTHONPATH=python/src python -m unittest discover -s python/tests -t python/tests -v
cd python && pip install -e ".[dev]" && ruff check . && mypy
```

## License

MIT. See [LICENSE](LICENSE).
