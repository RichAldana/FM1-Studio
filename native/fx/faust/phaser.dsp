// FM1 Mix pad effect. GPL-3.0-only (this file); the Faust libraries keep their own licences (see their headers).
// Phaser: pf.phaser2_stereo, 4 notches. X = speed, Y = depth and feedback.
import("stdfaust.lib");
speed = hslider("speed", 0.5, 0.05, 8, 0.001) : si.smoo : max(0.05) : min(8);
depth = hslider("depth", 1, 0, 1, 0.001) : si.smoo : max(0) : min(1);
feedback = hslider("feedback", 0.3, 0, 0.9, 0.001) : si.smoo : max(0) : min(0.9);
process = pf.phaser2_stereo(4, 1000, 100, 1.5, 800, speed, depth, feedback, 0);
