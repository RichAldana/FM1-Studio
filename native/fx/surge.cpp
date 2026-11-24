// SPDX-License-Identifier: GPL-3.0-only
// The pad's Surge effects, from the Surge Synth Team's libraries (GPL-3.0-or-later; vendored in third_party/sst, with
// simde for SSE on ARM) and from Surge XT's own effects code (third_party/surge: the chowdsp spring reverb and BBD
// delay lines, by Jatin Chowdhury for Surge):
//   - surge.filter: sst-filters' Cytomic SVF lowpass, a compensated soft drive before it;
//   - surge.delay: sst-effects' Delay (Surge's), in its "clean" line mode, which crossfades between two read heads when
//     the time changes (no pitch glide), set up as a ping-pong (the input from the left, the echoes crossing over);
//   - surge.spring: Surge XT's spring reverb processor (chowdsp SpringReverbProc);
//   - surge.ensemble: Surge XT's BBD ensemble, its BBD path redone here around the same delay lines and saturation
//     (two sines, 120 degrees apart, sweeping two BBD lines per side), without Surge's storage and parameter classes;
//   - surge.shaper: sst-waveshapers' shapes, a DC blocker after, the level compensated;
//   - surge.resonator: three sst-filters band-passes (the note, its octave, the twelfth), after Surge's Resonator.
// Effects at Surge's block size run in chunks of it; everything is allocated in prepare().
#include <algorithm>
#include <array>
#include <cmath>
#include <cstring>
#include <string>
#include "effect.h"

#include "sst/basic-blocks/simd/setup.h"
#include "sst/effects/ConcreteConfig.h"
#include "sst/effects/Delay.h"
#include "sst/filters/CytomicSVF.h"
#include "sst/waveshapers.h"
#include "chowdsp/spring_reverb/SpringReverbProc.h"
#include "chowdsp/bbd_utils/BBDDelayLine.h"
#include "chowdsp/bbd_utils/BBDNonlin.h"

