// FM1 Mix pad effect. GPL-3.0-only (this file); the Faust libraries keep their own licences (see their headers).
// Diffuse cloud: re.greyhole (Julian Parker, Till Bovermann; GPL-2.0-or-later in the Faust libraries). X = damping
// (dark at the left), Y = size, feedback, diffusion and modulation together. Played as a send.
import("stdfaust.lib");
size = hslider("size", 1.5, 0.5, 3.0, 0.001) : si.smoo : max(0.5) : min(3.0);
diffusion = hslider("diffusion", 0.5, 0, 0.99, 0.001) : si.smoo : max(0) : min(0.99);
feedback = hslider("feedback", 0.8, 0, 0.95, 0.001) : si.smoo : max(0) : min(0.95);
damp = hslider("damp", 0.3, 0, 0.95, 0.001) : si.smoo : max(0) : min(0.95);
modDepth = hslider("modDepth", 0.1, 0, 1, 0.001) : si.smoo : max(0) : min(1);
process = re.greyhole(0.2, damp, size, diffusion, feedback, modDepth, 2.0);
