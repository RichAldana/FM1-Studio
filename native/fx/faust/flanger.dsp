// FM1 Mix pad effect. GPL-3.0-only (this file); the Faust libraries keep their own licences (see their headers).
// Flanger: pf.flanger_stereo, a sine sweeping the two delays (a quarter turn apart); speed and feedback on the pad.
import("stdfaust.lib");
// (si.smoo starts from 0: each smoothed value kept in its range, or a T60 or a ratio of 0 divides by zero)
speed = hslider("speed", 0.3, 0.05, 5, 0.001) : si.smoo : max(0.05) : min(5);
feedback = hslider("feedback", 0.5, 0, 0.95, 0.001) : si.smoo : max(0) : min(0.95);
dmax = 512;
lfo(ph) = 0.5 + 0.5 * os.oscp(speed, ph);
process = pf.flanger_stereo(dmax, 8 + (dmax - 16) * lfo(0) * 0.5, 8 + (dmax - 16) * lfo(ma.PI / 2) * 0.5, 1, feedback, 0);
