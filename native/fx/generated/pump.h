/* ------------------------------------------------------------
name: "pump"
Code generated with Faust 2.70.3 (https://faust.grame.fr)
Compilation options: -lang cpp -i -nvi -ct 1 -cn PumpDsp -scn FaustDsp -es 1 -mcd 16 -mdd 1024 -mdy 33 -single -ftz 0
------------------------------------------------------------ */

#ifndef  __PumpDsp_H__
#define  __PumpDsp_H__

#ifndef FAUSTFLOAT
#define FAUSTFLOAT float
#endif 

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <math.h>

#ifndef FAUSTCLASS 
#define FAUSTCLASS PumpDsp
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


class PumpDsp final : public FaustDsp {
	
 private:
	
	int fSampleRate;
	float fConst1;
	float fConst2;
	FAUSTFLOAT fHslider0;
	float fRec0[2];
	FAUSTFLOAT fHslider1;
	float fRec1[2];
	float fConst3;
	float fConst4;
	float fConst5;
	float fRec3[2];
	float fConst6;
	float fRec2[2];
	
 public:
	PumpDsp() {}

	void metadata(Meta* m) { 
		m->declare("analyzers.lib/amp_follower_ar:author", "Jonatan Liljedahl, revised by Romain Michon");
		m->declare("analyzers.lib/name", "Faust Analyzer Library");
		m->declare("analyzers.lib/version", "1.2.0");
		m->declare("basics.lib/name", "Faust Basic Element Library");
		m->declare("basics.lib/tabulateNd", "Copyright (C) 2023 Bart Brouns <bart@magnetophon.nl>");
		m->declare("basics.lib/version", "1.12.0");
		m->declare("compile_options", "-lang cpp -i -nvi -ct 1 -cn PumpDsp -scn FaustDsp -es 1 -mcd 16 -mdd 1024 -mdy 33 -single -ftz 0");
		m->declare("compressors.lib/compression_gain_mono:author", "Julius O. Smith III");
		m->declare("compressors.lib/compression_gain_mono:copyright", "Copyright (C) 2014-2020 by Julius O. Smith III <jos@ccrma.stanford.edu>");
		m->declare("compressors.lib/compression_gain_mono:license", "MIT-style STK-4.3 license");
		m->declare("compressors.lib/compressor_stereo:author", "Julius O. Smith III");
		m->declare("compressors.lib/compressor_stereo:copyright", "Copyright (C) 2014-2020 by Julius O. Smith III <jos@ccrma.stanford.edu>");
		m->declare("compressors.lib/compressor_stereo:license", "MIT-style STK-4.3 license");
		m->declare("compressors.lib/name", "Faust Compressor Effect Library");
		m->declare("compressors.lib/version", "1.6.0");
		m->declare("filename", "pump.dsp");
		m->declare("maths.lib/author", "GRAME");
		m->declare("maths.lib/copyright", "GRAME");
		m->declare("maths.lib/license", "LGPL with exception");
		m->declare("maths.lib/name", "Faust Math Library");
		m->declare("maths.lib/version", "2.7.0");
		m->declare("name", "pump");
		m->declare("platform.lib/name", "Generic Platform Library");
		m->declare("platform.lib/version", "1.3.0");
		m->declare("signals.lib/name", "Faust Signal Routing Library");
		m->declare("signals.lib/onePoleSwitching:author", "Jonatan Liljedahl, revised by Dario Sanfilippo");
		m->declare("signals.lib/onePoleSwitching:licence", "STK-4.3");
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
		fConst3 = std::exp(-(1e+03f / fConst0));
		fConst4 = std::exp(-(8.333333f / fConst0));
		fConst5 = std::exp(-(5e+02f / fConst0));
		fConst6 = 1.0f - fConst3;
	}
	
	void instanceResetUserInterface() {
		fHslider0 = FAUSTFLOAT(-2e+01f);
		fHslider1 = FAUSTFLOAT(4.0f);
	}
	
	void instanceClear() {
		for (int l0 = 0; l0 < 2; l0 = l0 + 1) {
			fRec0[l0] = 0.0f;
		}
		for (int l1 = 0; l1 < 2; l1 = l1 + 1) {
			fRec1[l1] = 0.0f;
		}
		for (int l2 = 0; l2 < 2; l2 = l2 + 1) {
			fRec3[l2] = 0.0f;
		}
		for (int l3 = 0; l3 < 2; l3 = l3 + 1) {
			fRec2[l3] = 0.0f;
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
	
	PumpDsp* clone() {
		return new PumpDsp();
	}
	
	int getSampleRate() {
		return fSampleRate;
	}
	
	void buildUserInterface(UI* ui_interface) {
		ui_interface->openVerticalBox("pump");
		ui_interface->addHorizontalSlider("ratio", &fHslider1, FAUSTFLOAT(4.0f), FAUSTFLOAT(1.0f), FAUSTFLOAT(2e+01f), FAUSTFLOAT(0.01f));
		ui_interface->addHorizontalSlider("thresh", &fHslider0, FAUSTFLOAT(-2e+01f), FAUSTFLOAT(-4e+01f), FAUSTFLOAT(0.0f), FAUSTFLOAT(0.01f));
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
			float fTemp0 = std::min<float>(0.0f, std::max<float>(-4e+01f, fRec0[0]));
			fRec1[0] = fSlow1 + fConst2 * fRec1[1];
			float fTemp1 = std::min<float>(2e+01f, std::max<float>(1.0f, fRec1[0]));
			float fTemp2 = std::pow(1e+01f, -0.025f * (1.0f - 1.0f / fTemp1) * fTemp0);
			float fTemp3 = float(input0[i0]);
			float fTemp4 = float(input1[i0]);
			float fTemp5 = std::fabs(std::fabs(fTemp4) + std::fabs(fTemp3));
			float fTemp6 = ((fTemp5 > fRec3[1]) ? fConst5 : fConst4);
			fRec3[0] = fTemp5 * (1.0f - fTemp6) + fRec3[1] * fTemp6;
			fRec2[0] = fConst6 * std::max<float>(2e+01f * std::log10(std::max<float>(1.1754944e-38f, fRec3[0])) - fTemp0, 0.0f) * (1.0f / std::max<float>(1.1920929e-07f, fTemp1) + -1.0f) + fConst3 * fRec2[1];
			float fTemp7 = std::pow(1e+01f, 0.05f * fRec2[0]);
			output0[i0] = FAUSTFLOAT(fTemp3 * fTemp7 * fTemp2);
			output1[i0] = FAUSTFLOAT(fTemp4 * fTemp7 * fTemp2);
			fRec0[1] = fRec0[0];
			fRec1[1] = fRec1[0];
			fRec3[1] = fRec3[0];
			fRec2[1] = fRec2[0];
		}
	}

};

#endif
