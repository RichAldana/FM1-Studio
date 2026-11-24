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

/* link with : "" */
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
	float fConst1;
	float fConst2;
	float fConst3;
	FAUSTFLOAT fHslider2;
	float fRec6[2];
	float fRec5[2];
	float fRec3[2];
	float fRec2[2];
	float fRec0[2];
	float fRec12[2];
	float fRec10[2];
	float fRec9[2];
	float fRec7[2];
	
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
		m->declare("signals.lib/name", "Faust Signal Routing Library");
		m->declare("signals.lib/version", "1.5.0");
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
		float fConst0 = std::min<float>(1.92e+05f, std::max<float>(1.0f, float(fSampleRate)));
		fConst1 = 3.1415927f / fConst0;
		fConst2 = 44.1f / fConst0;
		fConst3 = 1.0f - fConst2;
	}
	
	void instanceResetUserInterface() {
		fHslider0 = FAUSTFLOAT(0.1f);
		fHslider1 = FAUSTFLOAT(1.6e+04f);
		fHslider2 = FAUSTFLOAT(0.0f);
	}
	
	void instanceClear() {
		for (int l0 = 0; l0 < 2; l0 = l0 + 1) {
			fRec6[l0] = 0.0f;
		}
		for (int l1 = 0; l1 < 2; l1 = l1 + 1) {
			fRec5[l1] = 0.0f;
		}
		for (int l2 = 0; l2 < 2; l2 = l2 + 1) {
			fRec3[l2] = 0.0f;
		}
		for (int l3 = 0; l3 < 2; l3 = l3 + 1) {
			fRec2[l3] = 0.0f;
		}
		for (int l4 = 0; l4 < 2; l4 = l4 + 1) {
			fRec0[l4] = 0.0f;
		}
		for (int l5 = 0; l5 < 2; l5 = l5 + 1) {
			fRec12[l5] = 0.0f;
		}
		for (int l6 = 0; l6 < 2; l6 = l6 + 1) {
			fRec10[l6] = 0.0f;
		}
		for (int l7 = 0; l7 < 2; l7 = l7 + 1) {
			fRec9[l7] = 0.0f;
		}
		for (int l8 = 0; l8 < 2; l8 = l8 + 1) {
			fRec7[l8] = 0.0f;
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
		ui_interface->addHorizontalSlider("cutoff", &fHslider1, FAUSTFLOAT(1.6e+04f), FAUSTFLOAT(4e+01f), FAUSTFLOAT(1.8e+04f), FAUSTFLOAT(1.0f));
		ui_interface->addHorizontalSlider("drive", &fHslider2, FAUSTFLOAT(0.0f), FAUSTFLOAT(0.0f), FAUSTFLOAT(1.0f), FAUSTFLOAT(0.001f));
		ui_interface->addHorizontalSlider("res", &fHslider0, FAUSTFLOAT(0.1f), FAUSTFLOAT(0.0f), FAUSTFLOAT(0.9f), FAUSTFLOAT(0.001f));
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
		float fSlow3 = std::tan(fConst1 * std::max<float>(float(fHslider1), 2e+01f));
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
		float fSlow20 = fConst2 * float(fHslider2);
		float fSlow21 = 1.0f - MoogDsp_faustpower2_f(fSlow19);
		float fSlow22 = std::sqrt(std::max<float>(0.0f, fSlow21));
		float fSlow23 = 1.0f - MoogDsp_faustpower2_f(fSlow18);
		float fSlow24 = std::sqrt(std::max<float>(0.0f, fSlow23));
		float fSlow25 = std::sqrt(fSlow21);
		float fSlow26 = 1.0f - fSlow17 / fSlow15;
		float fSlow27 = (1.0f - fSlow16 - 2.0f * fSlow18 * fSlow26) / (fSlow25 * std::sqrt(fSlow23));
		float fSlow28 = 2.0f * (fSlow26 / fSlow25);
		float fSlow29 = std::sqrt(std::max<float>(0.0f, 1.0f - MoogDsp_faustpower2_f(fSlow12))) / fSlow15;
		float fSlow30 = std::sqrt(std::max<float>(0.0f, 1.0f - MoogDsp_faustpower2_f(fSlow11)));
		float fSlow31 = 1.0f - fSlow10 / fSlow7;
		float fSlow32 = 2.0f * fSlow31;
		float fSlow33 = fSlow1 + fSlow5;
		float fSlow34 = (fSlow6 + (fSlow33 + -2.0f) / fSlow3 + fSlow2) / fSlow7;
		float fSlow35 = std::max<float>(-0.9999999f, std::min<float>(0.9999999f, 2.0f * (fSlow10 / (fSlow7 * (fSlow34 + 1.0f)))));
		float fSlow36 = (1.0f - (fSlow34 + 2.0f * fSlow31 * fSlow35)) / std::sqrt(1.0f - MoogDsp_faustpower2_f(fSlow35));
		float fSlow37 = std::max<float>(-0.9999999f, std::min<float>(0.9999999f, fSlow34));
		float fSlow38 = 1.0f - MoogDsp_faustpower2_f(fSlow37);
		float fSlow39 = 1.0f / std::sqrt(fSlow38);
		float fSlow40 = std::sqrt(std::max<float>(0.0f, fSlow38));
		float fSlow41 = fSlow14 + (fSlow33 + 2.0f) / fSlow3 + 1.0f;
		float fSlow42 = 1.0f - fSlow17 / fSlow41;
		float fSlow43 = 2.0f * fSlow42;
		float fSlow44 = (fSlow14 + (1.0f - (fSlow1 + (2.0f - fSlow5)) / fSlow3)) / fSlow41;
		float fSlow45 = std::max<float>(-0.9999999f, std::min<float>(0.9999999f, 2.0f * (fSlow17 / (fSlow41 * (fSlow44 + 1.0f)))));
		float fSlow46 = (1.0f - (fSlow44 + 2.0f * fSlow42 * fSlow45)) / std::sqrt(1.0f - MoogDsp_faustpower2_f(fSlow45));
		float fSlow47 = std::max<float>(-0.9999999f, std::min<float>(0.9999999f, fSlow44));
		float fSlow48 = 1.0f - MoogDsp_faustpower2_f(fSlow47);
		float fSlow49 = 1.0f / std::sqrt(fSlow48);
		float fSlow50 = std::sqrt(std::max<float>(0.0f, fSlow48));
		float fSlow51 = fSlow37 / fSlow41;
		float fSlow52 = 1.0f / fSlow7;
		for (int i0 = 0; i0 < count; i0 = i0 + 1) {
			fRec6[0] = fSlow20 + fConst3 * fRec6[1];
			float fTemp0 = std::min<float>(1.0f, std::max<float>(0.0f, fRec6[0]));
			float fTemp1 = 3.0f * fTemp0 + 1.0f;
			float fTemp2 = 6.0f * fTemp0 + 1.0f;
			float fTemp3 = tanhf(float(input0[i0]) * fTemp2) / fTemp1;
			float fTemp4 = fSlow22 * fTemp3 - fSlow19 * fRec3[1];
			fRec5[0] = fSlow24 * fTemp4 - fSlow18 * fRec5[1];
			fRec3[0] = fSlow18 * fTemp4 + fSlow24 * fRec5[1];
			float fRec4 = fRec5[0];
			float fTemp5 = fSlow29 * (fSlow19 * fTemp3 + fSlow22 * fRec3[1] + fSlow28 * fRec3[0] + fSlow27 * fRec4) - fSlow12 * fRec0[1];
			fRec2[0] = fSlow30 * fTemp5 - fSlow11 * fRec2[1];
			fRec0[0] = fSlow11 * fTemp5 + fSlow30 * fRec2[1];
			float fRec1 = fRec2[0];
			output0[i0] = FAUSTFLOAT(fSlow52 * (fSlow51 * (fSlow47 * fTemp3 + fSlow50 * fRec3[1] + fSlow49 * (fSlow46 * fRec4 + fSlow43 * fRec3[0])) + fSlow40 * fRec0[1] + fSlow39 * (fSlow36 * fRec1 + fSlow32 * fRec0[0])));
			float fTemp6 = tanhf(float(input1[i0]) * fTemp2) / fTemp1;
			float fTemp7 = fSlow22 * fTemp6 - fSlow19 * fRec10[1];
			fRec12[0] = fSlow24 * fTemp7 - fSlow18 * fRec12[1];
			fRec10[0] = fSlow18 * fTemp7 + fSlow24 * fRec12[1];
			float fRec11 = fRec12[0];
			float fTemp8 = fSlow29 * (fSlow19 * fTemp6 + fSlow22 * fRec10[1] + fSlow28 * fRec10[0] + fSlow27 * fRec11) - fSlow12 * fRec7[1];
			fRec9[0] = fSlow30 * fTemp8 - fSlow11 * fRec9[1];
			fRec7[0] = fSlow11 * fTemp8 + fSlow30 * fRec9[1];
			float fRec8 = fRec9[0];
			output1[i0] = FAUSTFLOAT(fSlow52 * (fSlow51 * (fSlow47 * fTemp6 + fSlow50 * fRec10[1] + fSlow49 * (fSlow46 * fRec11 + fSlow43 * fRec10[0])) + fSlow40 * fRec7[1] + fSlow39 * (fSlow36 * fRec8 + fSlow32 * fRec7[0])));
			fRec6[1] = fRec6[0];
			fRec5[1] = fRec5[0];
			fRec3[1] = fRec3[0];
			fRec2[1] = fRec2[0];
			fRec0[1] = fRec0[0];
			fRec12[1] = fRec12[0];
			fRec10[1] = fRec10[0];
			fRec9[1] = fRec9[0];
			fRec7[1] = fRec7[0];
		}
	}

};

#endif
