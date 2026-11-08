// Ground truth: tokenize a file with VS Code's TextMate grammar and resolve
// colors with vscode-textmate's theme engine (what VS Code renders).
// Usage: node generate.mjs <node_modules dir> <grammars dir> <scopeName> <theme.json> <file>
import { createRequire } from 'node:module';
import fs from 'node:fs';
import path from 'node:path';
const [nm, grammarDir, scopeName, themePath, file] = process.argv.slice(2);
const require = createRequire(path.join(nm, 'x.js'));
const vsctm = require('vscode-textmate');
const onig = require('vscode-oniguruma');
const wasm = fs.readFileSync(path.join(nm, 'vscode-oniguruma/release/onig.wasm')).buffer;
await onig.loadWASM(wasm);
const stripJsonc = (s) => {
  let out = '', i = 0, inStr = false;
  while (i < s.length) {
    const c = s[i];
    if (inStr) { out += c; if (c === '\\') { out += s[i + 1]; i += 2; continue; } if (c === '"') inStr = false; i++; continue; }
    if (c === '"') { inStr = true; out += c; i++; continue; }
    if (c === '/' && s[i + 1] === '/') { while (i < s.length && s[i] !== '\n') i++; continue; }
    if (c === '/' && s[i + 1] === '*') { i += 2; while (i < s.length && !(s[i] === '*' && s[i + 1] === '/')) i++; i += 2; continue; }
    out += c; i++;
  }
  return out.replace(/,(\s*[}\]])/g, '$1');
};
const theme = JSON.parse(stripJsonc(fs.readFileSync(themePath, 'utf8')));
const colors = theme.colors || {};
const settings = [{ settings: { foreground: colors['editor.foreground'] || (theme.type === 'light' ? '#000000' : '#D4D4D4'), background: colors['editor.background'] || '#000000' } }];
for (const r of theme.tokenColors || []) settings.push(r);
const registry = new vsctm.Registry({
  onigLib: Promise.resolve({ createOnigScanner: (p) => new onig.OnigScanner(p), createOnigString: (s) => new onig.OnigString(s) }),
  loadGrammar: async (sn) => {
    for (const f of fs.readdirSync(grammarDir)) {
      const p = path.join(grammarDir, f);
      const raw = fs.readFileSync(p, 'utf8');
      if (JSON.parse(raw).scopeName === sn) return vsctm.parseRawGrammar(raw, p);
    }
    return null;
  },
});
registry.setTheme({ name: theme.name, settings });
const colorMap = registry.getColorMap();
const grammar = await registry.loadGrammar(scopeName);
const lines = fs.readFileSync(file, 'utf8').split('\n');
let stack = vsctm.INITIAL;
const out = [];
lines.forEach((line, ln) => {
  const r1 = grammar.tokenizeLine(line, stack);
  const r2 = grammar.tokenizeLine2(line, stack);
  stack = r1.ruleStack;
  // tokenizeLine2: [start, metadata, start, metadata, ...]
  const bin = r2.tokens;
  for (const t of r1.tokens) {
    const text = line.slice(t.startIndex, t.endIndex);
    if (!text.trim()) continue;
    let meta = 0;
    for (let k = 0; k < bin.length; k += 2) if (bin[k] <= t.startIndex) meta = bin[k + 1];
    const fg = colorMap[vsctm.EncodedTokenAttributes ? vsctm.EncodedTokenAttributes.getForeground(meta) : (meta >>> 15) & 0x1ff];
    const fs_ = (meta >>> 11) & 0xf;
    out.push({ line: ln, start: t.startIndex, end: t.endIndex, text, fg: fg.toUpperCase(), fontStyle: fs_, scopes: t.scopes });
  }
});
process.stdout.write(JSON.stringify(out) + '\n');
