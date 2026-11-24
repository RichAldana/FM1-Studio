/* ------------------------------------------------------------
name: "phaser"
Code generated with Faust 2.70.3 (https://faust.grame.fr)
Compilation options: -lang cpp -i -nvi -ct 1 -cn PhaserDsp -scn FaustDsp -es 1 -mcd 16 -mdd 1024 -mdy 33 -single -ftz 0
------------------------------------------------------------ */

#ifndef  __PhaserDsp_H__
#define  __PhaserDsp_H__

#ifndef FAUSTFLOAT
#define FAUSTFLOAT float
#endif 

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <math.h>

#ifndef FAUSTCLASS 
#define FAUSTCLASS PhaserDsp
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

static float PhaserDsp_faustpower2_f(float value) {
	return value * value;
}

class PhaserDsp final : public FaustDsp {
	
 private:
	
	int iVec0[2];
	int fSampleRate;
	float fConst1;
	float fConst2;
	FAUSTFLOAT fHslider0;
	float fRec0[2];
	FAUSTFLOAT fHslider1;
	float fRec4[2];
	float fConst3;
	float fRec2[2];
	float fRec3[2];
	float fConst4;
	float fConst5;
	float fConst6;
	float fConst7;
	float fConst9;
	float fConst10;
	FAUSTFLOAT fHslider2;
	float fRec9[2];
	float fRec8[3];
	float fRec7[3];
	float fRec6[3];
	float fRec5[3];
	float fRec1[2];
	float fRec14[3];
	float fRec13[3];
	float fRec12[3];
	float fRec11[3];
	float fRec10[2];
	
 public:
	PhaserDsp() {}

	void metadata(Meta* m) { 
		m->declare("compile_options", "-lang cpp -i -nvi -ct 1 -cn PhaserDsp -scn FaustDsp -es 1 -mcd 16 -mdd 1024 -mdy 33 -single -ftz 0");
		m->declare("filename", "phaser.dsp");
		m->declare("filters.lib/fir:author", "Julius O. Smith III");
		m->declare("filters.lib/fir:copyright", "Copyright (C) 2003-2019 by Julius O. Smith III <jos@ccrma.stanford.edu>");
		m->declare("filters.lib/fir:license", "MIT-style STK-4.3 license");
		m->declare("filters.lib/iir:author", "Julius O. Smith III");
		m->declare("filters.lib/iir:copyright", "Copyright (C) 2003-2019 by Julius O. Smith III <jos@ccrma.stanford.edu>");
		m->declare("filters.lib/iir:license", "MIT-style STK-4.3 license");
		m->declare("filters.lib/lowpass0_highpass1", "Copyright (C) 2003-2019 by Julius O. Smith III <jos@ccrma.stanford.edu>");
		m->declare("filters.lib/name", "Faust Filters Library");
		m->declare("filters.lib/nlf2:author", "Julius O. Smith III");
		m->declare("filters.lib/nlf2:copyright", "Copyright (C) 2003-2019 by Julius O. Smith III <jos@ccrma.stanford.edu>");
		m->declare("filters.lib/nlf2:license", "MIT-style STK-4.3 license");
		m->declare("filters.lib/tf2:author", "Julius O. Smith III");
		m->declare("filters.lib/tf2:copyright", "Copyright (C) 2003-2019 by Julius O. Smith III <jos@ccrma.stanford.edu>");
		m->declare("filters.lib/tf2:license", "MIT-style STK-4.3 license");
		m->declare("filters.lib/version", "1.3.0");
		m->declare("maths.lib/author", "GRAME");
		m->declare("maths.lib/copyright", "GRAME");
		m->declare("maths.lib/license", "LGPL with exception");
		m->declare("maths.lib/name", "Faust Math Library");
		m->declare("maths.lib/version", "2.7.0");
		m->declare("name", "phaser");
		m->declare("oscillators.lib/name", "Faust Oscillator Library");
		m->declare("oscillators.lib/version", "1.5.0");
		m->declare("phaflangers.lib/name", "Faust Phaser and Flanger Library");
		m->declare("phaflangers.lib/version", "1.1.0");
		m->declare("platform.lib/name", "Generic Platform Library");
		m->declare("platform.lib/version", "1.3.0");
		m->declare("signals.lib/name", "Faust Signal Routing Library");
		m->declare("signals.lib/version", "1.5.0");
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
		float fConst0 = std::min<float>(1.92e+05f, std::max<float>(1.0f, float(fSampleRate)));
		fConst1 = 44.1f / fConst0;
		fConst2 = 1.0f - fConst1;
		fConst3 = 6.2831855f / fConst0;
		fConst4 = 5.0625f / fConst0;
		fConst5 = 3.375f / fConst0;
		fConst6 = 2.25f / fConst0;
		fConst7 = 1.5f / fConst0;
		float fConst8 = std::exp(-(3141.5928f / fConst0));
		fConst9 = PhaserDsp_faustpower2_f(fConst8);
		fConst10 = 2.0f * fConst8;
	}
	
