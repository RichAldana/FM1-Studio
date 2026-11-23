// FM1 Mix pad effect. GPL-3.0-only (this file); the Faust libraries keep their own licences (see their headers).
// Pump: co.compressor_stereo, fast attack, a release that breathes; threshold and ratio on the pad, the level made up.
import("stdfaust.lib");
// (si.smoo starts from 0: each smoothed value kept in its range, or a T60 or a ratio of 0 divides by zero)
thresh = hslider("thresh", -20, -40, 0, 0.01) : si.smoo : max(-40) : min(0);
ratio = hslider("ratio", 4, 1, 20, 0.01) : si.smoo : max(1) : min(20);
makeup = ba.db2linear(0 - thresh * (1 - 1 / ratio) * 0.5);
process = co.compressor_stereo(ratio, thresh, 0.002, 0.12) : par(i, 2, *(makeup));
