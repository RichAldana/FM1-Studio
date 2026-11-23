// FM1 Mix pad effect. GPL-3.0-only (this file); the Faust libraries keep their own licences (see their headers).
// Hall: re.zita_rev1_stereo, wet only; its decay (T60) on the pad.
import("stdfaust.lib");
// (si.smoo starts from 0: each smoothed value kept in its range, or a T60 or a ratio of 0 divides by zero)
t60 = hslider("t60", 3, 0.5, 20, 0.01) : si.smoo : max(0.5) : min(20);
process = re.zita_rev1_stereo(60, 200, 6000, t60, t60 * 0.8, 48000);