	void instanceResetUserInterface() {
		fHslider0 = FAUSTFLOAT(1.0f);
		fHslider1 = FAUSTFLOAT(0.5f);
		fHslider2 = FAUSTFLOAT(0.3f);
	}
	
	void instanceClear() {
		for (int l0 = 0; l0 < 2; l0 = l0 + 1) {
			iVec0[l0] = 0;
		}
		for (int l1 = 0; l1 < 2; l1 = l1 + 1) {
			fRec0[l1] = 0.0f;
		}
		for (int l2 = 0; l2 < 2; l2 = l2 + 1) {
			fRec4[l2] = 0.0f;
		}
		for (int l3 = 0; l3 < 2; l3 = l3 + 1) {
			fRec2[l3] = 0.0f;
		}
		for (int l4 = 0; l4 < 2; l4 = l4 + 1) {
			fRec3[l4] = 0.0f;
		}
		for (int l5 = 0; l5 < 2; l5 = l5 + 1) {
			fRec9[l5] = 0.0f;
		}
		for (int l6 = 0; l6 < 3; l6 = l6 + 1) {
			fRec8[l6] = 0.0f;
		}
		for (int l7 = 0; l7 < 3; l7 = l7 + 1) {
			fRec7[l7] = 0.0f;
		}
		for (int l8 = 0; l8 < 3; l8 = l8 + 1) {
			fRec6[l8] = 0.0f;
		}
		for (int l9 = 0; l9 < 3; l9 = l9 + 1) {
			fRec5[l9] = 0.0f;
		}
		for (int l10 = 0; l10 < 2; l10 = l10 + 1) {
			fRec1[l10] = 0.0f;
		}
		for (int l11 = 0; l11 < 3; l11 = l11 + 1) {
			fRec14[l11] = 0.0f;
		}
		for (int l12 = 0; l12 < 3; l12 = l12 + 1) {
			fRec13[l12] = 0.0f;
		}
		for (int l13 = 0; l13 < 3; l13 = l13 + 1) {
			fRec12[l13] = 0.0f;
		}
		for (int l14 = 0; l14 < 3; l14 = l14 + 1) {
			fRec11[l14] = 0.0f;
		}
		for (int l15 = 0; l15 < 2; l15 = l15 + 1) {
			fRec10[l15] = 0.0f;
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
	
	PhaserDsp* clone() {
		return new PhaserDsp();
	}
	
	int getSampleRate() {
		return fSampleRate;
	}
	
	void buildUserInterface(UI* ui_interface) {
		ui_interface->openVerticalBox("phaser");
		ui_interface->addHorizontalSlider("depth", &fHslider0, FAUSTFLOAT(1.0f), FAUSTFLOAT(0.0f), FAUSTFLOAT(1.0f), FAUSTFLOAT(0.001f));
		ui_interface->addHorizontalSlider("feedback", &fHslider2, FAUSTFLOAT(0.3f), FAUSTFLOAT(0.0f), FAUSTFLOAT(0.9f), FAUSTFLOAT(0.001f));
		ui_interface->addHorizontalSlider("speed", &fHslider1, FAUSTFLOAT(0.5f), FAUSTFLOAT(0.05f), FAUSTFLOAT(8.0f), FAUSTFLOAT(0.001f));
		ui_interface->closeBox();
	}
	
	void compute(int count, FAUSTFLOAT** RESTRICT inputs, FAUSTFLOAT** RESTRICT outputs) {
		FAUSTFLOAT* input0 = inputs[0];
		FAUSTFLOAT* input1 = inputs[1];
		FAUSTFLOAT* output0 = outputs[0];
		FAUSTFLOAT* output1 = outputs[1];
		float fSlow0 = fConst1 * float(fHslider0);
		float fSlow1 = fConst1 * float(fHslider1);
		float fSlow2 = fConst1 * float(fHslider2);
		for (int i0 = 0; i0 < count; i0 = i0 + 1) {
			iVec0[0] = 1;
			fRec0[0] = fSlow0 + fConst2 * fRec0[1];
			float fTemp0 = std::min<float>(1.0f, std::max<float>(0.0f, fRec0[0]));
			fRec4[0] = fSlow1 + fConst2 * fRec4[1];
			float fTemp1 = fConst3 * std::min<float>(8.0f, std::max<float>(0.05f, fRec4[0]));
			float fTemp2 = std::cos(fTemp1);
			float fTemp3 = std::sin(fTemp1);
			fRec2[0] = fRec3[1] * fTemp3 + fRec2[1] * fTemp2;
			fRec3[0] = float(1 - iVec0[1]) + fRec3[1] * fTemp2 - fTemp3 * fRec2[1];
			float fTemp4 = 2199.1147f * (1.0f - fRec2[0]) + 628.31854f;
			float fTemp5 = fRec5[1] * std::cos(fConst4 * fTemp4);
			float fTemp6 = fRec6[1] * std::cos(fConst5 * fTemp4);
			float fTemp7 = fRec7[1] * std::cos(fConst6 * fTemp4);
			float fTemp8 = fRec8[1] * std::cos(fConst7 * fTemp4);
			fRec9[0] = fSlow2 + fConst2 * fRec9[1];
			float fTemp9 = std::min<float>(0.9f, std::max<float>(0.0f, fRec9[0]));
			float fTemp10 = float(input0[i0]);
			fRec8[0] = fTemp10 + fTemp9 * fRec1[1] + fConst10 * fTemp8 - fConst9 * fRec8[2];
			fRec7[0] = fRec8[2] + fConst9 * (fRec8[0] - fRec7[2]) - fConst10 * (fTemp8 - fTemp7);
			fRec6[0] = fRec7[2] + fConst9 * (fRec7[0] - fRec6[2]) - fConst10 * (fTemp7 - fTemp6);
			fRec5[0] = fRec6[2] + fConst9 * (fRec6[0] - fRec5[2]) - fConst10 * (fTemp6 - fTemp5);
			fRec1[0] = fRec5[2] + fConst9 * fRec5[0] - fConst10 * fTemp5;
			float fTemp11 = 1.0f - 0.5f * fTemp0;
			output0[i0] = FAUSTFLOAT(fTemp10 * fTemp11 + 0.5f * fRec1[0] * fTemp0);
			float fTemp12 = 2199.1147f * (1.0f - fRec3[0]) + 628.31854f;
			float fTemp13 = fRec11[1] * std::cos(fConst4 * fTemp12);
			float fTemp14 = fRec12[1] * std::cos(fConst5 * fTemp12);
			float fTemp15 = fRec13[1] * std::cos(fConst6 * fTemp12);
			float fTemp16 = fRec14[1] * std::cos(fConst7 * fTemp12);
			float fTemp17 = float(input1[i0]);
			fRec14[0] = fTemp17 + fTemp9 * fRec10[1] + fConst10 * fTemp16 - fConst9 * fRec14[2];
			fRec13[0] = fRec14[2] + fConst9 * (fRec14[0] - fRec13[2]) - fConst10 * (fTemp16 - fTemp15);
			fRec12[0] = fRec13[2] + fConst9 * (fRec13[0] - fRec12[2]) - fConst10 * (fTemp15 - fTemp14);
			fRec11[0] = fRec12[2] + fConst9 * (fRec12[0] - fRec11[2]) - fConst10 * (fTemp14 - fTemp13);
			fRec10[0] = fRec11[2] + fConst9 * fRec11[0] - fConst10 * fTemp13;
			output1[i0] = FAUSTFLOAT(fTemp17 * fTemp11 + 0.5f * fRec10[0] * fTemp0);
			iVec0[1] = iVec0[0];
			fRec0[1] = fRec0[0];
			fRec4[1] = fRec4[0];
			fRec2[1] = fRec2[0];
			fRec3[1] = fRec3[0];
			fRec9[1] = fRec9[0];
			fRec8[2] = fRec8[1];
			fRec8[1] = fRec8[0];
			fRec7[2] = fRec7[1];
			fRec7[1] = fRec7[0];
			fRec6[2] = fRec6[1];
			fRec6[1] = fRec6[0];
			fRec5[2] = fRec5[1];
			fRec5[1] = fRec5[0];
			fRec1[1] = fRec1[0];
			fRec14[2] = fRec14[1];
			fRec14[1] = fRec14[0];
			fRec13[2] = fRec13[1];
			fRec13[1] = fRec13[0];
			fRec12[2] = fRec12[1];
			fRec12[1] = fRec12[0];
			fRec11[2] = fRec11[1];
			fRec11[1] = fRec11[0];
			fRec10[1] = fRec10[0];
		}
	}

};

#endif
