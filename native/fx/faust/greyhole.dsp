// FM1 Mix pad effect. GPL-3.0-only (this file); the Faust libraries keep their own licences (see their headers).
// Diffuse cloud: re.greyhole, wet only; size and diffusion on the pad, the rest fixed.
import("stdfaust.lib");
// (si.smoo starts from 0: each smoothed value kept in its range, or a T60 or a ratio of 0 divides by zero)
size = hslider("size", 1.5, 0.5, 3.0, 0.001) : si.smoo : max(0.5) : min(3.0);
diffusion = hslider("diffusion", 0.5, 0, 0.99, 0.001) : si.smoo : max(0) : min(0.99);
process = re.greyhole(0.2, 0.4, size, diffusion, 0.9, 0.1, 2.0);
