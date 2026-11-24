// SPDX-License-Identifier: GPL-3.0-only
// The pad effects' common interface (the brief's IEffect), shared by fxengine.cpp and the wrappers of each engine
// (airwin.cpp: Airwindows, surge.cpp: Surge's sst libraries, mi.cpp: the Eurorack-derived ones).
// Audio thread rules: process, setParam and onTouch allocate nothing and take no lock; everything is allocated in the
// constructor or prepare().
#pragma once
#include <string>

struct TransportInfo {
    double bpm;
    double beatPosition;   // beats since the clock started
    bool running;
};

class IEffect {
public:
    virtual ~IEffect() = default;
    virtual void prepare(double sampleRate, int blockSize) = 0;   // allocations here
    virtual void reset() = 0;                                      // clears, allocates nothing
    // n frames (at most the block size given to prepare), stereo, not interleaved; out may not be in
    virtual void process(const float* inL, const float* inR, float* outL, float* outR, int n, const TransportInfo& t) = 0;
    virtual void setParam(int index, float value) = 0;             // in the parameter's own units
    virtual void onTouch(bool down) = 0;
    virtual int numParams() const = 0;
    virtual const char* paramName(int index) const = 0;
};

// each engine's factory: the effect for `id`, or null if the id is not its
IEffect* make_airwin(const std::string& id);
IEffect* make_surge(const std::string& id);
IEffect* make_mi(const std::string& id);
