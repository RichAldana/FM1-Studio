// FM1 Mix pad effect. GPL-3.0-only (this file); the Faust libraries keep their own licences (see their headers).
// Flanger: pf.flanger_stereo, its delays swept by a sine (the right a quarter turn after the left). X = speed,
// Y = depth and feedback.
import("stdfaust.lib");
speed = hslider("speed", 0.3, 0.05, 8, 0.001) : si.smoo : max(0.05) : min(8);
feedback = hslider("feedback", 0.5, 0, 0.9, 0.001) : si.smoo : max(0) : min(0.9);
depth = hslider("depth", 1, 0, 1, 0.001) : si.smoo : max(0) : min(1);
dmax = 512;
lfo(ph) = 0.5 + 0.5 * os.oscp(speed, ph);
process = pf.flanger_stereo(dmax, 8 + (dmax - 16) * lfo(0) * 0.5, 8 + (dmax - 16) * lfo(ma.PI / 2) * 0.5, depth, feedback, 0);
