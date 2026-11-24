The pad effects' third-party code
Vendored (copied as they are, their copyright headers and licence files kept), only the files the effects use. FM1 Mix
as a whole is GPL-3.0-only; each part here keeps its own licence, all compatible with it.
Folder	What, for which effects	Licence	From
`airwin2rack/`	Airwindows' Isolator3, kCosmos, CrunchCoat, DeRez2, BeziComp (Chris Johnson) in airwin2rack's consolidation (Paul Walker): Isolateur, Cosmos, Reverb rétro, Lo-fi, Compression	MIT (`LICENSE.md`, `LICENSE-airwindows.txt`)	https://github.com/baconpaul/airwin2rack, 91b3343 (2026-11-22)
`sst/sst-basic-blocks`, `sst/sst-filters`, `sst/sst-effects`, `sst/sst-waveshapers`	the Surge Synth Team's libraries (headers): Filtre analogique, Delay ping-pong, Waveshaper, Résonateur, and what the spring and the ensemble need	GPL-3.0-or-later (`LICENSE.md` in each)	https://github.com/surge-synthesizer: sst-basic-blocks 143d75b, sst-filters 36cd4d2, sst-effects 987b39d, sst-waveshapers dd12f31
`surge/`	from Surge XT: the chowdsp spring reverb (Ressort) and BBD delay lines (Ensemble), with the few shared headers they include (`globals.h`, `vembertech/`, `SSEComplex.h`); `shim/UnitConversions.h` is FM1 Mix's (one function, instead of Surge's, which needs fmt)	GPL-3.0-or-later (`LICENSE`)	https://github.com/surge-synthesizer/surge, 90a36ad (2026-11-23)
`simde/`	SIMD Everywhere: the SSE intrinsics the sst libraries use, on ARM (NEON)	MIT (`COPYING`)	https://github.com/simd-everywhere/simde, fdf9843 (2026-11-19), the x86 headers only
`fmt/`	{fmt}, header-only: the sst libraries' parameter descriptions include it	MIT (`LICENSE`)	https://github.com/fmtlib/fmt, 3be6709 (2026-11-23)
`eurorack/`	Emilie Gillet's Clouds, Rings and Warps DSP code with stmlib: Boucle granulaire, Texture, Résonateur modal, Modulation croisée. Their original names are never shown in the app (the repository's guidelines for derivative works); the credit is on the licences screen	MIT (each file's header; `stmlib/LICENSE`; `README.md`: "Code (STM32F projects): MIT license")	https://github.com/pichenettes/eurorack f4cfa76 (2026-10-12), https://github.com/pichenettes/stmlib d18def8
How they run on a phone (rates, block sizes) follows VCV's AudibleInstruments (GPL-3.0-or-later,
https://github.com/VCVRack/AudibleInstruments): its approach, not its code (`../mi.cpp`, `../resample.h`).
The Faust effects' generated code is described in `../generated/README.md`.
