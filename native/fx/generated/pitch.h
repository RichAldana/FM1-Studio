/* ------------------------------------------------------------
name: "pitch"
Code generated with Faust 2.70.3 (https://faust.grame.fr)
Compilation options: -lang cpp -i -nvi -ct 1 -cn PitchDsp -scn FaustDsp -es 1 -mcd 16 -mdd 1024 -mdy 33 -single -ftz 0
------------------------------------------------------------ */

#ifndef  __PitchDsp_H__
#define  __PitchDsp_H__

#ifndef FAUSTFLOAT
#define FAUSTFLOAT float
#endif 

#include <algorithm>
#include <cmath>
#include <cstdint>

#ifndef FAUSTCLASS 
#define FAUSTCLASS PitchDsp
#endif

#ifdef __APPLE__ 
#define exp10f __exp10f
#define exp10 __exp10
#endif

#if defined(_WIN32)
#define RESTRICT __restrict
#else
#define RESTRICT __restrict__
#endif


class PitchDsp final : public FaustDsp {
	
 private:
	
	FAUSTFLOAT fHslider0;
	float fRec0[2];
	int IOTA0;
	float fVec0[131072];
	float fVec1[131072];
	int fSampleRate;
	
 public:
	PitchDsp() {}

	void metadata(Meta* m) { 
		m->declare("compile_options", "-lang cpp -i -nvi -ct 1 -cn PitchDsp -scn FaustDsp -es 1 -mcd 16 -mdd 1024 -mdy 33 -single -ftz 0");
		m->declare("delays.lib/name", "Faust Delay Library");
		m->declare("delays.lib/version", "1.1.0");
		m->declare("filename", "pitch.dsp");
		m->declare("maths.lib/author", "GRAME");
		m->declare("maths.lib/copyright", "GRAME");
		m->declare("maths.lib/license", "LGPL with exception");
		m->declare("maths.lib/name", "Faust Math Library");
		m->declare("maths.lib/version", "2.7.0");
		m->declare("misceffects.lib/name", "Misc Effects Library");
		m->declare("misceffects.lib/version", "2.4.0");
		m->declare("name", "pitch");
	}

	static constexpr int getStaticNumInputs() {
		return 2;
	}
	static constexpr int getStaticNumOutputs() {
		return 2;
	}
	int getNumInputs() {
		return 2;
	}
	int getNumOutputs() {
		return 2;
	}
	
	static void classInit(int sample_rate) {
	}
	
	void instanceConstants(int sample_rate) {
		fSampleRate = sample_rate;
	}
	
	void instanceResetUserInterface() {
		fHslider0 = FAUSTFLOAT(0.0f);
	}
	
	void instanceClear() {
		for (int l0 = 0; l0 < 2; l0 = l0 + 1) {
			fRec0[l0] = 0.0f;
		}
		IOTA0 = 0;
		for (int l1 = 0; l1 < 131072; l1 = l1 + 1) {
			fVec0[l1] = 0.0f;
		}
		for (int l2 = 0; l2 < 131072; l2 = l2 + 1) {
			fVec1[l2] = 0.0f;
		}
	}
	
	void init(int sample_rate) {
		classInit(sample_rate);
		instanceInit(sample_rate);
	}
	
	void instanceInit(int sample_rate) {
		instanceConstants(sample_rate);
		instanceResetUserInterface();
		instanceClear();
	}
	
	PitchDsp* clone() {
		return new PitchDsp();
	}
	
	int getSampleRate() {
		return fSampleRate;
	}
	
	void buildUserInterface(UI* ui_interface) {
		ui_interface->openVerticalBox("pitch");
		ui_interface->addHorizontalSlider("shift", &fHslider0, FAUSTFLOAT(0.0f), FAUSTFLOAT(-12.0f), FAUSTFLOAT(12.0f), FAUSTFLOAT(0.01f));
		ui_interface->closeBox();
	}
	
	void compute(int count, FAUSTFLOAT** RESTRICT inputs, FAUSTFLOAT** RESTRICT outputs) {
		FAUSTFLOAT* input0 = inputs[0];
		FAUSTFLOAT* input1 = inputs[1];
		FAUSTFLOAT* output0 = outputs[0];
		FAUSTFLOAT* output1 = outputs[1];
		float fSlow0 = std::pow(2.0f, 0.083333336f * float(fHslider0));
		for (int i0 = 0; i0 < count; i0 = i0 + 1) {
			fRec0[0] = std::fmod(fRec0[1] + 2049.0f - fSlow0, 2048.0f);
			float fTemp0 = std::min<float>(0.00390625f * fRec0[0], 1.0f);
			float fTemp1 = 1.0f - fTemp0;
			float fTemp2 = fRec0[0] + 2048.0f;
			float fTemp3 = std::floor(fTemp2);
			float fTemp4 = fRec0[0] + (2048.0f - fTemp3);
			float fTemp5 = float(input0[i0]);
			fVec0[IOTA0 & 131071] = fTemp5;
			int iTemp6 = int(fTemp2);
			int iTemp7 = std::min<int>(65537, std::max<int>(0, iTemp6 + 1));
			float fTemp8 = fTemp3 + (-2047.0f - fRec0[0]);
			int iTemp9 = std::min<int>(65537, std::max<int>(0, iTemp6));
			int iTemp10 = int(fRec0[0]);
			int iTemp11 = std::min<int>(65537, std::max<int>(0, iTemp10 + 1));
			float fTemp12 = std::floor(fRec0[0]);
			float fTemp13 = fRec0[0] - fTemp12;
			float fTemp14 = fTemp12 + (1.0f - fRec0[0]);
			int iTemp15 = std::min<int>(65537, std::max<int>(0, iTemp10));
			output0[i0] = FAUSTFLOAT((fVec0[(IOTA0 - iTemp15) & 131071] * fTemp14 + fTemp13 * fVec0[(IOTA0 - iTemp11) & 131071]) * fTemp0 + (fVec0[(IOTA0 - iTemp9) & 131071] * fTemp8 + fVec0[(IOTA0 - iTemp7) & 131071] * fTemp4) * fTemp1);
			float fTemp16 = float(input1[i0]);
			fVec1[IOTA0 & 131071] = fTemp16;
			output1[i0] = FAUSTFLOAT(fTemp0 * (fVec1[(IOTA0 - iTemp15) & 131071] * fTemp14 + fTemp13 * fVec1[(IOTA0 - iTemp11) & 131071]) + fTemp1 * (fVec1[(IOTA0 - iTemp9) & 131071] * fTemp8 + fVec1[(IOTA0 - iTemp7) & 131071] * fTemp4));
			fRec0[1] = fRec0[0];
			IOTA0 = IOTA0 + 1;
		}
	}

};

#endif
