// SPDX-License-Identifier: GPL-3.0-only
// For the engines that run at their own rate and block size (Clouds at 32 kHz by 32 frames, Rings at 48 kHz by 24):
//   - Resampler: a streaming windowed-sinc resampler (16 taps, 64 phases interpolated), any fixed ratio;
//   - RateAdapter: the host's frames into the engine's rate, its blocks run as soon as there are enough, the output
//     back to the host's rate, with a fixed latency (about one engine block plus the filters) so it never runs dry.
// Everything is allocated in prepare(); process() allocates nothing.
#pragma once
#include <cmath>
#include <cstring>
#include <vector>

template <int CH>
class Resampler {
    static constexpr int K = 8;        // half the taps
    static constexpr int P = 64;       // phases
    static constexpr int N = 2048;     // the input history kept
    float table[P + 1][2 * K];
    float buf[CH][N];
    int n = 0;                         // frames in buf
    double t = 0;                      // the next output's time, in buf's frames
    double step = 1;                   // input frames per output frame
public:
    void prepare(double inRate, double outRate) {
        step = inRate / outRate;
        // the cutoff: below the lower Nyquist, with some room for the transition
        const double fc = 0.9 * std::fmin(1.0, outRate / inRate);
        for (int p = 0; p <= P; p++) {
            const double f = (double)p / P;
            double sum = 0;
            for (int j = 0; j < 2 * K; j++) {
                const double x = (double)(j - K + 1) - f;          // distance from the output time to tap j
                const double s = x == 0 ? 1.0 : std::sin(M_PI * fc * x) / (M_PI * fc * x);
                const double w = 0.42 + 0.5 * std::cos(M_PI * x / K) + 0.08 * std::cos(2 * M_PI * x / K);  // Blackman
                table[p][j] = (float)(s * (std::fabs(x) < K ? w : 0.0));
                sum += table[p][j];
            }
            for (int j = 0; j < 2 * K; j++) table[p][j] = (float)(table[p][j] / sum);   // unity gain
        }
        reset();
    }
    void reset() {
        std::memset(buf, 0, sizeof buf);
        n = 2 * K;
        t = K;
    }
    /// One input frame; calls out(frame) for each output frame it completes.
    template <class F>
    void push(const float* x, F&& out) {
        if (n >= N) compact();
        for (int c = 0; c < CH; c++) buf[c][n] = x[c];
        n++;
        while (t + K < n) {
            const int i0 = (int)t;
            const double fr = (t - i0) * P;
            const int p = (int)fr;
            const float a = (float)(fr - p);
            float y[CH];
            for (int c = 0; c < CH; c++) {
                const float* s = &buf[c][i0 - K + 1];
                float acc = 0;
                for (int j = 0; j < 2 * K; j++) acc += s[j] * (table[p][j] + a * (table[p + 1][j] - table[p][j]));
                y[c] = acc;
            }
            out(y);
            t += step;
        }
    }
private:
    void compact() {
        const int keep = (int)t - K;
        if (keep <= 0) { n = 2 * K; t = K; return; }
        for (int c = 0; c < CH; c++) std::memmove(buf[c], buf[c] + keep, (size_t)(n - keep) * sizeof(float));
        n -= keep;
        t -= keep;
    }
};

/// A stereo FIFO of fixed capacity.
struct Fifo2 {
    std::vector<float> l, r;
    int head = 0, count = 0;
    void init(int cap) { l.assign(cap, 0.f); r.assign(cap, 0.f); head = count = 0; }
    int cap() const { return (int)l.size(); }
    void push(float a, float b) {
        if (count >= cap()) return;    // (full: dropped; cannot happen with the sizes below)
        const int i = (head + count) % cap();
        l[i] = a; r[i] = b; count++;
    }
    void pop(float& a, float& b) {
        if (count == 0) { a = b = 0; return; }
        a = l[head]; b = r[head]; head = (head + 1) % cap(); count--;
    }
    void clear() { head = count = 0; }
};

/// The host's stereo frames through an engine running at `innerRate` by blocks of `block` frames.
class RateAdapter {
    Resampler<2> up, down;
    Fifo2 inner, outer;
    std::vector<float> bl, br, ol, orr;
    int block = 32;
    int latency = 0;
public:
    void prepare(double hostRate, double innerRate, int blockSize) {
        block = blockSize;
        up.prepare(hostRate, innerRate);
        down.prepare(innerRate, hostRate);
        inner.init(4096);
        outer.init(8192);
        bl.assign(block, 0.f); br.assign(block, 0.f); ol.assign(block, 0.f); orr.assign(block, 0.f);
        latency = (int)std::ceil(block * hostRate / innerRate) + 40;
        reset();
    }
    void reset() {
        up.reset(); down.reset(); inner.clear(); outer.clear();
        for (int i = 0; i < latency; i++) outer.push(0, 0);
    }
    /// run(inL, inR, outL, outR, block): one engine block
    template <class Run>
    void process(const float* inL, const float* inR, float* outL, float* outR, int n, Run&& run) {
        for (int i = 0; i < n; i++) {
            const float x[2] = {inL[i], inR[i]};
            up.push(x, [&](const float* y) { inner.push(y[0], y[1]); });
            while (inner.count >= block) {
                for (int k = 0; k < block; k++) inner.pop(bl[k], br[k]);
                run(bl.data(), br.data(), ol.data(), orr.data(), block);
                for (int k = 0; k < block; k++) {
                    const float z[2] = {ol[k], orr[k]};
                    down.push(z, [&](const float* y) { outer.push(y[0], y[1]); });
                }
            }
            outer.pop(outL[i], outR[i]);
        }
    }
};
