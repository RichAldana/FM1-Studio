// SPDX-License-Identifier: GPL-3.0-only
// FM1 Mix: the one function of Surge's UnitConversions.h that its spring reverb uses (the rest needs fmt).
#pragma once
#include <cmath>
inline double dB_to_linear(double in) { return std::pow(10.0, 0.05 * in); }
