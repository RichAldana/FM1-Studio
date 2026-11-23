// FM1 Mix pad effect. GPL-3.0-only (this file); the Faust libraries keep their own licences (see their headers).
// Moog filter: ve.moog_vcf_2bn (bilinear: good up to the top of the range). Its parameters change once a 64-frame
// block, already smoothed by the pad's XY layer (fx.rs): no per-sample smoothing here, so Faust computes the filter's
// coefficients once a block instead of every sample (9 % of real time per sample on a desktop core, under 1 % so).
import("stdfaust.lib");
cutoff = hslider("cutoff", 18000, 40, 18000, 1);
res = hslider("res", 0, 0, 0.9, 0.001);
process = par(i, 2, ve.moog_vcf_2bn(res, cutoff));
