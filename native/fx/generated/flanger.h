/* ------------------------------------------------------------
name: "flanger"
Code generated with Faust 2.70.3 (https://faust.grame.fr)
Compilation options: -lang cpp -i -nvi -ct 1 -cn FlangerDsp -scn FaustDsp -es 1 -mcd 16 -mdd 1024 -mdy 33 -single -ftz 0
------------------------------------------------------------ */

#ifndef  __FlangerDsp_H__
#define  __FlangerDsp_H__

#ifndef FAUSTFLOAT
#define FAUSTFLOAT float
#endif 

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <math.h>

#ifndef FAUSTCLASS 
#define FAUSTCLASS FlangerDsp
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

class FlangerDspSIG0 {
	
  private:
	
	int iVec2[2];
	int iRec2[2];
	
  public:
	
	int getNumInputsFlangerDspSIG0() {
		return 0;
	}
	int getNumOutputsFlangerDspSIG0() {
		return 1;
	}
	
	void instanceInitFlangerDspSIG0(int sample_rate) {
		for (int l3 = 0; l3 < 2; l3 = l3 + 1) {
			iVec2[l3] = 0;
		}
		for (int l4 = 0; l4 < 2; l4 = l4 + 1) {
			iRec2[l4] = 0;
		}
	}
	
	void fillFlangerDspSIG0(int count, float* table) {
		for (int i1 = 0; i1 < count; i1 = i1 + 1) {
			iVec2[0] = 1;
			iRec2[0] = (iVec2[1] + iRec2[1]) % 65536;
			table[i1] = std::sin(9.58738e-05f * float(iRec2[0]));
			iVec2[1] = iVec2[0];
			iRec2[1] = iRec2[0];
		}
	}

};

static FlangerDspSIG0* newFlangerDspSIG0() { return (FlangerDspSIG0*)new FlangerDspSIG0(); }
static void deleteFlangerDspSIG0(FlangerDspSIG0* dsp) { delete dsp; }

class FlangerDspSIG1 {
	
  private:
	
	int iVec4[2];
	int iRec6[2];
	
  public:
	
	int getNumInputsFlangerDspSIG1() {
		return 0;
	}
	int getNumOutputsFlangerDspSIG1() {
		return 1;
	}
	
	void instanceInitFlangerDspSIG1(int sample_rate) {
		for (int l9 = 0; l9 < 2; l9 = l9 + 1) {
			iVec4[l9] = 0;
		}
		for (int l10 = 0; l10 < 2; l10 = l10 + 1) {
			iRec6[l10] = 0;
		}
	}
	
	void fillFlangerDspSIG1(int count, float* table) {
		for (int i2 = 0; i2 < count; i2 = i2 + 1) {
			iVec4[0] = 1;
			iRec6[0] = (iVec4[1] + iRec6[1]) % 65536;
			table[i2] = std::cos(9.58738e-05f * float(iRec6[0]));
			iVec4[1] = iVec4[0];
			iRec6[1] = iRec6[0];
		}
	}

};

static FlangerDspSIG1* newFlangerDspSIG1() { return (FlangerDspSIG1*)new FlangerDspSIG1(); }
static void deleteFlangerDspSIG1(FlangerDspSIG1* dsp) { delete dsp; }

static float ftbl0FlangerDspSIG0[65536];
static float ftbl1FlangerDspSIG1[65536];

class FlangerDsp final : public FaustDsp {
	
 private:
	
	int iVec0[2];
	int fSampleRate;
	float fConst1;
	float fConst2;
	FAUSTFLOAT fHslider0;
	float fRec1[2];
	int IOTA0;
	float fVec1[512];
	FAUSTFLOAT fHslider1;
	float fRec4[2];
	float fConst3;
	float fRec3[2];
	float fRec0[2];
	float fVec3[512];
	float fRec5[2];
	
 public:
	FlangerDsp() {}

	void metadata(Meta* m) { 
		m->declare("basics.lib/name", "Faust Basic Element Library");
		m->declare("basics.lib/tabulateNd", "Copyright (C) 2023 Bart Brouns <bart@magnetophon.nl>");
		m->declare("basics.lib/version", "1.12.0");
		m->declare("compile_options", "-lang cpp -i -nvi -ct 1 -cn FlangerDsp -scn FaustDsp -es 1 -mcd 16 -mdd 1024 -mdy 33 -single -ftz 0");
		m->declare("delays.lib/name", "Faust Delay Library");
		m->declare("delays.lib/version", "1.1.0");
		m->declare("filename", "flanger.dsp");
		m->declare("maths.lib/author", "GRAME");
		m->declare("maths.lib/copyright", "GRAME");
		m->declare("maths.lib/license", "LGPL with exception");
		m->declare("maths.lib/name", "Faust Math Library");
		m->declare("maths.lib/version", "2.7.0");
		m->declare("name", "flanger");
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
		FlangerDspSIG0* sig0 = newFlangerDspSIG0();
		sig0->instanceInitFlangerDspSIG0(sample_rate);
		sig0->fillFlangerDspSIG0(65536, ftbl0FlangerDspSIG0);
		FlangerDspSIG1* sig1 = newFlangerDspSIG1();
		sig1->instanceInitFlangerDspSIG1(sample_rate);
		sig1->fillFlangerDspSIG1(65536, ftbl1FlangerDspSIG1);
		deleteFlangerDspSIG0(sig0);
		deleteFlangerDspSIG1(sig1);
	}
	
