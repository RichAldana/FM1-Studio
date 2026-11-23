// The reference: LOOM-8's own loom_samples.js on test data, its results written for check.kt to compare.
//   node gen.mjs /path/to/fm1-loom OUTDIR
import { mkdirSync, writeFileSync } from "node:fs";
import * as fs from "node:fs";
globalThis.__fs = fs;
import { join } from "node:path";
import { pathToFileURL } from "node:url";

const [loom, out] = process.argv.slice(2);
const L = await import(pathToFileURL(join(loom, "app/web/samples/loom_samples.js")).href);
mkdirSync(out, { recursive: true });
const w = (name, data) => writeFileSync(join(out, name), Buffer.from(data.buffer, data.byteOffset, data.byteLength));
let seed = 12345;
const rnd = () => { seed = (seed * 1103515245 + 12345) >>> 0; return (seed >>> 8) / 16777216 - 0.5; };
const meta = [];

// A. int16 samples -> a bank image (over the regions' seams), then built again from its own read-back
const lens = [30000, 5, 64, 65, 100000, 47000, 60000, 333, 22050, 41000, 70000];
const specs = lens.map((n, k) => {
  const s = new Int16Array(n);
  for (let i = 0; i < n; i++) s[i] = Math.round(20000 * Math.sin(i * (0.01 + k * 0.003)) + 4000 * rnd());
  w(`A${k}.s16`, s);
  const sp = { name: `Test_${k}!`, s, root: 40 + k, loop: k % 3 ? [k * 10, Math.max(1, n - k)] : null, bars: k % 5,
    rate: [11025, 16000, 22050, 32000][k % 4] };
  meta.push(["A", k, sp.name, sp.root, sp.loop ? sp.loop[0] : -1, sp.loop ? sp.loop[1] : -1, sp.bars, sp.rate].join("|"));
  return sp;
});
const A = L.userBank(specs);
w("A.img", A);
w("A2.img", L.userBank(L.keptOf(A)));

// B. sound at a file's rate -> each bank rate (resample, emphasis, to int16; loop points)
const srs = [44100, 48000, 22050];
srs.forEach((sr, k) => {
  const n = Math.round(sr * (0.3 + 0.2 * k)), x = new Float64Array(n);
  for (let i = 0; i < n; i++) x[i] = 0.6 * Math.sin(2 * Math.PI * (220 + 110 * k) * i / sr) * Math.exp(-i / sr * 3) + 0.05 * rnd();
  w(`B${k}.f64`, x);
  meta.push(["B", k, sr, n].join("|"));
  for (const r of L.RATES) {
    const a = L.atRate(x, sr, [100, n - 50], r);
    w(`B${k}_${r}.s16`, a.s);
    meta.push(["BL", k, r, a.loop[0], a.loop[1]].join("|"));
  }
});

// C. a bank read back (A's samples kept) plus new sounds, the rate chosen by build; D. the frames that send it
const items = [...L.keptOf(A).slice(0, 6)];
srs.forEach((sr, k) => {
  const x = new Float64Array(require_f64(join(out, `B${k}.f64`)));
  items.push({ name: `new ${k}`, x, sr, loop: null, root: k ? 60 : L.LOOP_ROOT, bars: k ? 0 : 2, rate: null });
});
function require_f64(p) { const b = (await_read(p)); return new Float64Array(b.buffer, b.byteOffset, b.byteLength / 8); }
function await_read(p) { return globalThis.__fs.readFileSync(p); }
const C = L.build(items);
w("C.img", C.img);
meta.push(["C", C.chose].join("|"));
const syx = [];
for (const [cmd, args] of L.frames(C.img)) syx.push(0xF0, ...L.HDR, cmd, ...args, 0xF7);
w("D.syx", new Uint8Array(syx));
writeFileSync(join(out, "meta.txt"), meta.join("\n") + "\n");
console.log(`reference: A ${A.length} B, C ${C.img.length} B at ${C.chose} Hz, D ${syx.length} B`);
