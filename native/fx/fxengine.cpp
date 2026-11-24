// SPDX-License-Identifier: GPL-3.0-only
// FM1 Mix's pad effects (the brief "moteur d'effets XY"): every effect behind one C interface (fxengine.h), so the
// engine (native/src/fx.rs: the XY layer, the slots, the clock) never knows which DSP library is underneath.
//   - Faust effects: native/fx/faust/*.dsp compiled to C++ (native/fx/generated, `faust -lang cpp -nvi -i`), their
//     sliders found by name through a UI that keeps their addresses (as Faust's MapUI does);
//   - effects written here: the tape stop and the beat repeat (a ring buffer and a read head; they need the clock);
//   - the other engines' wrappers, each in its own file (effect.h: make_airwin, make_surge, make_mi).
// Audio thread rules: process, set_param and on_touch allocate nothing and take no lock; everything is allocated in
// fx_new / fx_prepare.
#include <algorithm>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <math.h>
#include <string>
#include <vector>
#include "fxengine.h"
#include "effect.h"
#if defined(__x86_64__) || defined(__i386__)
#include <xmmintrin.h>
#include <pmmintrin.h>
#endif

#ifndef FAUSTFLOAT
#define FAUSTFLOAT float
#endif

// ---------------------------------------------------------------- what Faust's generated code expects

struct Meta {
    virtual ~Meta() {}
    virtual void declare(const char*, const char*) {}
};
struct Soundfile;
struct UI {
    virtual ~UI() {}
    virtual void openTabBox(const char*) {}
    virtual void openHorizontalBox(const char*) {}
    virtual void openVerticalBox(const char*) {}
    virtual void closeBox() {}
    virtual void addButton(const char*, FAUSTFLOAT*) {}
    virtual void addCheckButton(const char*, FAUSTFLOAT*) {}
    virtual void addVerticalSlider(const char*, FAUSTFLOAT*, FAUSTFLOAT, FAUSTFLOAT, FAUSTFLOAT, FAUSTFLOAT) {}
    virtual void addHorizontalSlider(const char*, FAUSTFLOAT*, FAUSTFLOAT, FAUSTFLOAT, FAUSTFLOAT, FAUSTFLOAT) {}
    virtual void addNumEntry(const char*, FAUSTFLOAT*, FAUSTFLOAT, FAUSTFLOAT, FAUSTFLOAT, FAUSTFLOAT) {}
    virtual void addHorizontalBargraph(const char*, FAUSTFLOAT*, FAUSTFLOAT, FAUSTFLOAT) {}
    virtual void addVerticalBargraph(const char*, FAUSTFLOAT*, FAUSTFLOAT, FAUSTFLOAT) {}
    virtual void addSoundfile(const char*, const char*, Soundfile**) {}
    virtual void declare(FAUSTFLOAT*, const char*, const char*) {}
};
struct FaustDsp {};

// each generated class in a namespace of its own (their helpers and tables are file statics)
namespace fx_moog {
#include "generated/moog.h"
}
namespace fx_echo {
#include "generated/echo.h"
}
namespace fx_zita {
#include "generated/zita.h"
}
namespace fx_greyhole {
#include "generated/greyhole.h"
}
namespace fx_phaser {
#include "generated/phaser.h"
}
namespace fx_flanger {
#include "generated/flanger.h"
}
namespace fx_drive {
#include "generated/drive.h"
}
namespace fx_pitch {
#include "generated/pitch.h"
}
namespace fx_pump {
#include "generated/pump.h"
}

// ---------------------------------------------------------------- Faust

struct ParamUI : UI {
    std::vector<std::string> names;
    std::vector<FAUSTFLOAT*> zones;
    std::vector<float> lo, hi;
    void add(const char* label, FAUSTFLOAT* z, FAUSTFLOAT mn, FAUSTFLOAT mx) {
        names.push_back(label);
        zones.push_back(z);
        lo.push_back(mn);
        hi.push_back(mx);
    }
    void addHorizontalSlider(const char* l, FAUSTFLOAT* z, FAUSTFLOAT, FAUSTFLOAT mn, FAUSTFLOAT mx, FAUSTFLOAT) override { add(l, z, mn, mx); }
    void addVerticalSlider(const char* l, FAUSTFLOAT* z, FAUSTFLOAT, FAUSTFLOAT mn, FAUSTFLOAT mx, FAUSTFLOAT) override { add(l, z, mn, mx); }
    void addNumEntry(const char* l, FAUSTFLOAT* z, FAUSTFLOAT, FAUSTFLOAT mn, FAUSTFLOAT mx, FAUSTFLOAT) override { add(l, z, mn, mx); }
};

