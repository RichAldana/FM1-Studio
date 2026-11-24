// FM1 Mix pad effect. GPL-3.0-only (this file); the Faust libraries keep their own licences (see their headers).
// Overdrive: ef.cubicnl then a lowpass. X = tone (the lowpass, dark at the left), Y = drive, the output level brought
// down as the drive goes up (1 / the square root of cubicnl's pregain): more drive, not only louder.
import("stdfaust.lib");
drive = hslider("drive", 0.3, 0, 1, 0.001) : si.smoo : max(0) : min(1);
tone = hslider("tone", 6000, 500, 16000, 1) : si.smoo : max(500) : min(16000);
comp = (1 + 0.5 * drive) / sqrt(pow(10, 2 * drive));
ch = ef.cubicnl(drive, 0) : fi.lowpass(2, tone) : *(comp);
process = par(i, 2, ch);
