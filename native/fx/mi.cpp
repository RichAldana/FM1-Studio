// SPDX-License-Identifier: GPL-3.0-only
// The pad's Eurorack-derived effects: the DSP code of three modules by Emilie Gillet (MIT; vendored, with stmlib, in
// third_party/eurorack), adapted the way VCV's AudibleInstruments runs them on a computer (its block sizes and
// rates; GPL-3.0). Their names in the app are this project's own ("Boucle granulaire", "Texture", "Résonateur
// modal", "Modulation croisée"); the credit is on the licences screen only.
//   - Clouds' GranularProcessor, 32 kHz by 32 frames (16-bit frames), resampled both ways (resample.h): two
//     effects from it, the looping delay ("mi.looper") and the granular mode ("mi.texture"); the finger held: freeze;
//   - Rings' Part (modal resonator), 48 kHz by 24 frames, excited by the sound (its strums found in it);
//   - Warps' Modulator at the host's rate (as AudibleInstruments does, its note corrected for the rate), its
//     internal oscillator as the carrier and the sound as the modulator: one stream in.
// Their own dry/wet is kept fully wet: the pad's slot mixes.
#include <algorithm>
#include <cmath>
#include <cstring>
#include <string>
#include <vector>
#include "effect.h"
#include "resample.h"
#include "clouds/dsp/granular_processor.h"
#include "rings/dsp/part.h"
#include "rings/dsp/strummer.h"
#include "warps/dsp/modulator.h"

