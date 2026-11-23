/* ------------------------------------------------------------
name: "moog"
Code generated with Faust 2.70.3 (https://faust.grame.fr)
Compilation options: -lang cpp -i -nvi -ct 1 -cn MoogDsp -scn FaustDsp -es 1 -mcd 16 -mdd 1024 -mdy 33 -single -ftz 0
------------------------------------------------------------ */

#ifndef  __MoogDsp_H__
#define  __MoogDsp_H__

#ifndef FAUSTFLOAT
#define FAUSTFLOAT float
#endif 

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <math.h>

#ifndef FAUSTCLASS 
#define FAUSTCLASS MoogDsp
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

static float MoogDsp_faustpower2_f(float value) {
	return value * value;
}

class MoogDsp final : public FaustDsp {
	
 private:
	
	FAUSTFLOAT fHslider0;
	FAUSTFLOAT fHslider1;
	int fSampleRate;
	float fConst0;
	float fRec5[2];
	float fRec3[2];
	float fRec2[2];
	float fRec0[2];
	float fRec11[2];
	float fRec9[2];
	float fRec8[2];
	float fRec6[2];
	
 public:
	MoogDsp() {}

	void metadata(Meta* m) { 
		m->declare("basics.lib/name", "Faust Basic Element Library");
		m->declare("basics.lib/tabulateNd", "Copyright (C) 2023 Bart Brouns <bart@magnetophon.nl>");
		m->declare("basics.lib/version", "1.12.0");
		m->declare("compile_options", "-lang cpp -i -nvi -ct 1 -cn MoogDsp -scn FaustDsp -es 1 -mcd 16 -mdd 1024 -mdy 33 -single -ftz 0");
		m->declare("filename", "moog.dsp");
		m->declare("filters.lib/allpassnnlt:author", "Julius O. Smith III");
		m->declare("filters.lib/allpassnnlt:copyright", "Copyright (C) 2003-2019 by Julius O. Smith III <jos@ccrma.stanford.edu>");
		m->declare("filters.lib/allpassnnlt:license", "MIT-style STK-4.3 license");
		m->declare("filters.lib/lowpass0_highpass1", "Copyright (C) 2003-2019 by Julius O. Smith III <jos@ccrma.stanford.edu>");
		m->declare("filters.lib/name", "Faust Filters Library");
		m->declare("filters.lib/tf2np:author", "Julius O. Smith III");
		m->declare("filters.lib/tf2np:copyright", "Copyright (C) 2003-2019 by Julius O. Smith III <jos@ccrma.stanford.edu>");
		m->declare("filters.lib/tf2np:license", "MIT-style STK-4.3 license");
		m->declare("filters.lib/version", "1.3.0");
		m->declare("maths.lib/author", "GRAME");
		m->declare("maths.lib/copyright", "GRAME");
		m->declare("maths.lib/license", "LGPL with exception");
		m->declare("maths.lib/name", "Faust Math Library");
		m->declare("maths.lib/version", "2.7.0");
		m->declare("name", "moog");
		m->declare("platform.lib/name", "Generic Platform Library");
		m->declare("platform.lib/version", "1.3.0");
		m->declare("vaeffects.lib/moog_vcf_2bn:author", "Julius O. Smith III");
		m->declare("vaeffects.lib/moog_vcf_2bn:copyright", "Copyright (C) 2003-2019 by Julius O. Smith III <jos@ccrma.stanford.edu>");
		m->declare("vaeffects.lib/moog_vcf_2bn:license", "MIT-style STK-4.3 license");
		m->declare("vaeffects.lib/name", "Faust Virtual Analog Filter Effect Library");
		m->declare("vaeffects.lib/version", "1.2.1");
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
		fConst0 = 3.1415927f / std::min<float>(1.92e+05f, std::max<float>(1.0f, float(fSampleRate)));
	}
	
	void instanceResetUserInterface() {
		fHslider0 = FAUSTFLOAT(0.0f);
		fHslider1 = FAUSTFLOAT(1.8e+04f);
	}
	
