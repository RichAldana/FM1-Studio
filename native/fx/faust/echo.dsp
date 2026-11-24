// FM1 Mix pad effect. GPL-3.0-only (this file); the Faust libraries keep their own licences (see their headers).
// Echo: a smooth delay (de.sdelay: a new time crossfades between two read heads in 1024 samples, about 23 ms, so the
// pitch does not slide), its feedback through a soft saturation (loopDrive, the top of Y) in the loop. Played as a
// send: wet only; the pad's slot mixes it.
import("stdfaust.lib");
time = hslider("time", 0.25, 0.01, 2.0, 0.0001);
feedback = hslider("feedback", 0.4, 0, 0.95, 0.001) : si.smoo : max(0) : min(0.95);
loopDrive = hslider("loopDrive", 0, 0, 1, 0.001) : si.smoo : max(0) : min(1);
N = 131072;
sat(x) = x * (1 - loopDrive) + loopDrive * ma.tanh(2 * x) * 0.6;
tap = (+ : de.sdelay(N, 1024, time * ma.SR)) ~ (*(feedback) : sat);
process = par(i, 2, tap);
