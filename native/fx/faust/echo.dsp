// FM1 Mix pad effect. GPL-3.0-only (this file); the Faust libraries keep their own licences (see their headers).
// Echo, wet only (the pad's slot adds it to the dry): a delay fed back; its time moves by a crossfade between two
// read heads (de.sdelay, 1024 samples), never a glide (the pitch would bend).
import("stdfaust.lib");
// (si.smoo starts from 0: each smoothed value kept in its range, or a T60 or a ratio of 0 divides by zero)
time = hslider("time", 0.25, 0.01, 2.0, 0.0001);
feedback = hslider("feedback", 0.4, 0, 0.95, 0.001) : si.smoo : max(0) : min(0.95);
N = 131072;
tap = (+ : de.sdelay(N, 1024, time * ma.SR)) ~ *(feedback);
process = par(i, 2, tap);
