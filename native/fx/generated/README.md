# Faust's generated code

These headers are generated from `native/fx/faust/*.dsp` by the Faust compiler 2.70.3 (GRAME, https://faust.grame.fr):

    faust -lang cpp -cn NameDsp -scn FaustDsp -nvi -i name.dsp -o ../generated/name.h

(`tools/fx_regen.sh` does them all). They carry the code of the Faust library functions each `.dsp` uses, under
those functions' own licences, all compatible with FM1 Mix's GPL-3.0-only:

| Effect | Faust function | Licence (as the library declares it) |
| --- | --- | --- |
| faust.moog | `ve.moog_vcf_2bn` (Julius O. Smith III) | MIT-style STK-4.3 |
| faust.echo | `de.sdelay` (GRAME) | LGPL with exception |
| faust.zita | `re.zita_rev1_stereo` (Julius O. Smith III, after Fons Adriaensen's zita-rev1) | MIT-style STK-4.3 |
| faust.greyhole | `re.greyhole` (Julian Parker, Till Bovermann) | GPL-2.0-or-later |
| faust.phaser | `pf.phaser2_stereo` (Julius O. Smith III) | MIT-style STK-4.3 |
| faust.flanger | `pf.flanger_stereo` (Julius O. Smith III) | MIT-style STK-4.3 |
| faust.drive | `ef.cubicnl`, `fi.lowpass` (Julius O. Smith III) | MIT-style STK-4.3 |
| faust.pitch | `ef.transpose` (GRAME) | LGPL with exception |
| faust.pump | `co.compressor_stereo` (Julius O. Smith III) | MIT-style STK-4.3 |
| all | `si.smoo` and the like (GRAME) | LGPL with exception |

The Faust compiler's exception: the code it generates may be distributed under the licence of one's choice, within
the libraries' own terms above.
