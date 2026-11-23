# FM1 Mix: licences

FM1 Mix as a whole is distributed under the **GNU General Public License, version 3 only** (`LICENSE`), because it
includes code copied from SLOOP (GPL-3.0-only). Its parts keep their own licences:

| Part | Files | Licence | Source |
| --- | --- | --- | --- |
| The slicer (CHOP) | `Chop.kt`, `SlicerModel.kt`, `SlicerActivity.kt` | GPL-3.0-only | SLOOP's web editor, https://github.com/isod89/sloop-fm1 (Copyright (C) 2026 Leo Kuroshita / Hügelton Instruments, and SLOOP's authors) |
| Samples into LOOM-8 | `LoomSamples.kt`, `LoomLink.kt` | MPL-2.0 | LOOM-8's `app/web/samples/loom_samples.js`, https://github.com/charlesvestal/fm1-loom (Copyright (C) 2026 Charles Vestal) |
| The emulator core | `third_party/fm1-emu` (fetched at build time) | MIT | https://github.com/joakimeriksson/fm1-emu (Copyright (c) Joakim Eriksson) |
| The firmware list shipped | `app/src/main/assets/firmwares-fm1emu.json` (fm1-emu's `web/firmwares.json`) | MIT | https://github.com/joakimeriksson/fm1-emu, built from The Firmware Hub's catalogue (https://thefirmwarehub.com, zvenson) |
| The rest of the app | `native/`, the other Kotlin files | GPL-3.0-only as part of FM1 Mix | this project |

No firmware is shipped in the app: each one is downloaded from the Hub's mirror or its author's release when the user
chooses it, and stays under its author's licence (shown in the catalogue).

Checks against the originals: `tools/chop_check` (SLOOP's own CHOP code and test claims) and `tools/loom_check`
(LOOM-8's own loom_samples.js, byte for byte).