template <class D>
class FaustEffect : public IEffect {
    D* dsp = nullptr;      // (on the heap: some hold large delay lines)
    ParamUI ui;
    int sr = 44100;
public:
    FaustEffect() { dsp = new D(); }
    ~FaustEffect() override { delete dsp; }
    void prepare(double sampleRate, int) override {
        sr = (int)sampleRate;
        dsp->init(sr);
        ui = ParamUI();
        dsp->buildUserInterface(&ui);
    }
    void reset() override { dsp->instanceClear(); }
    void process(const float* inL, const float* inR, float* outL, float* outR, int n, const TransportInfo&) override {
        FAUSTFLOAT* in[2] = {const_cast<float*>(inL), const_cast<float*>(inR)};
        FAUSTFLOAT* out[2] = {outL, outR};
        dsp->compute(n, in, out);
    }
    void setParam(int i, float v) override {
        if (i >= 0 && i < (int)ui.zones.size()) *ui.zones[i] = std::min(ui.hi[i], std::max(ui.lo[i], v));
    }
    void onTouch(bool) override {}
    int numParams() const override { return (int)ui.zones.size(); }
    const char* paramName(int i) const override { return i >= 0 && i < (int)ui.names.size() ? ui.names[i].c_str() : ""; }
};

// ---------------------------------------------------------------- tape stop (written here)

// The finger down: the tape slows down in `fall` seconds to `depth` of its speed (0: a full stop; the pitch going down
// with it); lifted: it spins up again in 0.15 s and, once at speed, joins the live sound again by a 30 ms crossfade.
// The wet level is the slot's.
class TapeStop : public IEffect {
    std::vector<float> buf;   // interleaved stereo
    size_t len = 0, w = 0;
    double r = 0;             // the read head (frames, may lag the write head)
    double speed = 1;
    float fall = 0.6f;
    float depth = 0.f;        // the speed it falls to
    bool down = false;
    int join = 0;             // frames of the crossfade back to live left
    double sr = 44100;
    bool active = false;
public:
    void prepare(double sampleRate, int) override {
        sr = sampleRate;
        len = (size_t)(sampleRate * 4);
        buf.assign(len * 2, 0.f);
        reset();
    }
    void reset() override {
        std::fill(buf.begin(), buf.end(), 0.f);
        w = 0; r = 0; speed = 1; down = false; join = 0; active = false;
    }
    void process(const float* inL, const float* inR, float* outL, float* outR, int n, const TransportInfo&) override {
        const double fallStep = 1.0 / std::max(0.02, (double)fall * sr);
        const double upStep = 1.0 / (0.15 * sr);
        const int joinLen = (int)(0.03 * sr);
        for (int i = 0; i < n; i++) {
            buf[2 * w] = inL[i];
            buf[2 * w + 1] = inR[i];
            if (!active) {
                r = (double)w;
                outL[i] = inL[i];
                outR[i] = inR[i];
            } else {
                if (down) speed = std::max((double)depth, speed - fallStep);
                else speed = std::min(1.0, speed + upStep);
                // read with linear interpolation
                double rp = r;
                size_t i0 = (size_t)rp % len, i1 = (i0 + 1) % len;
                float f = (float)(rp - std::floor(rp));
                float l = buf[2 * i0] * (1 - f) + buf[2 * i1] * f;
                float rr = buf[2 * i0 + 1] * (1 - f) + buf[2 * i1 + 1] * f;
                r += speed;
                if (r >= (double)len) r -= (double)len;
                if (!down && speed >= 1.0) {
                    if (join == 0) join = joinLen;
                    float g = (float)join / (float)joinLen;     // 1 .. 0: from the tape to live
                    l = l * g + inL[i] * (1 - g);
                    rr = rr * g + inR[i] * (1 - g);
                    if (--join == 0) active = false;
                }
                outL[i] = l;
                outR[i] = rr;
            }
            w = (w + 1) % len;
        }
    }
    void setParam(int i, float v) override {
        if (i == 0) fall = std::min(3.0f, std::max(0.03f, v));
        if (i == 1) depth = std::min(0.95f, std::max(0.f, v));
    }
    void onTouch(bool d) override {
        down = d;
        if (d) { active = true; join = 0; }
    }
    int numParams() const override { return 2; }
    const char* paramName(int i) const override { return i == 0 ? "fall" : i == 1 ? "depth" : ""; }
};

