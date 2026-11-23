/* ------------------------------------------------------------
name: "echo"
Code generated with Faust 2.70.3 (https://faust.grame.fr)
Compilation options: -lang cpp -i -nvi -ct 1 -cn EchoDsp -scn FaustDsp -es 1 -mcd 16 -mdd 1024 -mdy 33 -single -ftz 0
------------------------------------------------------------ */

#ifndef  __EchoDsp_H__
#define  __EchoDsp_H__

#ifndef FAUSTFLOAT
#define FAUSTFLOAT float
#endif 

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <math.h>

#ifndef FAUSTCLASS 
#define FAUSTCLASS EchoDsp
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


class EchoDsp final : public FaustDsp {
	
 private:
	
	int fSampleRate;
	float fConst0;
	float fConst1;
	float fConst2;
	FAUSTFLOAT fHslider0;
	float fRec1[2];
	int IOTA0;
	float fVec0[262144];
	FAUSTFLOAT fHslider1;
	float fRec2[2];
	float fRec3[2];
	float fRec4[2];
	float fRec5[2];
	float fRec0[2];
	float fVec1[262144];
	float fRec6[2];
	
 public:
	EchoDsp() {}

	void metadata(Meta* m) { 
		m->declare("basics.lib/name", "Faust Basic Element Library");
		m->declare("basics.lib/tabulateNd", "Copyright (C) 2023 Bart Brouns <bart@magnetophon.nl>");
		m->declare("basics.lib/version", "1.12.0");
		m->declare("compile_options", "-lang cpp -i -nvi -ct 1 -cn EchoDsp -scn FaustDsp -es 1 -mcd 16 -mdd 1024 -mdy 33 -single -ftz 0");
		m->declare("delays.lib/name", "Faust Delay Library");
		m->declare("delays.lib/version", "1.1.0");
		m->declare("filename", "echo.dsp");
		m->declare("maths.lib/author", "GRAME");
		m->declare("maths.lib/copyright", "GRAME");
		m->declare("maths.lib/license", "LGPL with exception");
		m->declare("maths.lib/name", "Faust Math Library");
		m->declare("maths.lib/version", "2.7.0");
		m->declare("name", "echo");
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
		fConst0 = std::min<float>(1.92e+05f, std::max<float>(1.0f, float(fSampleRate)));
		fConst1 = 44.1f / fConst0;
		fConst2 = 1.0f - fConst1;
	}
	
	void instanceResetUserInterface() {
		fHslider0 = FAUSTFLOAT(0.4f);
		fHslider1 = FAUSTFLOAT(0.25f);
	}
	
	void instanceClear() {
		for (int l0 = 0; l0 < 2; l0 = l0 + 1) {
			fRec1[l0] = 0.0f;
		}
		IOTA0 = 0;
		for (int l1 = 0; l1 < 262144; l1 = l1 + 1) {
			fVec0[l1] = 0.0f;
		}
		for (int l2 = 0; l2 < 2; l2 = l2 + 1) {
			fRec2[l2] = 0.0f;
		}
		for (int l3 = 0; l3 < 2; l3 = l3 + 1) {
			fRec3[l3] = 0.0f;
		}
		for (int l4 = 0; l4 < 2; l4 = l4 + 1) {
			fRec4[l4] = 0.0f;
		}
		for (int l5 = 0; l5 < 2; l5 = l5 + 1) {
			fRec5[l5] = 0.0f;
		}
		for (int l6 = 0; l6 < 2; l6 = l6 + 1) {
			fRec0[l6] = 0.0f;
		}
		for (int l7 = 0; l7 < 262144; l7 = l7 + 1) {
			fVec1[l7] = 0.0f;
		}
		for (int l8 = 0; l8 < 2; l8 = l8 + 1) {
			fRec6[l8] = 0.0f;
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
	
	EchoDsp* clone() {
		return new EchoDsp();
	}
	
	int getSampleRate() {
		return fSampleRate;
	}
	
	void buildUserInterface(UI* ui_interface) {
		ui_interface->openVerticalBox("echo");
		ui_interface->addHorizontalSlider("feedback", &fHslider0, FAUSTFLOAT(0.4f), FAUSTFLOAT(0.0f), FAUSTFLOAT(0.95f), FAUSTFLOAT(0.001f));
		ui_interface->addHorizontalSlider("time", &fHslider1, FAUSTFLOAT(0.25f), FAUSTFLOAT(0.01f), FAUSTFLOAT(2.0f), FAUSTFLOAT(0.0001f));
		ui_interface->closeBox();
	}
	
	void compute(int count, FAUSTFLOAT** RESTRICT inputs, FAUSTFLOAT** RESTRICT outputs) {
		FAUSTFLOAT* input0 = inputs[0];
		FAUSTFLOAT* input1 = inputs[1];
		FAUSTFLOAT* output0 = outputs[0];
		FAUSTFLOAT* output1 = outputs[1];
		float fSlow0 = fConst1 * float(fHslider0);
		float fSlow1 = fConst0 * float(fHslider1);
		for (int i0 = 0; i0 < count; i0 = i0 + 1) {
			fRec1[0] = fSlow0 + fConst2 * fRec1[1];
			float fTemp0 = std::min<float>(0.95f, std::max<float>(0.0f, fRec1[0]));
			float fTemp1 = float(input0[i0]) + fTemp0 * fRec0[1];
			fVec0[IOTA0 & 262143] = fTemp1;
			float fTemp2 = ((fRec2[1] != 0.0f) ? (((fRec3[1] > 0.0f) & (fRec3[1] < 1.0f)) ? fRec2[1] : 0.0f) : (((fRec3[1] == 0.0f) & (fSlow1 != fRec4[1])) ? 0.0009765625f : (((fRec3[1] == 1.0f) & (fSlow1 != fRec5[1])) ? -0.0009765625f : 0.0f)));
			fRec2[0] = fTemp2;
			fRec3[0] = std::max<float>(0.0f, std::min<float>(1.0f, fRec3[1] + fTemp2));
			fRec4[0] = (((fRec3[1] >= 1.0f) & (fRec5[1] != fSlow1)) ? fSlow1 : fRec4[1]);
			fRec5[0] = (((fRec3[1] <= 0.0f) & (fRec4[1] != fSlow1)) ? fSlow1 : fRec5[1]);
			int iTemp3 = int(std::min<float>(131072.0f, std::max<float>(0.0f, fRec4[0])));
			float fTemp4 = fVec0[(IOTA0 - iTemp3) & 262143];
			int iTemp5 = int(std::min<float>(131072.0f, std::max<float>(0.0f, fRec5[0])));
			fRec0[0] = fTemp4 + fRec3[0] * (fVec0[(IOTA0 - iTemp5) & 262143] - fTemp4);
			output0[i0] = FAUSTFLOAT(fRec0[0]);
			float fTemp6 = float(input1[i0]) + fTemp0 * fRec6[1];
			fVec1[IOTA0 & 262143] = fTemp6;
			float fTemp7 = fVec1[(IOTA0 - iTemp3) & 262143];
			fRec6[0] = fTemp7 + fRec3[0] * (fVec1[(IOTA0 - iTemp5) & 262143] - fTemp7);
			output1[i0] = FAUSTFLOAT(fRec6[0]);
			fRec1[1] = fRec1[0];
			IOTA0 = IOTA0 + 1;
			fRec2[1] = fRec2[0];
			fRec3[1] = fRec3[0];
			fRec4[1] = fRec4[0];
			fRec5[1] = fRec5[0];
			fRec0[1] = fRec0[0];
			fRec6[1] = fRec6[0];
		}
	}

};

#endif
