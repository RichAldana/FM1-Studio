import app.fm1mix.Fm1Id
var failures = 0
fun check(ok: Boolean, msg: String) { println((if (ok) "  ok   " else "  FAIL ") + msg); if (!ok) failures++ }
fun main() {
    check(Fm1Id.looksLikeFm1(0x1209, 0x0001, "Chordism FM-1", true), "Chordism (1209:0001, named FM-1, MIDI)")
    check(Fm1Id.looksLikeFm1(0x1234, 0x5678, "Felucca", false) && Fm1Id.looksLikeFm1(0x1, 0x2, "LOOM-8 FM-1", false), "by name: Felucca, LOOM-8 FM-1")
    check(Fm1Id.looksLikeFm1(0x1, 0x2, "Mystery synth", true), "an unknown name with MIDI")
    check(!Fm1Id.looksLikeFm1(0x05ac, 0x110a, "USB-C to 3.5mm Headphone Jack Adapter", false), "Apple's headphone adapter: not an FM-1")
    check(!Fm1Id.looksLikeFm1(0x18d1, 0x5033, "Pixel USB-C earbuds", false), "Pixel earbuds: not an FM-1")
    check(Fm1Id.bare("USB-Audio - Chordism FM-1") == "chordism fm-1", "Android's prefix removed")
    check(Fm1Id.isOneOf("USB-Audio - Chordism FM-1", listOf("Chordism FM-1")), "the FM-1's audio device matched to its USB device")
    check(Fm1Id.isOneOf("USB-Audio - LOOM-8 FM-1", listOf("LOOM-8 FM-1", "USB-C to 3.5mm Headphone Jack Adapter")), "matched among two USB devices")
    check(!Fm1Id.isOneOf("USB-Audio - USB-C to 3.5mm Headphone Jack Adapter", listOf("LOOM-8 FM-1")), "the adapter's audio is not the FM-1's")
    check(!Fm1Id.isOneOf("USB-Audio - ", listOf("LOOM-8 FM-1")) && !Fm1Id.isOneOf("x", emptyList()), "nothing to match")
    println(if (failures == 0) "ALL OK" else "$failures FAILURES")
}