	void instanceConstants(int sample_rate) {
		fSampleRate = sample_rate;
		float fConst0 = std::min<float>(1.92e+05f, std::max<float>(1.0f, float(fSampleRate)));
		fConst1 = 44.1f / fConst0;
		fConst2 = 1.0f - fConst1;
		fConst3 = 1.0f / fConst0;
	}
	
	void instanceResetUserInterface() {
		fHslider0 = FAUSTFLOAT(0.5f);
		fHslider1 = FAUSTFLOAT(0.3f);
	}
	
	void instanceClear() {
		for (int l0 = 0; l0 < 2; l0 = l0 + 1) {
			iVec0[l0] = 0;
		}
		for (int l1 = 0; l1 < 2; l1 = l1 + 1) {
			fRec1[l1] = 0.0f;
		}
		IOTA0 = 0;
		for (int l2 = 0; l2 < 512; l2 = l2 + 1) {
			fVec1[l2] = 0.0f;
		}
		for (int l5 = 0; l5 < 2; l5 = l5 + 1) {
			fRec4[l5] = 0.0f;
		}
		for (int l6 = 0; l6 < 2; l6 = l6 + 1) {
			fRec3[l6] = 0.0f;
		}
		for (int l7 = 0; l7 < 2; l7 = l7 + 1) {
			fRec0[l7] = 0.0f;
		}
		for (int l8 = 0; l8 < 512; l8 = l8 + 1) {
			fVec3[l8] = 0.0f;
		}
		for (int l11 = 0; l11 < 2; l11 = l11 + 1) {
			fRec5[l11] = 0.0f;
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
	
	FlangerDsp* clone() {
		return new FlangerDsp();
	}
	
	int getSampleRate() {
		return fSampleRate;
	}
	
	void buildUserInterface(UI* ui_interface) {
		ui_interface->openVerticalBox("flanger");
		ui_interface->addHorizontalSlider("feedback", &fHslider0, FAUSTFLOAT(0.5f), FAUSTFLOAT(0.0f), FAUSTFLOAT(0.95f), FAUSTFLOAT(0.001f));
		ui_interface->addHorizontalSlider("speed", &fHslider1, FAUSTFLOAT(0.3f), FAUSTFLOAT(0.05f), FAUSTFLOAT(5.0f), FAUSTFLOAT(0.001f));
		ui_interface->closeBox();
	}
	
	void compute(int count, FAUSTFLOAT** RESTRICT inputs, FAUSTFLOAT** RESTRICT outputs) {
		FAUSTFLOAT* input0 = inputs[0];
		FAUSTFLOAT* input1 = inputs[1];
		FAUSTFLOAT* output0 = outputs[0];
		FAUSTFLOAT* output1 = outputs[1];
		float fSlow0 = fConst1 * float(fHslider0);
		float fSlow1 = fConst1 * float(fHslider1);
		for (int i0 = 0; i0 < count; i0 = i0 + 1) {
			float fTemp0 = float(input0[i0]);
			iVec0[0] = 1;
			fRec1[0] = fSlow0 + fConst2 * fRec1[1];
			float fTemp1 = std::min<float>(0.95f, std::max<float>(0.0f, fRec1[0]));
			float fTemp2 = fTemp1 * fRec0[1] - fTemp0;
			fVec1[IOTA0 & 511] = fTemp2;
			fRec4[0] = fSlow1 + fConst2 * fRec4[1];
			float fTemp3 = ((1 - iVec0[1]) ? 0.0f : fRec3[1] + fConst3 * std::min<float>(5.0f, std::max<float>(0.05f, fRec4[0])));
			fRec3[0] = fTemp3 - std::floor(fTemp3);
			int iTemp4 = std::max<int>(0, std::min<int>(int(65536.0f * fRec3[0]), 65535));
			float fTemp5 = 124.0f * (ftbl0FlangerDspSIG0[iTemp4] + 1.0f);
			float fTemp6 = fTemp5 + 8.0f;
			int iTemp7 = int(fTemp6);
			float fTemp8 = std::floor(fTemp6);
			fRec0[0] = fVec1[(IOTA0 - std::min<int>(513, std::max<int>(0, iTemp7))) & 511] * (fTemp8 + (-7.0f - fTemp5)) + (fTemp5 + (8.0f - fTemp8)) * fVec1[(IOTA0 - std::min<int>(513, std::max<int>(0, iTemp7 + 1))) & 511];
			output0[i0] = FAUSTFLOAT(0.5f * (fTemp0 + fRec0[0]));
			float fTemp9 = float(input1[i0]);
			float fTemp10 = fTemp1 * fRec5[1] - fTemp9;
			fVec3[IOTA0 & 511] = fTemp10;
			float fTemp11 = 124.0f * (ftbl1FlangerDspSIG1[iTemp4] + 1.0f);
			float fTemp12 = fTemp11 + 8.0f;
			int iTemp13 = int(fTemp12);
			float fTemp14 = std::floor(fTemp12);
			fRec5[0] = fVec3[(IOTA0 - std::min<int>(513, std::max<int>(0, iTemp13))) & 511] * (fTemp14 + (-7.0f - fTemp11)) + (fTemp11 + (8.0f - fTemp14)) * fVec3[(IOTA0 - std::min<int>(513, std::max<int>(0, iTemp13 + 1))) & 511];
			output1[i0] = FAUSTFLOAT(0.5f * (fTemp9 + fRec5[0]));
			iVec0[1] = iVec0[0];
			fRec1[1] = fRec1[0];
			IOTA0 = IOTA0 + 1;
			fRec4[1] = fRec4[0];
			fRec3[1] = fRec3[0];
			fRec0[1] = fRec0[0];
			fRec5[1] = fRec5[0];
		}
	}

};

#endif
