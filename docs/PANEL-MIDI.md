# PANEL MIDI, protocol 1

The FM-1's panel lent to another FM-1 (an emulated one: FM1 Mix on a phone, RemoteFM1, fm1-emu): while it lasts the
firmware is deaf to its own keys, buttons, encoders and MASTER, sends them as MIDI, and shows the other FM-1's lights.
Any firmware can do it; this page is what it and the app say to each other. The reference implementation is
`panel_link.c` (MPL-2.0, a firmware adds it with a few hooks: see its top).

Copyright (C) 2026 RichAldana. This text: CC BY 4.0.

## Finding each other

The app asks every USB MIDI port, whatever its name:

    host:   F0 7D 50 4D 01 F7                                   HELLO
    device: F0 7D 50 4D 01 <proto> <caps> <btn ch> <key ch> <nled> <name> 00 <version> 00 <masked> 00 F7

- `7D` is the non-commercial SysEx ID; `50 4D` ("PM") is this protocol's, so it never meets a firmware's own commands
  (Felucca's forks share `7D 46 4C`, each with commands of its own).
- `proto` 1. `caps`, bits: 0 the lights (LEDS), 1 MASTER in 14 bits, 2 the USB audio return (the phone's sound mixed
  into the FM-1's headphones and speaker), 3 the state pushed (the device says when it switches itself).
- `btn ch`, `key ch`: the channels the panel's events use, 0-based (14 and 15: channels 15 and 16).
- `nled`: the lights a LEDS frame carries (41: 14 buttons, 27 keys, by matrix id).
- `name`, `version`: the firmware's (ASCII). `masked`: what the firmware leaves aside while its panel is lent, for the
  app to say (ASCII, comma-separated, may be empty: "SYNC OUT,CLICK,USB LEVEL,MIDI CH 15-16").
- A device that does not know the protocol does not answer; the app then leaves it alone.

## Lending the panel

    host:   F0 7D 50 4D 02 <v> F7          v: 1 lend, 0 take back, 2 ask
    device: F0 7D 50 4D 02 <state> F7      state: 1 lent, 0 its own

The device answers every PANEL with its state, and (caps bit 3) sends the same frame by itself when the user switches
on the FM-1 (its menu, or GLO + FX held a second). The device never saves the state: it always starts with its panel
its own.

## While it is lent

The panel's events, in RemoteFM1's panel map (fm1-map's `remotefm1-panel` profile reads it as it is):

| What | Message |
|---|---|
| key k (0 = F3 .. 26 = G5) | note 53 + k on `key ch`, velocity 100; note off on release |
| button (matrix id: OCT- 0, OCT+ 1, FX 2, SEL 3, ENV 4, LFO 5, EDIT 6, GLO 7, HOME 8, SAVE 9, ARP 10, SEQ 11, PLAY 12, REC 13) | note = the id on `btn ch`, velocity 127; note off on release |
| encoder (SELECT 0, ALGORITHM 1, KNOB 1-4 2..5, PRESETS 6) | CC 16 + the id on `btn ch`, 64 + the detents (1..127) |
| MASTER | CC 7 (its top 7 bits) then CC 39 (the low 7) on `btn ch`, when it moved 8/4096 or more |

What was held when the panel went away is not sent (its release neither). The device sends nothing else of its own on
`btn ch` and `key ch` while it lasts.

The other FM-1's lights:

    host:   F0 7D 50 4D 03 <nled levels> F7        each 0..127, by matrix id (buttons 0..13, keys 14..40)

No answer. The device shows 64 and up lit, 24 and up its middle level, 6 and up dim, below dark. The host sends them
when a level changes and at least every second; with none for 1.5 s the device shows the way back alone: GLO and FX lit.

MASTER while it is lent is the other FM-1's: the device keeps its own level where MASTER was, and takes the pot back
once it comes to that level again (no jump).

## The way back

GLO + FX held a second (the device's own panel, lent or not), the device's menu, or PANEL 0 from the host. The app
asks PANEL 0 when it goes away.

## Older firmware

LOOM-8 PANEL MIDI 0.18.2-pm1 speaks an earlier form of this, inside LOOM-8's own commands: `F0 7D 46 4C 30 v F7`
(PANEL, answered with the state) and `F0 7D 46 4C 31 <41 levels> F7` (LEDS), found by its name and LOOM-8's SB_PING.
FM1 Mix keeps that for it.