namespace {

inline float clamp01(float v) { return std::fmin(1.f, std::fmax(0.f, v)); }
inline int16_t s16(float v) { return (int16_t)std::fmax(-32768.f, std::fmin(32767.f, v * 32767.f)); }

// ---------------------------------------------------------------- Clouds

class CloudsFx : public IEffect {
    static constexpr int kMem = 118784;
    static constexpr int kCcm = 65536 - 128;
    clouds::PlaybackMode mode;
    clouds::GranularProcessor* proc = nullptr;
    std::vector<uint8_t> mem, ccm;
    RateAdapter rate;
    bool freeze = false;
    // position, size, pitch, density, texture, feedback, reverb, spread
    float v[8] = {0.5f, 0.5f, 0.f, 0.5f, 0.5f, 0.f, 0.f, 0.5f};
    static constexpr const char* kNames[8] = {"position", "size", "pitch", "density", "texture", "feedback", "reverb", "spread"};
    void init() {
        std::memset((void*)proc, 0, sizeof(*proc));
        std::fill(mem.begin(), mem.end(), 0);
        std::fill(ccm.begin(), ccm.end(), 0);
        proc->Init(mem.data(), kMem, ccm.data(), kCcm);
        proc->set_playback_mode(mode);
        proc->set_quality(0);
        proc->Prepare();
    }
public:
    explicit CloudsFx(clouds::PlaybackMode m) : mode(m) {
        proc = new clouds::GranularProcessor();
        mem.assign(kMem, 0);
        ccm.assign(kCcm, 0);
    }
    ~CloudsFx() override { delete proc; }
    void prepare(double sampleRate, int) override {
        rate.prepare(sampleRate, 32000.0, 32);
        init();
    }
    void reset() override {
        // (the buffers are the processor's own: cleared, nothing allocated)
        rate.reset();
        freeze = false;
        init();
    }
    void process(const float* inL, const float* inR, float* outL, float* outR, int n, const TransportInfo&) override {
        rate.process(inL, inR, outL, outR, n, [&](const float* il, const float* ir, float* ol, float* orr, int b) {
            clouds::ShortFrame in[32], out[32];
            for (int k = 0; k < b; k++) { in[k].l = s16(il[k]); in[k].r = s16(ir[k]); }
            proc->set_playback_mode(mode);
            proc->set_quality(0);
            proc->Prepare();
            clouds::Parameters* p = proc->mutable_parameters();
            p->trigger = false;
            p->gate = false;
            p->freeze = freeze;
            p->position = clamp01(v[0]);
            p->size = clamp01(v[1]);
            p->pitch = std::fmax(-24.f, std::fmin(24.f, v[2]));
            p->density = clamp01(v[3]);
            p->texture = clamp01(v[4]);
            p->dry_wet = 1.f;
            p->stereo_spread = clamp01(v[7]);
            p->feedback = clamp01(v[5]);
            p->reverb = clamp01(v[6]);
            proc->Process(in, out, b);
            for (int k = 0; k < b; k++) { ol[k] = out[k].l / 32768.f; orr[k] = out[k].r / 32768.f; }
        });
    }
    void setParam(int i, float x) override { if (i >= 0 && i < 8) v[i] = x; }
    void onTouch(bool down) override { freeze = down; }
    int numParams() const override { return 8; }
    const char* paramName(int i) const override { return i >= 0 && i < 8 ? kNames[i] : ""; }
};

// ---------------------------------------------------------------- Rings

class RingsFx : public IEffect {
    rings::Part* part = nullptr;
    rings::Strummer strummer;
    std::vector<uint16_t> reverb;
    RateAdapter rate;
    // note (MIDI), structure, brightness, damping, position
    float v[5] = {48.f, 0.3f, 0.5f, 0.7f, 0.3f};
    static constexpr const char* kNames[5] = {"note", "structure", "brightness", "damping", "position"};
    void init() {
        std::fill(reverb.begin(), reverb.end(), 0);
        std::memset((void*)part, 0, sizeof(*part));
        part->Init(reverb.data());
        part->set_polyphony(1);
        part->set_model(rings::RESONATOR_MODEL_MODAL);
        strummer.Init(0.01f, 48000.f / 24.f);
    }
public:
    RingsFx() {
        part = new rings::Part();
        reverb.assign(32768, 0);
    }
    ~RingsFx() override { delete part; }
    void prepare(double sampleRate, int) override {
        rate.prepare(sampleRate, 48000.0, 24);
        init();
    }
    void reset() override { rate.reset(); init(); }
    void process(const float* inL, const float* inR, float* outL, float* outR, int n, const TransportInfo&) override {
        rate.process(inL, inR, outL, outR, n, [&](const float* il, const float* ir, float* ol, float* orr, int b) {
            float in[24], out[24], aux[24];
            for (int k = 0; k < b; k++) in[k] = 0.5f * (il[k] + ir[k]);
            rings::Patch patch;
            patch.structure = std::fmin(0.9995f, clamp01(v[1]));
            patch.brightness = clamp01(v[2]);
            patch.damping = std::fmin(0.9995f, clamp01(v[3]));
            patch.position = std::fmin(0.9995f, clamp01(v[4]));
            rings::PerformanceState ps;
            ps.strum = false;
            ps.internal_exciter = false;
            ps.internal_strum = true;      // strums found in the sound
            ps.internal_note = false;
            ps.tonic = 0.f;
            ps.note = std::fmax(12.f, std::fmin(108.f, v[0]));
            ps.fm = 0.f;
            ps.chord = std::clamp((int)std::lround(patch.structure * (rings::kNumChords - 1)), 0, rings::kNumChords - 1);
            strummer.Process(in, b, &ps);
            part->Process(ps, patch, in, out, aux, b);
            for (int k = 0; k < b; k++) { ol[k] = out[k]; orr[k] = aux[k]; }
        });
    }
    void setParam(int i, float x) override { if (i >= 0 && i < 5) v[i] = x; }
    void onTouch(bool) override {}
    int numParams() const override { return 5; }
    const char* paramName(int i) const override { return i >= 0 && i < 5 ? kNames[i] : ""; }
};

// ---------------------------------------------------------------- Warps

class WarpsFx : public IEffect {
    warps::Modulator* mod = nullptr;
    double sr = 44100;
    // algorithm (0..1), timbre (0..1), note (the carrier's, MIDI), level of the carrier, level of the sound, shape (1..3)
    float v[6] = {0.f, 0.5f, 48.f, 0.6f, 0.8f, 1.f};
    static constexpr const char* kNames[6] = {"algorithm", "timbre", "note", "carrier", "level", "shape"};
    void init() {
        std::memset((void*)mod, 0, sizeof(*mod));
        mod->Init(96000.0f);
    }
public:
    WarpsFx() { mod = new warps::Modulator(); }
    ~WarpsFx() override { delete mod; }
    void prepare(double sampleRate, int) override { sr = sampleRate; init(); }
    void reset() override { init(); }
    void process(const float* inL, const float* inR, float* outL, float* outR, int n, const TransportInfo&) override {
        warps::ShortFrame in[32], out[32];
        for (int i0 = 0; i0 < n; i0 += 32) {
            const int b = std::min(32, n - i0);
            warps::Parameters* p = mod->mutable_parameters();
            p->carrier_shape = std::clamp((int)std::lround(v[5]), 1, 3);
            p->channel_drive[0] = clamp01(v[3]);
            p->channel_drive[1] = clamp01(v[4]);
            p->modulation_algorithm = clamp01(v[0]);
            p->modulation_parameter = clamp01(v[1]);
            p->frequency_shift_pot = p->modulation_algorithm;
            p->frequency_shift_cv = 0.f;
            p->phase_shift = p->modulation_algorithm;
            p->note = v[2] + (float)(std::log2(96000.0 / sr) * 12.0);
            for (int k = 0; k < b; k++) {
                in[k].l = 0;                                               // the carrier: internal
                in[k].r = s16(0.5f * (inL[i0 + k] + inR[i0 + k]));         // the modulator: the sound
            }
            mod->Process(in, out, b);
            // (its output runs hot: brought down to about the input's level)
            for (int k = 0; k < b; k++) outL[i0 + k] = outR[i0 + k] = out[k].l * (0.45f / 32768.f);
        }
    }
    void setParam(int i, float x) override { if (i >= 0 && i < 6) v[i] = x; }
    void onTouch(bool) override {}
    int numParams() const override { return 6; }
    const char* paramName(int i) const override { return i >= 0 && i < 6 ? kNames[i] : ""; }
};

}  // namespace

IEffect* make_mi(const std::string& id) {
    if (id == "mi.looper") return new CloudsFx(clouds::PLAYBACK_MODE_LOOPING_DELAY);
    if (id == "mi.texture") return new CloudsFx(clouds::PLAYBACK_MODE_GRANULAR);
    if (id == "mi.resonator") return new RingsFx();
    if (id == "mi.crossmod") return new WarpsFx();
    return nullptr;
}
