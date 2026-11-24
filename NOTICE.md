# FM1 Mix: licences

FM1 Mix as a whole is distributed under the **GNU General Public License, version 3 only** (`LICENSE`), because it
includes code copied from SLOOP (GPL-3.0-only). Its parts keep their own licences:

| Part | Files | Licence | Source |
| --- | --- | --- | --- |
| The slicer (CHOP) | `Chop.kt`, `SlicerModel.kt`, `SlicerActivity.kt` | GPL-3.0-only | SLOOP's web editor, https://github.com/isod89/sloop-fm1 (Copyright (C) 2026 Leo Kuroshita / Hügelton Instruments, and SLOOP's authors) |
| Samples into LOOM-8 | `LoomSamples.kt`, `LoomLink.kt` | MPL-2.0 | LOOM-8's `app/web/samples/loom_samples.js`, https://github.com/charlesvestal/fm1-loom (Copyright (C) 2026 Charles Vestal) |
| The emulator core | `third_party/fm1-emu` (fetched at build time) | MIT | https://github.com/joakimeriksson/fm1-emu (Copyright (c) Joakim Eriksson) |
| The firmware list shipped | `app/src/main/assets/firmwares-fm1emu.json` (fm1-emu's `web/firmwares.json`) | MIT | https://github.com/joakimeriksson/fm1-emu, built from The Firmware Hub's catalogue (https://thefirmwarehub.com, zvenson) |
| The rest of the app | `native/` (but `native/fx/generated`), the other Kotlin files | GPL-3.0-only as part of FM1 Mix | this project |
| The pad's Faust effects | `native/fx/generated/*.h`, generated from `native/fx/faust/*.dsp` | the code of each Faust library function used, under its own licence: MIT-style STK-4.3 (Julius O. Smith III: Moog filter, Zita reverb, phaser, flanger, overdrive, compressor), LGPL with the Faust exception (GRAME: delay, pitch shifter, smoothing), GPL-2.0-or-later (Julian Parker, Till Bovermann: greyhole, the « Nuage diffus »). Details: `native/fx/generated/README.md` | Faust 2.70.3 and its libraries, https://faust.grame.fr, https://github.com/grame-cncm/faustlibraries (GRAME, Centre National de Création Musicale) |
| Reading the projects' FLAC sounds | the `claxon` crate (Rust, a dependency fetched by Cargo); `native/src/flac.rs` (the encoder, this project's own) | Apache-2.0 (licence text: `app/src/main/assets/licenses/claxon-Apache-2.0.txt`) | Ruud van Asseldonk, https://github.com/ruuda/claxon |
| The pad's other effects and engine | `native/fx/fxengine.cpp` (tape stop, beat repeat), `airwin.cpp`, `surge.cpp`, `mi.cpp`, `resample.h` (the wrappers), `native/src/fx.rs` | GPL-3.0-only as part of FM1 Mix | this project |
| Airwindows effects (Isolateur, Cosmos, Reverb rétro, Lo-fi, Compression) | `native/fx/third_party/airwin2rack` | MIT | Chris Johnson (Airwindows), https://github.com/airwindows/airwindows; airwin2rack, Paul Walker, https://github.com/baconpaul/airwin2rack |
| Surge effects (Filtre analogique, Delay ping-pong, Ressort, Ensemble, Waveshaper, Résonateur) | `native/fx/third_party/sst`, `native/fx/third_party/surge` | GPL-3.0-or-later | the Surge Synth Team: sst-basic-blocks, sst-filters, sst-effects, sst-waveshapers (https://github.com/surge-synthesizer), Surge XT (its chowdsp spring reverb and BBD lines, Jatin Chowdhury) |
| SIMD on ARM, text formatting (for the Surge libraries) | `native/fx/third_party/simde`, `native/fx/third_party/fmt` | MIT | SIMD Everywhere (Evan Nemerson and contributors); {fmt} (Victor Zverovich and contributors) |
| The Eurorack-derived effects (Boucle granulaire, Texture, Résonateur modal, Modulation croisée) | `native/fx/third_party/eurorack` | MIT | Emilie Gillet's eurorack code (Clouds, Rings, Warps) and stmlib, https://github.com/pichenettes/eurorack; run the way VCV's AudibleInstruments does (GPL-3.0-or-later; its approach, not its code). Their original names are not used in the app. |

No firmware is shipped in the app: each one is downloaded from the Hub's mirror or its author's release when the user
chooses it, and stays under its author's licence (shown in the catalogue).

Checks against the originals: `tools/chop_check` (SLOOP's own CHOP code and test claims) and `tools/loom_check`
(LOOM-8's own loom_samples.js, byte for byte).

The Faust compiler's own licence (GPL with an exception) puts no condition on the code it generates; the generated
headers carry the library code above, all compatible with GPL-3.0-only. The effects' names in the app are this
project's own. Every vendored source, its commit and licence: `native/fx/third_party/README.md`. Not used: Oboe (the
app keeps its own audio path), Ableton Link.
