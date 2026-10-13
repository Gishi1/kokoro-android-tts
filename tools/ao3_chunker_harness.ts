/**
 * Run ao3-reader's real narration chunker over a corpus and emit JSONL, so its
 * output can be scored against Kokoro ground truth by
 * `tools/splitter_conformance.py --chunks-jsonl`.
 *
 * The ao3-reader repository is imported READ-ONLY. Nothing here writes to it.
 *
 * ao3-reader applies `mergeChunks(p, { max: 300, min: 100 })` in
 * web/src/views/Reader.svelte:237 for the MSE narration variant, so those are
 * the parameters reproduced here.
 *
 * Usage (tsx lives in the ao3-reader node_modules):
 *   AO3_READER=../ao3-reader \
 *   ../ao3-reader/node_modules/.bin/tsx tools/ao3_chunker_harness.ts \
 *       --corpus data/corpus_mixed.txt --mode lines
 *
 * AO3_READER defaults to `../ao3-reader`, resolved against the current directory
 * (so the usage above works from the repository root); an absolute path also works.
 * The chunker is imported dynamically so no checkout path is baked in.
 */

import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { pathToFileURL } from 'node:url';

interface Chunk {
  text: string;
}

interface Chunker {
  chunkParagraphs: (paragraphs: string[]) => string[][];
  mergeChunks: (chunks: string[], opts: { max: number; min: number }) => Chunk[];
}

const MAX = 300;
const MIN = 100;

function arg(name: string, fallback: string): string {
  const i = process.argv.indexOf(`--${name}`);
  return i >= 0 && i + 1 < process.argv.length ? process.argv[i + 1] : fallback;
}

// Not top-level `await`: tsx compiles this file to CJS (no `"type": "module"` in
// reach), and esbuild rejects top-level await in that output format.
async function main(): Promise<void> {
  const ao3Reader = resolve(process.env.AO3_READER ?? '../ao3-reader');
  const chunkerPath = resolve(ao3Reader, 'web/src/lib/narration/chunker.ts');
  const { chunkParagraphs, mergeChunks } = (await import(
    pathToFileURL(chunkerPath).href
  )) as Chunker;

  const corpusPath = arg('corpus', 'data/corpus_mixed.txt');
  const mode = arg('mode', 'lines');

  const raw = readFileSync(corpusPath, 'utf8');

  // `lines` : one utterance per line (matches how the mixed corpus is authored)
  // `paras` : blank-line separated paragraphs, single newlines are soft wraps
  const paragraphs: string[] =
    mode === 'paras'
      ? raw
          .split(/\n[ \t]*\n+/)
          .map((p) => p.replace(/[ \t]*\n[ \t]*/g, ' ').trim())
          .filter((p) => p.length > 0 && !p.startsWith('#'))
      : raw
          .split('\n')
          .map((l) => l.trim())
          .filter((l) => l.length > 0 && !l.startsWith('#'));

  const byParagraph = chunkParagraphs(paragraphs);
  const merged = byParagraph.map((p) => mergeChunks(p, { max: MAX, min: MIN }));

  let index = 0;
  const lines: string[] = [];
  for (const para of merged) {
    for (const c of para) {
      if (!c.text.trim()) continue;
      lines.push(
        JSON.stringify({
          index: index++,
          file: corpusPath.split('/').pop(),
          line: 0,
          // ao3-reader has no token model; char length is emitted for reference only.
          est: c.text.length,
          text: c.text,
        }),
      );
    }
  }
  process.stdout.write(lines.join('\n') + '\n');
  process.stderr.write(
    `ao3 chunker: ${paragraphs.length} paragraphs -> ${index} chunks (max=${MAX}, min=${MIN})\n`,
  );
}

main().catch((err: unknown) => {
  process.stderr.write(`ao3 chunker harness failed: ${String(err)}\n`);
  process.exit(1);
});