namespace {

inline float clampf(float v, float a, float b) { return std::fmin(b, std::fmax(a, v)); }

/// A one-pole smoother (per sample), for gains and frequencies changed once a block.
struct Smooth {
    float v = 0, a = 0.999f;
    void init(double sr, float ms, float start) { a = (float)std::exp(-1000.0 / (ms * sr)); v = start; }
    inline float next(float target) { v = target + (v - target) * a; return v; }
};

// ---------------------------------------------------------------- filter

class FilterFx : public IEffect {
    sst::filters::CytomicSVF svf;
    float sr = 44100, cutoff = 16000, res = 0.1f, drive = 0;
    Smooth gin;
    static constexpr const char* kNames[3] = {"cutoff", "res", "drive"};
public:
    void prepare(double s, int) override { sr = (float)s; gin.init(s, 10, 1); reset(); }
    void reset() override { svf.init(); }
    void process(const float* inL, const float* inR, float* outL, float* outR, int n, const TransportInfo&) override {
        svf.setCoeff(sst::filters::CytomicSVF::Mode::Lowpass, clampf(cutoff, 20, 20000), clampf(res, 0, 0.95f), 1.f / sr);
        const float pre = 1 + 6 * drive, comp = 1 / (1 + 3 * drive);
        for (int i = 0; i < n; i++) {
            const float g = gin.next(pre);
            float l = std::tanh(inL[i] * g) * comp, r = std::tanh(inR[i] * g) * comp;
            if (drive < 1e-4f) { l = inL[i]; r = inR[i]; }
            sst::filters::CytomicSVF::step(svf, l, r);
            outL[i] = l; outR[i] = r;
        }
    }
    void setParam(int i, float v) override { if (i == 0) cutoff = v; if (i == 1) res = v; if (i == 2) drive = clampf(v, 0, 1); }
    void onTouch(bool) override {}
    int numParams() const override { return 3; }
    const char* paramName(int i) const override { return i >= 0 && i < 3 ? kNames[i] : ""; }
};

// ---------------------------------------------------------------- ping-pong delay

// the concrete configuration, with the delay's time in Clean mode (retimes crossfade) and its feedback soft-clipped
struct DelayCfg : sst::effects::core::ConcreteConfig {
    static int deformType(EffectStorage*, int idx) { return idx <= 1 ? 1 : idx == 2 ? 1 : 0; }
};
using SDelay = sst::effects::delay::Delay<DelayCfg>;

class DelayFx : public IEffect {
    DelayCfg::GlobalStorage gs{44100};
    DelayCfg::EffectStorage es;
    SDelay* fx = nullptr;
    float time = 0.25f, feedback = 0.5f, width = 0.f;
    float bl alignas(16)[DelayCfg::blockSize], br alignas(16)[DelayCfg::blockSize];
    static constexpr const char* kNames[3] = {"time", "feedback", "width"};
    void apply() {
        fx->paramStorage[SDelay::dly_time_left] = std::log2(clampf(time, 0.01f, 2.5f));
        fx->paramStorage[SDelay::dly_time_right] = std::log2(clampf(time, 0.01f, 2.5f));
        fx->paramStorage[SDelay::dly_feedback] = 0.f;
        fx->paramStorage[SDelay::dly_crossfeed] = clampf(feedback, 0, 0.95f);   // the ping-pong
        fx->paramStorage[SDelay::dly_width] = clampf(width, -24, 24);
    }
public:
    DelayFx() {}
    ~DelayFx() override { delete fx; }
    void prepare(double s, int) override {
        gs = DelayCfg::GlobalStorage(s);
        delete fx;
        fx = new SDelay(&gs, &es, nullptr);
        for (int i = 0; i < SDelay::numParams; i++) fx->paramStorage[i] = fx->paramAt(i).defaultVal;
        fx->paramStorage[SDelay::dly_mix] = 1.f;                 // wet only: the slot mixes
        fx->paramStorage[SDelay::dly_input_channel] = -1.f;      // from the left: the first echo there, then across
        fx->paramStorage[SDelay::dly_mod_depth] = 0.f;
        apply();
        fx->initialize();
    }
    void reset() override { apply(); fx->initialize(); }
    void process(const float* inL, const float* inR, float* outL, float* outR, int n, const TransportInfo&) override {
        apply();
        constexpr int B = DelayCfg::blockSize;
        for (int i0 = 0; i0 < n; i0 += B) {
            const int m = std::min(B, n - i0);
            for (int k = 0; k < B; k++) { bl[k] = k < m ? inL[i0 + k] : 0; br[k] = k < m ? inR[i0 + k] : 0; }
            fx->processBlock(bl, br);
            for (int k = 0; k < m; k++) { outL[i0 + k] = bl[k]; outR[i0 + k] = br[k]; }
        }
    }
    void setParam(int i, float v) override { if (i == 0) time = v; if (i == 1) feedback = v; if (i == 2) width = v; }
    void onTouch(bool) override {}
    int numParams() const override { return 3; }
    const char* paramName(int i) const override { return i >= 0 && i < 3 ? kNames[i] : ""; }
};

// ---------------------------------------------------------------- spring reverb

class SpringFx : public IEffect {
    chowdsp::SpringReverbProc* proc = nullptr;
    chowdsp::SpringReverbProc::Params p;
    float bl[BLOCK_SIZE], br[BLOCK_SIZE];
    double sr = 44100;
    static constexpr const char* kNames[5] = {"size", "decay", "damping", "spin", "chaos"};
public:
    SpringFx() { proc = new chowdsp::SpringReverbProc(); }
    ~SpringFx() override { delete proc; }
    void prepare(double s, int) override { sr = s; proc->prepare((float)s, BLOCK_SIZE); }
    void reset() override { proc->prepare((float)sr, BLOCK_SIZE); }
    void process(const float* inL, const float* inR, float* outL, float* outR, int n, const TransportInfo&) override {
        for (int i0 = 0; i0 < n; i0 += BLOCK_SIZE) {
            const int m = std::min((int)BLOCK_SIZE, n - i0);
            std::memcpy(bl, inL + i0, m * sizeof(float));
            std::memcpy(br, inR + i0, m * sizeof(float));
            proc->setParams(p, m);
            proc->processBlock(bl, br, m);
            std::memcpy(outL + i0, bl, m * sizeof(float));
            std::memcpy(outR + i0, br, m * sizeof(float));
        }
    }
    void setParam(int i, float v) override {
        v = clampf(v, 0, 1);
        if (i == 0) p.size = v;
        if (i == 1) p.decay = v;
        if (i == 2) p.damping = v;
        if (i == 3) p.spin = v;
        if (i == 4) p.chaos = v;
    }
    void onTouch(bool) override {}
    int numParams() const override { return 5; }
    const char* paramName(int i) const override { return i >= 0 && i < 5 ? kNames[i] : ""; }
};

// ---------------------------------------------------------------- BBD ensemble

// Surge XT's BBDEnsembleEffect (BBD path, 512 stages at a 40 kHz clock: 6.4 ms in the middle), without its storage:
// two LFOs (0.18 Hz and 5.52 Hz by default, `speed` scales both), each three sines 120 degrees apart, sweep two BBD
// lines per side; their sum is soft-clipped back in through a DC-blocked feedback, saturated, -8 dB, then widened.
class EnsembleFx : public IEffect {
    BBDDelayLine<512> dL1, dL2, dR1, dR2;
    BBDNonlin sat;
    double sr = 44100;
    double ph1 = 0, ph2 = 0;
    float fbL = 0, fbR = 0;
    float hx[2][2] = {{0, 0}, {0, 0}}, hy[2][2] = {{0, 0}, {0, 0}};   // the DC blockers (two one-pole highpasses a side)
    float speed = 1, depth = 1, feedback = 0, width = 1;
    static constexpr const char* kNames[4] = {"speed", "depth", "feedback", "width"};
    static inline float soft(float x) { x = clampf(x, -1.5f, 1.5f); return x - x * x * x * (4.f / 27.f); }
public:
    void prepare(double s, int) override {
        sr = s;
        for (auto* d : {&dL1, &dL2, &dR1, &dR2}) d->prepare(s);
        sat.reset((float)s);
        reset();
    }
    void reset() override {
        for (auto* d : {&dL1, &dL2, &dR1, &dR2}) { d->setFilterFreq(10000.f); d->setDelayTime(0.005f); }
        sat.setDrive(0.f);
        ph1 = ph2 = 0; fbL = fbR = 0;
        std::memset(hx, 0, sizeof hx); std::memset(hy, 0, sizeof hy);
    }
    void process(const float* inL, const float* inR, float* outL, float* outR, int n, const TransportInfo&) override {
        const float clock = 40.f;                               // kHz
        const float center = 512.f / (2.f * clock) * 0.001f;    // s
        const float scale = 0.95f * center / (0.6f + 0.2f) * 1000.f;
        const float del1 = scale * 0.6f * 0.001f * depth, del2 = scale * 0.2f * 0.001f * depth * 0.6f;
        const double r1 = 0.18 * speed / sr, r2 = 5.52 * speed / sr;
        const float fbGain = 0.49f * std::sqrt(0.2f * clampf(feedback, 0, 1));
        const float hpA = (float)std::exp(-2.0 * M_PI * 50.0 / sr);
        const float out = std::pow(10.f, -8.f / 20.f);
        for (int i = 0; i < n; i++) {
            float l = soft(inL[i] * 0.75f + fbL), r = soft(inR[i] * 0.75f + fbR);
            float t[3];
            for (int k = 0; k < 3; k++) {
                const double o = k / 3.0;
                t[k] = del1 * (float)std::sin(2 * M_PI * (ph1 + o)) + del2 * (float)std::sin(2 * M_PI * (ph2 + o)) + center;
            }
            dL1.setDelayTime(t[0]); dL2.setDelayTime(t[1]); dR1.setDelayTime(t[1]); dR2.setDelayTime(t[2]);
            float o alignas(16)[4] = {dL1.process(l), dL2.process(l), dR1.process(r), dR2.process(r)};
            float a = fbGain * (o[0] + o[1]), b = fbGain * (o[2] + o[3]);
            // the feedback's DC blocked (two one-pole highpasses in a row, 50 Hz)
            for (int k = 0; k < 2; k++) {
                const float ya = a - hx[0][k] + hpA * hy[0][k]; hx[0][k] = a; hy[0][k] = ya; a = ya;
                const float yb = b - hx[1][k] + hpA * hy[1][k]; hx[1][k] = b; hy[1][k] = yb; b = yb;
            }
            fbL = a; fbR = b;
            auto v = sat.processSample(SIMD_MM(load_ps)(o));
            float w alignas(16)[4];
            SIMD_MM(store_ps)(w, v);
            float L = (w[0] + w[1]) * out, R = (w[2] + w[3]) * out;
            const float mid = 0.5f * (L + R), side = 0.5f * (L - R) * width;
            outL[i] = mid + side; outR[i] = mid - side;
            ph1 += r1; if (ph1 >= 1) ph1 -= 1;
            ph2 += r2; if (ph2 >= 1) ph2 -= 1;
        }
    }
    void setParam(int i, float v) override {
        if (i == 0) speed = clampf(v, 0.05f, 20);
        if (i == 1) depth = clampf(v, 0, 1);
        if (i == 2) feedback = clampf(v, 0, 1);
        if (i == 3) width = clampf(v, 0, 2);
    }
    void onTouch(bool) override {}
    int numParams() const override { return 4; }
    const char* paramName(int i) const override { return i >= 0 && i < 4 ? kNames[i] : ""; }
};

// ---------------------------------------------------------------- waveshaper

class ShaperFx : public IEffect {
    static constexpr sst::waveshapers::WaveshaperType kShapes[6] = {
        sst::waveshapers::WaveshaperType::wst_soft, sst::waveshapers::WaveshaperType::wst_asym,
        sst::waveshapers::WaveshaperType::wst_sine, sst::waveshapers::WaveshaperType::wst_westfold,
        sst::waveshapers::WaveshaperType::wst_hard, sst::waveshapers::WaveshaperType::wst_fuzzheavy};
    sst::waveshapers::QuadWaveshaperState st;
    sst::waveshapers::QuadWaveshaperPtr fn = nullptr;
    int shape = -1, want = 0;
    float drive = 1;
    float dcL = 0, dcR = 0, xL = 0, xR = 0, dcA = 0.999f;
    Smooth gd;
    static constexpr const char* kNames[2] = {"shape", "drive"};
    void take(int s) {
        shape = s;
        fn = sst::waveshapers::GetQuadWaveshaper(kShapes[s]);
        std::memset(&st, 0, sizeof st);
        float R[sst::waveshapers::n_waveshaper_registers];
        sst::waveshapers::initializeWaveshaperRegister(kShapes[s], R);
        for (int k = 0; k < sst::waveshapers::n_waveshaper_registers; k++) st.R[k] = SIMD_MM(set1_ps)(R[k]);
        st.init = SIMD_MM(cmpeq_ps)(SIMD_MM(setzero_ps)(), SIMD_MM(setzero_ps)());   // all ones: the first sample
    }
public:
    void prepare(double s, int) override { dcA = (float)std::exp(-2.0 * M_PI * 20.0 / s); gd.init(s, 10, 1); take(0); }
    void reset() override { take(want); dcL = dcR = xL = xR = 0; }
    void process(const float* inL, const float* inR, float* outL, float* outR, int n, const TransportInfo&) override {
        if (want != shape) take(want);
        for (int i = 0; i < n; i++) {
            const float d = gd.next(drive);
            const float comp = 1.f / std::sqrt(d);
            auto in = SIMD_MM(set_ps)(0, 0, inR[i], inL[i]);
            auto o = fn ? fn(&st, in, SIMD_MM(set1_ps)(d)) : in;
            float w alignas(16)[4];
            SIMD_MM(store_ps)(w, o);
            // a DC blocker (the asymmetric shapes)
            const float yl = w[0] - xL + dcA * dcL; xL = w[0]; dcL = yl;
            const float yr = w[1] - xR + dcA * dcR; xR = w[1]; dcR = yr;
            outL[i] = yl * comp; outR[i] = yr * comp;
        }
    }
    void setParam(int i, float v) override {
        if (i == 0) want = std::clamp((int)std::lround(v), 0, 5);
        if (i == 1) drive = clampf(v, 1, 32);
    }
    void onTouch(bool) override {}
    int numParams() const override { return 2; }
    const char* paramName(int i) const override { return i >= 0 && i < 2 ? kNames[i] : ""; }
};

// ---------------------------------------------------------------- resonator

class ResonatorFx : public IEffect {
    sst::filters::CytomicSVF bl, br;   // three bands each (lanes 0..2), the fourth unused
    float sr = 44100, note = 48, res = 0.8f, drive = 0;
    static constexpr const char* kNames[3] = {"note", "res", "drive"};
public:
    void prepare(double s, int) override { sr = (float)s; reset(); }
    void reset() override { bl.init(); br.init(); }
    void process(const float* inL, const float* inR, float* outL, float* outR, int n, const TransportInfo&) override {
        const float f0 = 440.f * std::pow(2.f, (clampf(note, 12, 108) - 69.f) / 12.f);
        float fr[4] = {f0, f0 * 2.f, f0 * 3.f, 1000.f};
        for (auto& f : fr) f = clampf(f, 20, sr * 0.45f);
        const float q = clampf(res, 0, 0.98f);
        bl.setCoeffQuadBandpass(fr, q, 1.f / sr);
        br.setCoeffQuadBandpass(fr, q, 1.f / sr);
        // the bands' levels, and a gain that keeps a resonant peak about where the input was
        const float g0 = 1.f, g1 = 0.5f, g2 = 0.3f;
        const float pre = 1 + 3 * drive;
        const float comp = 1.2f / (1 + 3 * drive);
        for (int i = 0; i < n; i++) {
            float a = std::tanh(inL[i] * pre), b = a, c = a, d = 0;
            sst::filters::CytomicSVF::step(bl, a, b, c, d);
            outL[i] = (a * g0 + b * g1 + c * g2) * comp;
            float e = std::tanh(inR[i] * pre), f = e, g = e, h = 0;
            sst::filters::CytomicSVF::step(br, e, f, g, h);
            outR[i] = (e * g0 + f * g1 + g * g2) * comp;
        }
    }
    void setParam(int i, float v) override { if (i == 0) note = v; if (i == 1) res = v; if (i == 2) drive = clampf(v, 0, 1); }
    void onTouch(bool) override {}
    int numParams() const override { return 3; }
    const char* paramName(int i) const override { return i >= 0 && i < 3 ? kNames[i] : ""; }
};

}  // namespace

IEffect* make_surge(const std::string& id) {
    if (id == "surge.filter") return new FilterFx();
    if (id == "surge.delay") return new DelayFx();
    if (id == "surge.spring") return new SpringFx();
    if (id == "surge.ensemble") return new EnsembleFx();
    if (id == "surge.shaper") return new ShaperFx();
    if (id == "surge.resonator") return new ResonatorFx();
    return nullptr;
}
