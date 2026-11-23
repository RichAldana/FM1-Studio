// The reference: SLOOP's own CHOP functions (taken out of its web/editor.html as they are) on SLOOP's own test signal
// (web/test_web.mjs chopTests), their results written for check.kt.   node gen.mjs /path/to/sloop-fm1 OUTDIR
import { readFileSync, writeFileSync, mkdirSync } from "node:fs";
import { join } from "node:path";
const [sloop, out] = process.argv.slice(2);
mkdirSync(out, { recursive: true });
const html = readFileSync(join(sloop, "web/editor.html"), "utf8").split("\n");
const from = html.findIndex((l) => l.startsWith("const CHOP = "));
const to = html.findIndex((l, i) => i > from && l.startsWith("/* a mono 16-bit WAV at SMP.RATE */"));
const smp = html.find((l) => l.startsWith("const SMP = "));
const notes = html.find((l) => /^const NOTE_NAMES\s*=/.test(l)) || 'const NOTE_NAMES = ["C","C#","D","D#","E","F","F#","G","G#","A","A#","B"];';
const E = new Function(`${smp}\n${notes}\n${html.slice(from, to).join("\n")}\nreturn { SMP, CHOP, chopNovelty, chopAttack, chopHits, chopSnap, chopGrid, chopEqual, chopList, chopPick, chopFit, chopZones };`)();
const w = (name, a) => writeFileSync(join(out, name), Buffer.from(a.buffer, a.byteOffset, a.byteLength));

// SLOOP's test signal: a break of 8 hits (kick- and snare-like, loud and ghost) over a quiet noise floor
const R = E.SMP.RATE, N = R * 4;
let seed = 7;
const rnd = () => ((seed = (seed * 1103515245 + 12345) >>> 0) / 2 ** 32) * 2 - 1;
const x = new Float64Array(N);
for (let i = 0; i < N; i++) x[i] = rnd() * 0.002;
const HITS = [0.10, 0.52, 0.93, 1.31, 1.80, 2.26, 2.70, 3.33].map((t) => Math.round(t * R));
const AMP = [1, 0.8, 0.25, 0.9, 1, 0.3, 0.85, 0.7];
HITS.forEach((h, k) => {
  for (let i = 0; i < R * 0.3 && h + i < N; i++) {
    const env = Math.exp(-i / (k % 2 ? 1500 : 3000)) * AMP[k];
    x[h + i] += env * (k % 2 ? rnd() * 0.8 : Math.sin(2 * Math.PI * 60 * i / R) * 0.9 + rnd() * 0.1);
  }
});
w("x.f64", x);
const nov = E.chopNovelty(x);
w("nov.f64", nov); w("level.f64", nov.level);
const lines = [`HITS|${HITS.join(",")}`];
for (let s = 1; s <= 10; s++) lines.push(`hits|${s}|${E.chopHits(x, nov, s).join(",")}`);
for (const p of [HITS[3] + 0.035 * R, HITS[4] - 0.03 * R, 1.1 * R, 10, N - 5, 33333.3]) lines.push(`snap|${p}|${E.chopSnap(x, nov, p)}`);
for (const [bpm, b] of [[90, 1], [120, 0.5], [97.5, 4], [40, 0.25]]) lines.push(`grid|${bpm}|${b}|${E.chopGrid(1000, N, bpm, b).join(",")}`);
for (const n of [1, 3, 4, 7, 16]) lines.push(`equal|${n}|${E.chopEqual(100, 90001, n).join(",")}`);
const opt = [null, { off: true }, { len: 30 }];
lines.push(`list|${E.chopList([10, 50, 400], 1000, 100, opt).map((c) => `${c.start}-${c.end}-${c.i}-${c.off ? 1 : 0}-${c.full}`).join(",")}`);
lines.push(`fit|${E.chopFit([{ start: 0, end: 100 }, { start: 0, end: 300 }], 250)}|${E.chopFit([{ start: 0, end: 100 }], 250)}`);
const hits = E.chopHits(x, nov, 5), zones = E.chopZones(x, E.chopList(hits, N), 60, 0);
zones.forEach((z, k) => w(`zone${k}.s16`, z.s));
lines.push(`zones|${hits.join(",")}|${zones.length}`);
writeFileSync(join(out, "ref.txt"), lines.join("\n") + "\n");
console.log(`SLOOP reference: ${hits.length} hits at sensitivity 5, ${zones.length} zones`);
