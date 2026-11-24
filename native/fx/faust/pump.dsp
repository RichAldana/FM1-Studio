// FM1 Mix pad effect. GPL-3.0-only (this file); the Faust libraries keep their own licences (see their headers).
// Pump: co.compressor_stereo. X = release (slow at the left: the groove), Y = the threshold going down and the ratio
// going up together, the gain made up for half of what is taken (so it pumps rather than only gets quieter).
import("stdfaust.lib");
thresh = hslider("thresh", -20, -40, 0, 0.01) : si.smoo : max(-40) : min(0);
ratio = hslider("ratio", 4, 1, 20, 0.01) : si.smoo : max(1) : min(20);
release = hslider("release", 0.12, 0.02, 1, 0.001) : si.smoo : max(0.02) : min(1);
makeup = ba.db2linear(0 - thresh * (1 - 1 / ratio) * 0.5);
process = co.compressor_stereo(ratio, thresh, 0.002, release) : par(i, 2, *(makeup));
