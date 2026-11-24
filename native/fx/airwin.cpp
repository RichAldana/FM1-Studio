// SPDX-License-Identifier: GPL-3.0-only
// The pad's Airwindows effects (Chris Johnson, MIT), as gathered by airwin2rack (Paul Walker, MIT): five of them,
// vendored in third_party/airwin2rack. Each is a VST2-style class (setParameter 0..1, processReplacing); one generic
// wrapper serves them all. Parameter names are Airwindows' own, lowercased letters only ("Dry/Wet" is "drywet").
// The slot does the dry/wet: profiles fix "drywet" at 1. DeRez2 has two more, made here: "type" (0 the sample rate
// reduced, 1 the bits) and "amount" (how much), which set its Rate and Rez together.
// reset() leaves the state as it is: the classes' constructors allocate (a std::set), which the audio thread must not;
// an effect is only reselected once its tail has died out, so little is left in them.
#include <cctype>
#include <cmath>
#include <cstring>
#include <string>
#include <vector>
#include "effect.h"
#include "third_party/airwin2rack/src/airwin_consolidated_base.h"
#include "third_party/airwin2rack/src/autogen_airwin/Isolator3.h"
#include "third_party/airwin2rack/src/autogen_airwin/kCosmos.h"
#include "third_party/airwin2rack/src/autogen_airwin/CrunchCoat.h"
#include "third_party/airwin2rack/src/autogen_airwin/DeRez2.h"
#include "third_party/airwin2rack/src/autogen_airwin/BeziComp.h"

namespace {

template <class T>
class AirwinFx : public IEffect {
    T* fx = nullptr;
    std::vector<std::string> names;
    int n = 0;
    bool derez2 = false;
    float type = 0.5f, amount = 0.f;
    void derez() {
        // DeRez2: Rate 1 and Rez 1 are untouched; "amount" takes the side "type" leans to
        fx->setParameter(0, 1.f - amount * (1.f - type) * 0.92f);   // Rate
        fx->setParameter(1, 1.f - amount * type * 0.92f);           // Rez
    }
public:
    explicit AirwinFx(bool isDerez2 = false) : derez2(isDerez2) {
        fx = new T(0);
        n = fx->kNumParametersHere();
    }
    ~AirwinFx() override { delete fx; }
    void prepare(double sampleRate, int) override {
        fx->setSampleRate((float)sampleRate);
        names.clear();
        char t[64];
        for (int i = 0; i < n; i++) {
            std::memset(t, 0, sizeof t);
            fx->getParameterName(i, t);
            std::string s;
            for (char* c = t; *c; c++) if (std::isalnum((unsigned char)*c)) s += (char)std::tolower((unsigned char)*c);
            names.push_back(s);
        }
        if (derez2) { names.push_back("type"); names.push_back("amount"); derez(); }
    }
    void reset() override {}
    void process(const float* inL, const float* inR, float* outL, float* outR, int frames, const TransportInfo&) override {
        float* in[2] = {const_cast<float*>(inL), const_cast<float*>(inR)};
        float* out[2] = {outL, outR};
        fx->processReplacing(in, out, frames);
    }
    void setParam(int i, float v) override {
        v = std::fmin(1.f, std::fmax(0.f, v));
        if (i >= 0 && i < n) fx->setParameter(i, v);
        else if (derez2 && i == n) { type = v; derez(); }
        else if (derez2 && i == n + 1) { amount = v; derez(); }
    }
    void onTouch(bool) override {}
    int numParams() const override { return (int)names.size(); }
    const char* paramName(int i) const override { return i >= 0 && i < (int)names.size() ? names[i].c_str() : ""; }
};

// the classes' parameter counts (an enum in each namespace)
#define AIRWIN(NS)                                                                         \
    struct NS##Fx : airwinconsolidated::NS::NS {                                           \
        NS##Fx(int m) : airwinconsolidated::NS::NS(m) {}                                   \
        int kNumParametersHere() const { return airwinconsolidated::NS::kNumParameters; } \
    };
AIRWIN(Isolator3)
AIRWIN(kCosmos)
AIRWIN(CrunchCoat)
AIRWIN(DeRez2)
AIRWIN(BeziComp)

}  // namespace

IEffect* make_airwin(const std::string& id) {
    if (id == "aw.isolator3") return new AirwinFx<Isolator3Fx>();
    if (id == "aw.kcosmos") return new AirwinFx<kCosmosFx>();
    if (id == "aw.crunchcoat") return new AirwinFx<CrunchCoatFx>();
    if (id == "aw.derez2") return new AirwinFx<DeRez2Fx>(true);
    if (id == "aw.bezicomp") return new AirwinFx<BeziCompFx>();
    return nullptr;
}
