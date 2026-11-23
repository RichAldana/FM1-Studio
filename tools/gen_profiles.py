#!/usr/bin/env python3
"""Writes the RemoteFM1 controller profiles (fm1-map format) into app/src/main/assets/profiles.

Sources: RemoteFM1 docs/PROTOCOL.md, section 4 (panel events in a session) and section 8
(standalone "MIDI REMOTE" mode, entered with GLO + FX on the FM-1)."""
import json, os

OUT = os.path.join(os.path.dirname(__file__), "..", "app", "src", "main", "assets", "profiles")
BUTTONS = ["OCT-", "OCT+", "FX", "SCL", "ENV", "LFO", "EDIT", "GLO", "HOME", "SAVE", "ARP", "SEQ", "PLAY", "REC"]
# matrix encoder id -> fm1-map target name
ENC = {0: "SELECT", 1: "ALGORITHM", 2: "KNOB1", 3: "KNOB2", 4: "KNOB3", 5: "KNOB4", 6: "PRESETS"}


def write(name, prof):
    with open(os.path.join(OUT, name), "w") as f:
        json.dump(prof, f, indent=2)
        f.write("\n")


# 1. A RemoteFM1 FM-1 in a session (panel events): keys = notes on channel 16 (F3 = 53),
#    buttons = notes on channel 15 (note = matrix id), encoders = CC 16+e on channel 15 relative
#    around 64, MASTER = 14-bit CC 7 / 39 on channel 15.
b = [{"src": {"ch": 16, "notes": [53, 79]}, "to": "keys", "fold": False}]
for i, n in enumerate(BUTTONS):
    b.append({"src": {"ch": 15, "note": i}, "to": n, "mode": "momentary", "label": n})
for e, n in ENC.items():
    b.append({"src": {"ch": 15, "cc": 16 + e}, "to": n, "mode": "relative-64"})
b.append({"src": {"ch": 15, "cc": 7}, "to": "MASTER", "mode": "absolute"})
b.append({"src": {"ch": 15, "cc": 39}, "to": "ignore"})
write("remotefm1-panel.json", {
    "name": "RemoteFM1 panel (session)", "pass_unmapped": False, "keys_mode": "panel", "bindings": b,
    "notes": "FM-1 running RemoteFM1 in a host session: keys ch16, buttons ch15, encoders CC16-22 relative, MASTER CC7/39."})

# 2. The standalone RemoteFM1 in MIDI REMOTE mode (GLO + FX). Any channel: the settings page lets the
#    user pick them, and the CCs are specific enough.
BTN_CC = {"FX": 20, "SCL": 21, "ENV": 22, "LFO": 23, "EDIT": 24, "GLO": 25, "HOME": 26, "SAVE": 27,
          "ARP": 28, "SEQ": 29, "PLAY": 30, "REC": 31, "OCT-": 85, "OCT+": 86}
ENC_CC = {"SELECT": 102, "ALGORITHM": 103, "PRESETS": 104, "KNOB1": 105, "KNOB2": 106, "KNOB3": 107, "KNOB4": 108}
for suffix, mode, label in [("absolute", "absolute", "knobs ABSOLUTE"),
                            ("rel-1-127", "relative", "knobs REL 1/127"),
                            ("rel-65-63", "relative-64", "knobs REL 65/63")]:
    b = [{"src": {"notes": [0, 127]}, "to": "keys"}]
    for n, cc in BTN_CC.items():
        b.append({"src": {"cc": cc}, "to": n, "mode": "momentary", "label": n})
    for n, cc in ENC_CC.items():
        b.append({"src": {"cc": cc}, "to": n, "mode": mode})
    b.append({"src": {"cc": 109}, "to": "MASTER", "mode": "absolute"})
    write(f"remotefm1-midi-remote-{suffix}.json", {
        "name": f"RemoteFM1 MIDI REMOTE ({label})", "pass_unmapped": False, "keys_mode": "panel", "bindings": b,
        "notes": "FM-1 with RemoteFM1 standalone, GLO + FX = MIDI REMOTE. Set KNOBS on its settings page (hold HOME) to match."})
