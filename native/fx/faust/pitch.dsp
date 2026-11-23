// FM1 Mix pad effect. GPL-3.0-only (this file); the Faust libraries keep their own licences (see their headers).
// Pitch: ef.transpose, -12 to +12 semitones (whole ones: the pad quantises).
import("stdfaust.lib");
shift = hslider("shift", 0, -12, 12, 0.01);
process = par(i, 2, ef.transpose(2048, 256, shift));