// ---------------------------------------------------------------- beat repeat (written here)

// The finger down arms it; at the next line of the grid (`length` beats: 1/4 .. 1/64 of a whole note, so 1 .. 1/16
// beat) it repeats the slice that just went by, again and again until the finger lifts: `repeats` times at full level,
// then each time `decay` quieter; `filter` (0..1) closes a lowpass a little more at each repeat. Loop edges have 2 ms
// fades. Its clock: the transport's beat position (the MIDI clock or the internal tempo).
class BeatRepeat : public IEffect {
    std::vector<float> buf;
    size_t len = 0, w = 0;
    double sr = 44100;
    float lengthBeats = 0.25f;
    float decay = 0.5f;
    float repeats = 4.f;      // at full level, before the decay
    float filter = 0.f;
    int rep = 0;              // repeats played
    float lpL = 0, lpR = 0;
    bool armed = false, repeating = false, release = false;
    size_t start = 0, sliceLen = 1, pos = 0;
    float gain = 1, relGain = 1;
    double lastBeat = -1;
public:
    void prepare(double sampleRate, int) override {
        sr = sampleRate;
        len = (size_t)(sampleRate * 8);
        buf.assign(len * 2, 0.f);
        reset();
    }
    void reset() override {
        std::fill(buf.begin(), buf.end(), 0.f);
        w = 0; armed = repeating = release = false; gain = 1; relGain = 1; lastBeat = -1;
    }
    void process(const float* inL, const float* inR, float* outL, float* outR, int n, const TransportInfo& t) override {
        const double perSample = t.bpm / 60.0 / sr;
        const size_t fade = (size_t)(0.002 * sr);
        for (int i = 0; i < n; i++) {
            buf[2 * w] = inL[i];
            buf[2 * w + 1] = inR[i];
            const double beat = t.beatPosition + i * perSample;
            // the grid: a line each `lengthBeats`
            const double q = std::max(1.0 / 64.0, (double)lengthBeats);
            const bool line = lastBeat >= 0 && std::floor(beat / q) != std::floor(lastBeat / q);
            lastBeat = beat;
            if (armed && line) {
                armed = false;
                repeating = true;
                sliceLen = std::max<size_t>(64, std::min(len / 2, (size_t)(q * 60.0 / std::max(20.0, t.bpm) * sr)));
                start = (w + len - sliceLen) % len;
                pos = 0;
                gain = 1;
                rep = 0;
                lpL = lpR = 0;
            }
            float l = inL[i], r = inR[i];
            if (repeating) {
                size_t k = (start + pos) % len;
                float e = 1;
                if (pos < fade) e = (float)pos / fade;
                else if (sliceLen - pos < fade) e = (float)(sliceLen - pos) / fade;
                float g = gain * e * relGain;
                float rl = buf[2 * k] * g, rr = buf[2 * k + 1] * g;
                if (filter > 0) {
                    // a one-pole lowpass, closing with the repeats (none on the first pass)
                    const float a = filter * 0.97f * (1.f - std::pow(0.7f, (float)rep));
                    lpL += (1 - a) * (rl - lpL);
                    lpR += (1 - a) * (rr - lpR);
                    rl = lpL; rr = lpR;
                }
                if (release) {
                    // back to live in 10 ms
                    relGain = std::max(0.f, relGain - (float)(1.0 / (0.01 * sr)));
                    l = rl + l * (1 - relGain);
                    r = rr + r * (1 - relGain);
                    if (relGain <= 0) { repeating = false; release = false; relGain = 1; }
                } else { l = rl; r = rr; }
                if (++pos >= sliceLen) {
                    pos = 0;
                    if (++rep >= (int)repeats) gain *= (1.f - 0.6f * decay);
                }
            }
            outL[i] = l;
            outR[i] = r;
            w = (w + 1) % len;
        }
    }
    void setParam(int i, float v) override {
        if (i == 0) lengthBeats = std::min(1.0f, std::max(1.0f / 16.0f, v));
        if (i == 1) decay = std::min(1.0f, std::max(0.f, v));
        if (i == 2) repeats = std::min(64.f, std::max(1.f, v));
        if (i == 3) filter = std::min(1.0f, std::max(0.f, v));
    }
    void onTouch(bool d) override {
        if (d) { armed = true; release = false; relGain = 1; }
        else { armed = false; if (repeating) release = true; }
    }
    int numParams() const override { return 4; }
    const char* paramName(int i) const override {
        return i == 0 ? "length" : i == 1 ? "decay" : i == 2 ? "repeats" : i == 3 ? "filter" : "";
    }
};