	void instanceClear() {
		for (int l0 = 0; l0 < 2; l0 = l0 + 1) {
			fRec5[l0] = 0.0f;
		}
		for (int l1 = 0; l1 < 2; l1 = l1 + 1) {
			fRec3[l1] = 0.0f;
		}
		for (int l2 = 0; l2 < 2; l2 = l2 + 1) {
			fRec2[l2] = 0.0f;
		}
		for (int l3 = 0; l3 < 2; l3 = l3 + 1) {
			fRec0[l3] = 0.0f;
		}
		for (int l4 = 0; l4 < 2; l4 = l4 + 1) {
			fRec11[l4] = 0.0f;
		}
		for (int l5 = 0; l5 < 2; l5 = l5 + 1) {
			fRec9[l5] = 0.0f;
		}
		for (int l6 = 0; l6 < 2; l6 = l6 + 1) {
			fRec8[l6] = 0.0f;
		}
		for (int l7 = 0; l7 < 2; l7 = l7 + 1) {
			fRec6[l7] = 0.0f;
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
	
	MoogDsp* clone() {
		return new MoogDsp();
	}
	
	int getSampleRate() {
		return fSampleRate;
	}
	
	void buildUserInterface(UI* ui_interface) {
		ui_interface->openVerticalBox("moog");
		ui_interface->addHorizontalSlider("cutoff", &fHslider1, FAUSTFLOAT(1.8e+04f), FAUSTFLOAT(4e+01f), FAUSTFLOAT(1.8e+04f), FAUSTFLOAT(1.0f));
		ui_interface->addHorizontalSlider("res", &fHslider0, FAUSTFLOAT(0.0f), FAUSTFLOAT(0.0f), FAUSTFLOAT(0.9f), FAUSTFLOAT(0.001f));
		ui_interface->closeBox();
	}
	
	void compute(int count, FAUSTFLOAT** RESTRICT inputs, FAUSTFLOAT** RESTRICT outputs) {
		FAUSTFLOAT* input0 = inputs[0];
		FAUSTFLOAT* input1 = inputs[1];
		FAUSTFLOAT* output0 = outputs[0];
		FAUSTFLOAT* output1 = outputs[1];
		float fSlow0 = float(fHslider0);
		float fSlow1 = 1.99998f * fSlow0;
		float fSlow2 = 1.0f - fSlow1;
		float fSlow3 = std::tan(fConst0 * std::max<float>(float(fHslider1), 2e+01f));
		float fSlow4 = 2.0f - fSlow1;
		float fSlow5 = 1.0f / fSlow3;
		float fSlow6 = MoogDsp_faustpower2_f(1.4141995f * fSlow0);
		float fSlow7 = fSlow6 + (fSlow5 + fSlow4) / fSlow3 + fSlow2;
		float fSlow8 = (fSlow6 + (fSlow5 - fSlow4) / fSlow3 + fSlow2) / fSlow7;
		float fSlow9 = 1.0f / MoogDsp_faustpower2_f(fSlow3);
		float fSlow10 = fSlow6 + (1.0f - (fSlow1 + fSlow9));
		float fSlow11 = std::max<float>(-0.9999999f, std::min<float>(0.9999999f, 2.0f * (fSlow10 / (fSlow7 * (fSlow8 + 1.0f)))));
		float fSlow12 = std::max<float>(-0.9999999f, std::min<float>(0.9999999f, fSlow8));
		float fSlow13 = fSlow1 + 2.0f;
		float fSlow14 = fSlow1 + fSlow6;
		float fSlow15 = fSlow14 + (fSlow13 + fSlow5) / fSlow3 + 1.0f;
		float fSlow16 = (fSlow14 + (1.0f - (fSlow13 - fSlow5) / fSlow3)) / fSlow15;
		float fSlow17 = fSlow14 + (1.0f - fSlow9);
		float fSlow18 = std::max<float>(-0.9999999f, std::min<float>(0.9999999f, 2.0f * (fSlow17 / (fSlow15 * (fSlow16 + 1.0f)))));
		float fSlow19 = std::max<float>(-0.9999999f, std::min<float>(0.9999999f, fSlow16));
		float fSlow20 = 1.0f - MoogDsp_faustpower2_f(fSlow19);
		float fSlow21 = std::sqrt(std::max<float>(0.0f, fSlow20));
		float fSlow22 = 1.0f - MoogDsp_faustpower2_f(fSlow18);
		float fSlow23 = std::sqrt(std::max<float>(0.0f, fSlow22));
		float fSlow24 = std::sqrt(fSlow20);
		float fSlow25 = 1.0f - fSlow17 / fSlow15;
		float fSlow26 = (1.0f - fSlow16 - 2.0f * fSlow18 * fSlow25) / (fSlow24 * std::sqrt(fSlow22));
		float fSlow27 = 2.0f * (fSlow25 / fSlow24);
		float fSlow28 = std::sqrt(std::max<float>(0.0f, 1.0f - MoogDsp_faustpower2_f(fSlow12))) / fSlow15;
		float fSlow29 = std::sqrt(std::max<float>(0.0f, 1.0f - MoogDsp_faustpower2_f(fSlow11)));
		float fSlow30 = 1.0f - fSlow10 / fSlow7;
		float fSlow31 = 2.0f * fSlow30;
		float fSlow32 = fSlow1 + fSlow5;
		float fSlow33 = (fSlow6 + (fSlow32 + -2.0f) / fSlow3 + fSlow2) / fSlow7;
		float fSlow34 = std::max<float>(-0.9999999f, std::min<float>(0.9999999f, 2.0f * (fSlow10 / (fSlow7 * (fSlow33 + 1.0f)))));
		float fSlow35 = (1.0f - (fSlow33 + 2.0f * fSlow30 * fSlow34)) / std::sqrt(1.0f - MoogDsp_faustpower2_f(fSlow34));
		float fSlow36 = std::max<float>(-0.9999999f, std::min<float>(0.9999999f, fSlow33));
		float fSlow37 = 1.0f - MoogDsp_faustpower2_f(fSlow36);
		float fSlow38 = 1.0f / std::sqrt(fSlow37);
		float fSlow39 = std::sqrt(std::max<float>(0.0f, fSlow37));
		float fSlow40 = fSlow14 + (fSlow32 + 2.0f) / fSlow3 + 1.0f;
		float fSlow41 = 1.0f - fSlow17 / fSlow40;
		float fSlow42 = 2.0f * fSlow41;
		float fSlow43 = (fSlow14 + (1.0f - (fSlow1 + (2.0f - fSlow5)) / fSlow3)) / fSlow40;
		float fSlow44 = std::max<float>(-0.9999999f, std::min<float>(0.9999999f, 2.0f * (fSlow17 / (fSlow40 * (fSlow43 + 1.0f)))));
		float fSlow45 = (1.0f - (fSlow43 + 2.0f * fSlow41 * fSlow44)) / std::sqrt(1.0f - MoogDsp_faustpower2_f(fSlow44));
		float fSlow46 = std::max<float>(-0.9999999f, std::min<float>(0.9999999f, fSlow43));
		float fSlow47 = 1.0f - MoogDsp_faustpower2_f(fSlow46);
		float fSlow48 = 1.0f / std::sqrt(fSlow47);
		float fSlow49 = std::sqrt(std::max<float>(0.0f, fSlow47));
		float fSlow50 = fSlow36 / fSlow40;
		float fSlow51 = 1.0f / fSlow7;
		for (int i0 = 0; i0 < count; i0 = i0 + 1) {
			float fTemp0 = float(input0[i0]);
			float fTemp1 = fSlow21 * fTemp0 - fSlow19 * fRec3[1];
			fRec5[0] = fSlow23 * fTemp1 - fSlow18 * fRec5[1];
			fRec3[0] = fSlow18 * fTemp1 + fSlow23 * fRec5[1];
			float fRec4 = fRec5[0];
			float fTemp2 = fSlow28 * (fSlow19 * fTemp0 + fSlow21 * fRec3[1] + fSlow27 * fRec3[0] + fSlow26 * fRec4) - fSlow12 * fRec0[1];
			fRec2[0] = fSlow29 * fTemp2 - fSlow11 * fRec2[1];
			fRec0[0] = fSlow11 * fTemp2 + fSlow29 * fRec2[1];
			float fRec1 = fRec2[0];
			output0[i0] = FAUSTFLOAT(fSlow51 * (fSlow50 * (fSlow46 * fTemp0 + fSlow49 * fRec3[1] + fSlow48 * (fSlow45 * fRec4 + fSlow42 * fRec3[0])) + fSlow39 * fRec0[1] + fSlow38 * (fSlow35 * fRec1 + fSlow31 * fRec0[0])));
			float fTemp3 = float(input1[i0]);
			float fTemp4 = fSlow21 * fTemp3 - fSlow19 * fRec9[1];
			fRec11[0] = fSlow23 * fTemp4 - fSlow18 * fRec11[1];
			fRec9[0] = fSlow18 * fTemp4 + fSlow23 * fRec11[1];
			float fRec10 = fRec11[0];
			float fTemp5 = fSlow28 * (fSlow19 * fTemp3 + fSlow21 * fRec9[1] + fSlow27 * fRec9[0] + fSlow26 * fRec10) - fSlow12 * fRec6[1];
			fRec8[0] = fSlow29 * fTemp5 - fSlow11 * fRec8[1];
			fRec6[0] = fSlow11 * fTemp5 + fSlow29 * fRec8[1];
			float fRec7 = fRec8[0];
			output1[i0] = FAUSTFLOAT(fSlow51 * (fSlow50 * (fSlow46 * fTemp3 + fSlow49 * fRec9[1] + fSlow48 * (fSlow45 * fRec10 + fSlow42 * fRec9[0])) + fSlow39 * fRec6[1] + fSlow38 * (fSlow35 * fRec7 + fSlow31 * fRec6[0])));
			fRec5[1] = fRec5[0];
			fRec3[1] = fRec3[0];
			fRec2[1] = fRec2[0];
			fRec0[1] = fRec0[0];
			fRec11[1] = fRec11[0];
			fRec9[1] = fRec9[0];
			fRec8[1] = fRec8[0];
			fRec6[1] = fRec6[0];
		}
	}

};

#endif
