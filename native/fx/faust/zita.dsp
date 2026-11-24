// FM1 Mix pad effect. GPL-3.0-only (this file); the Faust libraries keep their own licences (see their headers).
// Hall: re.zita_rev1_stereo. X = damping (damp: the frequency above which the decay halves, dark at the left), Y = the
// decay (t60, mid band). Played as a send: wet only; the pad's slot mixes it.
import("stdfaust.lib");
t60 = hslider("t60", 3, 0.5, 20, 0.01) : si.smoo : max(0.5) : min(20);
damp = hslider("damp", 6000, 800, 16000, 1) : si.smoo : max(800) : min(16000);
process = re.zita_rev1_stereo(60, 200, damp, t60, t60 * 0.8, 48000);
