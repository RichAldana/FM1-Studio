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
| The pad's other effects and engine | `native/fx/fxengine.cpp` (tape stop, beat repeat), `native/src/fx.rs` | GPL-3.0-only as part of FM1 Mix | this project |

No firmware is shipped in the app: each one is downloaded from the Hub's mirror or its author's release when the user
chooses it, and stays under its author's licence (shown in the catalogue).

Checks against the originals: `tools/chop_check` (SLOOP's own CHOP code and test claims) and `tools/loom_check`
(LOOM-8's own loom_samples.js, byte for byte).

The Faust compiler's own licence (GPL with an exception) puts no condition on the code it generates; the generated
headers carry the library code above, all compatible with GPL-3.0-only. The effects' names in the app are this
project's own. No Mutable Instruments, Airwindows or Surge code is in this version yet.
