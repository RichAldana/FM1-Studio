/* ------------------------------------------------------------
name: "drive"
Code generated with Faust 2.70.3 (https://faust.grame.fr)
Compilation options: -lang cpp -i -nvi -ct 1 -cn DriveDsp -scn FaustDsp -es 1 -mcd 16 -mdd 1024 -mdy 33 -single -ftz 0
------------------------------------------------------------ */

#ifndef  __DriveDsp_H__
#define  __DriveDsp_H__

#ifndef FAUSTFLOAT
#define FAUSTFLOAT float
#endif 

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <math.h>

#ifndef FAUSTCLASS 
#define FAUSTCLASS DriveDsp
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

static float DriveDsp_faustpower2_f(float value) {
	return value * value;
}

class DriveDsp final : public FaustDsp {
	
 private:
	
	int fSampleRate;
	float fConst1;
	float fConst2;
	FAUSTFLOAT fHslider0;
	float fRec0[2];
	float fConst3;
	FAUSTFLOAT fHslider1;
	float fRec2[2];
	float fRec1[3];
	float fRec3[3];
	
 public:
	DriveDsp() {}

	void metadata(Meta* m) { 
		m->declare("compile_options", "-lang cpp -i -nvi -ct 1 -cn DriveDsp -scn FaustDsp -es 1 -mcd 16 -mdd 1024 -mdy 33 -single -ftz 0");
		m->declare("filename", "drive.dsp");
		m->declare("filters.lib/fir:author", "Julius O. Smith III");
		m->declare("filters.lib/fir:copyright", "Copyright (C) 2003-2019 by Julius O. Smith III <jos@ccrma.stanford.edu>");
		m->declare("filters.lib/fir:license", "MIT-style STK-4.3 license");
		m->declare("filters.lib/iir:author", "Julius O. Smith III");
		m->declare("filters.lib/iir:copyright", "Copyright (C) 2003-2019 by Julius O. Smith III <jos@ccrma.stanford.edu>");
		m->declare("filters.lib/iir:license", "MIT-style STK-4.3 license");
		m->declare("filters.lib/lowpass0_highpass1", "Copyright (C) 2003-2019 by Julius O. Smith III <jos@ccrma.stanford.edu>");
		m->declare("filters.lib/lowpass0_highpass1:author", "Julius O. Smith III");
		m->declare("filters.lib/lowpass:author", "Julius O. Smith III");
		m->declare("filters.lib/lowpass:copyright", "Copyright (C) 2003-2019 by Julius O. Smith III <jos@ccrma.stanford.edu>");
		m->declare("filters.lib/lowpass:license", "MIT-style STK-4.3 license");
		m->declare("filters.lib/name", "Faust Filters Library");
		m->declare("filters.lib/tf2:author", "Julius O. Smith III");
		m->declare("filters.lib/tf2:copyright", "Copyright (C) 2003-2019 by Julius O. Smith III <jos@ccrma.stanford.edu>");
		m->declare("filters.lib/tf2:license", "MIT-style STK-4.3 license");
		m->declare("filters.lib/tf2s:author", "Julius O. Smith III");
		m->declare("filters.lib/tf2s:copyright", "Copyright (C) 2003-2019 by Julius O. Smith III <jos@ccrma.stanford.edu>");
		m->declare("filters.lib/tf2s:license", "MIT-style STK-4.3 license");
		m->declare("filters.lib/version", "1.3.0");
		m->declare("maths.lib/author", "GRAME");
		m->declare("maths.lib/copyright", "GRAME");
		m->declare("maths.lib/license", "LGPL with exception");
		m->declare("maths.lib/name", "Faust Math Library");
		m->declare("maths.lib/version", "2.7.0");
		m->declare("misceffects.lib/cubicnl:author", "Julius O. Smith III");
		m->declare("misceffects.lib/cubicnl:license", "STK-4.3");
		m->declare("misceffects.lib/name", "Misc Effects Library");
		m->declare("misceffects.lib/version", "2.4.0");
		m->declare("name", "drive");
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
		fConst3 = 3.1415927f / fConst0;
	}
	
	void instanceResetUserInterface() {
		fHslider0 = FAUSTFLOAT(6e+03f);
		fHslider1 = FAUSTFLOAT(0.3f);
	}
	
	void instanceClear() {
		for (int l0 = 0; l0 < 2; l0 = l0 + 1) {
			fRec0[l0] = 0.0f;
		}
		for (int l1 = 0; l1 < 2; l1 = l1 + 1) {
			fRec2[l1] = 0.0f;
		}
		for (int l2 = 0; l2 < 3; l2 = l2 + 1) {
			fRec1[l2] = 0.0f;
		}
		for (int l3 = 0; l3 < 3; l3 = l3 + 1) {
			fRec3[l3] = 0.0f;
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
	
	DriveDsp* clone() {
		return new DriveDsp();
	}
	
	int getSampleRate() {
		return fSampleRate;
	}
	
	void buildUserInterface(UI* ui_interface) {
		ui_interface->openVerticalBox("drive");
		ui_interface->addHorizontalSlider("drive", &fHslider1, FAUSTFLOAT(0.3f), FAUSTFLOAT(0.0f), FAUSTFLOAT(1.0f), FAUSTFLOAT(0.001f));
		ui_interface->addHorizontalSlider("tone", &fHslider0, FAUSTFLOAT(6e+03f), FAUSTFLOAT(5e+02f), FAUSTFLOAT(1.2e+04f), FAUSTFLOAT(1.0f));
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
			fRec0[0] = fSlow0 + fConst2 * fRec0[1];
			float fTemp0 = std::tan(fConst3 * std::min<float>(1.2e+04f, std::max<float>(5e+02f, fRec0[0])));
			float fTemp1 = 1.0f / fTemp0;
			float fTemp2 = (fTemp1 + 1.4142135f) / fTemp0 + 1.0f;
			float fTemp3 = 1.0f - 1.0f / DriveDsp_faustpower2_f(fTemp0);
			float fTemp4 = (fTemp1 + -1.4142135f) / fTemp0 + 1.0f;
			fRec2[0] = fSlow1 + fConst2 * fRec2[1];
			float fTemp5 = std::pow(1e+01f, 2.0f * std::min<float>(1.0f, std::max<float>(0.0f, fRec2[0])));
			float fTemp6 = std::max<float>(-1.0f, std::min<float>(1.0f, float(input0[i0]) * fTemp5));
			fRec1[0] = fTemp6 * (1.0f - 0.33333334f * DriveDsp_faustpower2_f(fTemp6)) - (fRec1[2] * fTemp4 + 2.0f * fRec1[1] * fTemp3) / fTemp2;
			output0[i0] = FAUSTFLOAT(1.4f * ((fRec1[2] + fRec1[0] + 2.0f * fRec1[1]) / fTemp2));
			float fTemp7 = std::max<float>(-1.0f, std::min<float>(1.0f, float(input1[i0]) * fTemp5));
			fRec3[0] = fTemp7 * (1.0f - 0.33333334f * DriveDsp_faustpower2_f(fTemp7)) - (fTemp4 * fRec3[2] + 2.0f * fTemp3 * fRec3[1]) / fTemp2;
			output1[i0] = FAUSTFLOAT(1.4f * ((fRec3[2] + fRec3[0] + 2.0f * fRec3[1]) / fTemp2));
			fRec0[1] = fRec0[0];
			fRec2[1] = fRec2[0];
			fRec1[2] = fRec1[1];
			fRec1[1] = fRec1[0];
			fRec3[2] = fRec3[1];
			fRec3[1] = fRec3[0];
		}
	}

};

#endif
