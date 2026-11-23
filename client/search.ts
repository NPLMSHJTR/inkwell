#!/usr/bin/env node
/**
 * inkwell client: query a running server and print ranked hits.
 *
 * Usage: node --experimental-strip-types client/search.ts "bm25 phrase" --port 8765
 */

const args = process.argv.slice(2);
let port = 8765;
let limit = 10;
const terms: string[] = [];
for (let index = 0; index < args.length; index++) {
  if (args[index] === "--port") port = Number(args[++index]);
  else if (args[index] === "--limit") limit = Number(args[++index]);
  else terms.push(args[index]);
}
if (terms.length === 0) {
  console.error('usage: search.ts "query" [--port 8765] [--limit 10]');
  process.exit(2);
}
const query = terms.join(" ");
const url = `http://127.0.0.1:${port}/search?q=${encodeURIComponent(query)}&limit=${limit}`;

const response = await fetch(url);
if (!response.ok) {
  console.error(`inkwell returned ${response.status}`);
  process.exit(1);
}
const data = (await response.json()) as {
  total: number;
  took_ms: number;
  hits: { id: number; title: string; score: number; snippet: string }[];
};
console.log(`${data.total} matches in ${data.took_ms.toFixed(2)} ms`);
for (const hit of data.hits) {
  console.log(`${hit.score.toFixed(3)}  [${hit.id}] ${hit.title}`);
  console.log(`         ${hit.snippet.slice(0, 120)}`);
}
