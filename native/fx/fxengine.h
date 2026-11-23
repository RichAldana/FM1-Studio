// SPDX-License-Identifier: GPL-3.0-only
// The pad effects' C interface (fxengine.cpp), used by native/src/fx.rs.
#pragma once
#ifdef __cplusplus
extern "C" {
#endif

typedef struct FxEffect FxEffect;

FxEffect* fx_new(const char* id);       /* null: no such effect in this build */
void fx_free(FxEffect* f);
void fx_prepare(FxEffect* f, double sampleRate, int blockSize);   /* allocations */
void fx_reset(FxEffect* f);             /* clears its buffers, allocates nothing */
int fx_num_params(FxEffect* f);
const char* fx_param_name(FxEffect* f, int i);
void fx_set_param(FxEffect* f, int i, float value);   /* in the parameter's own units */
void fx_on_touch(FxEffect* f, int down);
/* n frames, stereo, not interleaved; out may not be in */
void fx_process(FxEffect* f, const float* inL, const float* inR, float* outL, float* outR, int n,
                double bpm, double beat, int running);

#ifdef __cplusplus
}
#endif
