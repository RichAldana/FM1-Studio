// FM1 Mix pad effect. GPL-3.0-only (this file); the Faust libraries keep their own licences (see their headers).
// Overdrive: ef.cubicnl, then a low-pass for its tone; drive and tone on the pad (its level kept about the same).
import("stdfaust.lib");
// (si.smoo starts from 0: each smoothed value kept in its range, or a T60 or a ratio of 0 divides by zero)
drive = hslider("drive", 0.3, 0, 1, 0.001) : si.smoo : max(0) : min(1);
tone = hslider("tone", 6000, 500, 12000, 1) : si.smoo : max(500) : min(12000);
ch = ef.cubicnl(drive, 0) : fi.lowpass(2, tone) : *(1.4);
process = par(i, 2, ch);