// ---------------------------------------------------------------- the C interface

struct FxEffect { IEffect* e; };

extern "C" {

FxEffect* fx_new(const char* id) {
    std::string s(id ? id : "");
    IEffect* e = nullptr;
    if (s == "faust.moog") e = new FaustEffect<fx_moog::MoogDsp>();
    else if (s == "faust.echo") e = new FaustEffect<fx_echo::EchoDsp>();
    else if (s == "faust.zita") e = new FaustEffect<fx_zita::ZitaDsp>();
    else if (s == "faust.greyhole") e = new FaustEffect<fx_greyhole::GreyholeDsp>();
    else if (s == "faust.phaser") e = new FaustEffect<fx_phaser::PhaserDsp>();
    else if (s == "faust.flanger") e = new FaustEffect<fx_flanger::FlangerDsp>();
    else if (s == "faust.drive") e = new FaustEffect<fx_drive::DriveDsp>();
    else if (s == "faust.pitch") e = new FaustEffect<fx_pitch::PitchDsp>();
    else if (s == "faust.pump") e = new FaustEffect<fx_pump::PumpDsp>();
    else if (s == "cpp.tapestop") e = new TapeStop();
    else if (s == "cpp.beatrepeat") e = new BeatRepeat();
    if (!e) e = make_airwin(s);
    if (!e) e = make_surge(s);
    if (!e) e = make_mi(s);
    return e ? new FxEffect{e} : nullptr;
}

void fx_free(FxEffect* f) { if (f) { delete f->e; delete f; } }
void fx_prepare(FxEffect* f, double sr, int block) { f->e->prepare(sr, block); }
void fx_reset(FxEffect* f) { f->e->reset(); }
int fx_num_params(FxEffect* f) { return f->e->numParams(); }
const char* fx_param_name(FxEffect* f, int i) { return f->e->paramName(i); }
void fx_set_param(FxEffect* f, int i, float v) { f->e->setParam(i, v); }
void fx_on_touch(FxEffect* f, int down) { f->e->onTouch(down != 0); }
// Denormals flushed to zero while an effect runs (a reverb's tail fading out would otherwise slow the processor
// down many times over): FPCR's FZ bit on arm64, MXCSR's FTZ and DAZ on x86 (the tests), put back after.
void fx_process(FxEffect* f, const float* inL, const float* inR, float* outL, float* outR, int n,
                double bpm, double beat, int running) {
    TransportInfo t{bpm, beat, running != 0};
#if defined(__aarch64__)
    uint64_t fpcr;
    __asm__ volatile("mrs %0, fpcr" : "=r"(fpcr));
    __asm__ volatile("msr fpcr, %0" : : "r"(fpcr | (1ull << 24)));
    f->e->process(inL, inR, outL, outR, n, t);
    __asm__ volatile("msr fpcr, %0" : : "r"(fpcr));
#elif defined(__x86_64__) || defined(__i386__)
    unsigned int csr = _mm_getcsr();
    _mm_setcsr(csr | 0x8040);
    f->e->process(inL, inR, outL, outR, n, t);
    _mm_setcsr(csr);
#else
    f->e->process(inL, inR, outL, outR, n, t);
#endif
}

}
