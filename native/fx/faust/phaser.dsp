// FM1 Mix pad effect. GPL-3.0-only (this file); the Faust libraries keep their own licences (see their headers).
// Phaser: pf.phaser2_stereo, its speed and depth on the pad.
import("stdfaust.lib");
// (si.smoo starts from 0: each smoothed value kept in its range, or a T60 or a ratio of 0 divides by zero)
speed = hslider("speed", 0.5, 0.05, 8, 0.001) : si.smoo : max(0.05) : min(8);
depth = hslider("depth", 1, 0, 1, 0.001) : si.smoo : max(0) : min(1);
process = pf.phaser2_stereo(4, 1000, 100, 1.5, 800, speed, depth, 0.5, 0);
