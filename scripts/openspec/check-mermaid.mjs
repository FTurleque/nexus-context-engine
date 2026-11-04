// Valide la syntaxe de tous les blocs ```mermaid des fichiers Markdown passés en argument.
// Hors CI : nécessite `mermaid` et `jsdom` (voir docs/developer/openspec.md pour les versions épinglées).
// Code de sortie : 0 tous valides, 1 au moins un diagramme invalide, 2 usage incorrect.
import fs from 'node:fs';
import { JSDOM } from 'jsdom';

const files = process.argv.slice(2);
if (files.length === 0) {
  console.error('usage: node check-mermaid.mjs <fichier.md>...');
  process.exit(2);
}

const dom = new JSDOM('<!doctype html><html><body></body></html>');
globalThis.window = dom.window;
globalThis.document = dom.window.document;
Object.defineProperty(globalThis, 'navigator', { value: dom.window.navigator, configurable: true });
const mermaid = (await import('mermaid')).default;
mermaid.initialize({ startOnLoad: false });

let invalid = 0;
let total = 0;
for (const file of files) {
  let text;
  try {
    text = fs.readFileSync(file, 'utf8');
  } catch (error) {
    console.error(`FAIL ${file}: fichier illisible (${error.code ?? error.message})`);
    process.exit(2);
  }
  for (const match of text.matchAll(/```mermaid[ \t]*\r?\n([\s\S]*?)```/g)) {
    total++;
    try {
      await mermaid.parse(match[1]);
    } catch (error) {
      invalid++;
      const detail = String(error?.message ?? error).split('\n').slice(0, 3).join(' | ');
      console.error(`FAIL ${file}: ${detail}`);
    }
  }
}
console.log(`mermaid: ${total} diagramme(s), ${invalid} invalide(s)`);
process.exit(invalid > 0 ? 1 : 0);
