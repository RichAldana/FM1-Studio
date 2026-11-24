/* ------------------------------------------------------------
name: "zita"
Code generated with Faust 2.70.3 (https://faust.grame.fr)
Compilation options: -lang cpp -i -nvi -ct 1 -cn ZitaDsp -scn FaustDsp -es 1 -mcd 16 -mdd 1024 -mdy 33 -single -ftz 0
------------------------------------------------------------ */

#ifndef  __ZitaDsp_H__
#define  __ZitaDsp_H__

#ifndef FAUSTFLOAT
#define FAUSTFLOAT float
#endif 

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <math.h>

#ifndef FAUSTCLASS 
#define FAUSTCLASS ZitaDsp
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

static float ZitaDsp_faustpower2_f(float value) {
	return value * value;
}

class ZitaDsp final : public FaustDsp {
	
 private:
	
	int fSampleRate;
	float fConst1;
	float fConst2;
	FAUSTFLOAT fHslider0;
	float fRec11[2];
	float fConst5;
	FAUSTFLOAT fHslider1;
	float fRec12[2];
	float fConst6;
	float fConst7;
	float fConst9;
	float fConst10;
	float fRec13[2];
	float fRec10[2];
	int IOTA0;
	float fVec0[16384];
	int iConst12;
	float fVec1[16384];
	int iConst13;
	float fVec2[4096];
	int iConst14;
	float fRec8[2];
	float fConst17;
	float fConst18;
	float fRec17[2];
	float fRec16[2];
	float fVec3[16384];
	int iConst20;
	float fVec4[2048];
	int iConst21;
	float fRec14[2];
	float fConst24;
	float fConst25;
	float fRec21[2];
	float fRec20[2];
	float fVec5[16384];
	int iConst27;
	float fVec6[4096];
	int iConst28;
	float fRec18[2];
	float fConst31;
	float fConst32;
	float fRec25[2];
	float fRec24[2];
	float fVec7[16384];
	int iConst34;
	float fVec8[2048];
	int iConst35;
	float fRec22[2];
	float fConst38;
	float fConst39;
	float fRec29[2];
	float fRec28[2];
	float fVec9[32768];
	int iConst41;
	float fVec10[16384];
	float fVec11[4096];
	int iConst42;
	float fRec26[2];
	float fConst45;
	float fConst46;
	float fRec33[2];
	float fRec32[2];
	float fVec12[16384];
	int iConst48;
	float fVec13[4096];
	int iConst49;
	float fRec30[2];
	float fConst52;
	float fConst53;
	float fRec37[2];
	float fRec36[2];
	float fVec14[32768];
	int iConst55;
	float fVec15[4096];
	int iConst56;
	float fRec34[2];
	float fConst59;
	float fConst60;
	float fRec41[2];
	float fRec40[2];
	float fVec16[32768];
	int iConst62;
	float fVec17[2048];
	int iConst63;
	float fRec38[2];
	float fRec0[3];
	float fRec1[3];
	float fRec2[3];
	float fRec3[3];
	float fRec4[3];
	float fRec5[3];
	float fRec6[3];
	float fRec7[3];
	
 public:
	ZitaDsp() {}

	void metadata(Meta* m) { 
		m->declare("basics.lib/name", "Faust Basic Element Library");
		m->declare("basics.lib/tabulateNd", "Copyright (C) 2023 Bart Brouns <bart@magnetophon.nl>");
		m->declare("basics.lib/version", "1.12.0");
		m->declare("compile_options", "-lang cpp -i -nvi -ct 1 -cn ZitaDsp -scn FaustDsp -es 1 -mcd 16 -mdd 1024 -mdy 33 -single -ftz 0");
		m->declare("delays.lib/name", "Faust Delay Library");
		m->declare("delays.lib/version", "1.1.0");
		m->declare("filename", "zita.dsp");
		m->declare("filters.lib/allpass_comb:author", "Julius O. Smith III");
		m->declare("filters.lib/allpass_comb:copyright", "Copyright (C) 2003-2019 by Julius O. Smith III <jos@ccrma.stanford.edu>");
		m->declare("filters.lib/allpass_comb:license", "MIT-style STK-4.3 license");
		m->declare("filters.lib/lowpass0_highpass1", "MIT-style STK-4.3 license");
		m->declare("filters.lib/lowpass0_highpass1:author", "Julius O. Smith III");
		m->declare("filters.lib/lowpass:author", "Julius O. Smith III");
		m->declare("filters.lib/lowpass:copyright", "Copyright (C) 2003-2019 by Julius O. Smith III <jos@ccrma.stanford.edu>");
		m->declare("filters.lib/lowpass:license", "MIT-style STK-4.3 license");
		m->declare("filters.lib/name", "Faust Filters Library");
		m->declare("filters.lib/tf1:author", "Julius O. Smith III");
		m->declare("filters.lib/tf1:copyright", "Copyright (C) 2003-2019 by Julius O. Smith III <jos@ccrma.stanford.edu>");
		m->declare("filters.lib/tf1:license", "MIT-style STK-4.3 license");
		m->declare("filters.lib/tf1s:author", "Julius O. Smith III");
		m->declare("filters.lib/tf1s:copyright", "Copyright (C) 2003-2019 by Julius O. Smith III <jos@ccrma.stanford.edu>");
		m->declare("filters.lib/tf1s:license", "MIT-style STK-4.3 license");
		m->declare("filters.lib/version", "1.3.0");
		m->declare("maths.lib/author", "GRAME");
		m->declare("maths.lib/copyright", "GRAME");
		m->declare("maths.lib/license", "LGPL with exception");
		m->declare("maths.lib/name", "Faust Math Library");
		m->declare("maths.lib/version", "2.7.0");
		m->declare("name", "zita");
		m->declare("platform.lib/name", "Generic Platform Library");
		m->declare("platform.lib/version", "1.3.0");
		m->declare("reverbs.lib/name", "Faust Reverb Library");
		m->declare("reverbs.lib/version", "1.2.1");
		m->declare("routes.lib/hadamard:author", "Remy Muller, revised by Romain Michon");
		m->declare("routes.lib/name", "Faust Signal Routing Library");
		m->declare("routes.lib/version", "1.2.0");
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
		float fConst3 = std::floor(0.174713f * fConst0 + 0.5f);
		float fConst4 = fConst3 / fConst0;
		fConst5 = 8.634694f * fConst4;
		fConst6 = 6.2831855f / fConst0;
		fConst7 = 6.9077554f * fConst4;
		float fConst8 = 1.0f / std::tan(628.31854f / fConst0);
		fConst9 = 1.0f - fConst8;
		fConst10 = 1.0f / (fConst8 + 1.0f);
		float fConst11 = std::floor(0.022904f * fConst0 + 0.5f);
		iConst12 = int(std::min<float>(8192.0f, std::max<float>(0.0f, fConst3 - fConst11)));
		iConst13 = int(std::min<float>(8192.0f, std::max<float>(0.0f, 0.06f * fConst0)));
		iConst14 = int(std::min<float>(2048.0f, std::max<float>(0.0f, fConst11 + -1.0f)));
		float fConst15 = std::floor(0.153129f * fConst0 + 0.5f);
		float fConst16 = fConst15 / fConst0;
		fConst17 = 8.634694f * fConst16;
		fConst18 = 6.9077554f * fConst16;
		float fConst19 = std::floor(0.020346f * fConst0 + 0.5f);
		iConst20 = int(std::min<float>(8192.0f, std::max<float>(0.0f, fConst15 - fConst19)));
		iConst21 = int(std::min<float>(1024.0f, std::max<float>(0.0f, fConst19 + -1.0f)));
		float fConst22 = std::floor(0.127837f * fConst0 + 0.5f);
		float fConst23 = fConst22 / fConst0;
		fConst24 = 8.634694f * fConst23;
		fConst25 = 6.9077554f * fConst23;
		float fConst26 = std::floor(0.031604f * fConst0 + 0.5f);
		iConst27 = int(std::min<float>(8192.0f, std::max<float>(0.0f, fConst22 - fConst26)));
		iConst28 = int(std::min<float>(2048.0f, std::max<float>(0.0f, fConst26 + -1.0f)));
		float fConst29 = std::floor(0.125f * fConst0 + 0.5f);
		float fConst30 = fConst29 / fConst0;
		fConst31 = 8.634694f * fConst30;
		fConst32 = 6.9077554f * fConst30;
		float fConst33 = std::floor(0.013458f * fConst0 + 0.5f);
		iConst34 = int(std::min<float>(8192.0f, std::max<float>(0.0f, fConst29 - fConst33)));
		iConst35 = int(std::min<float>(1024.0f, std::max<float>(0.0f, fConst33 + -1.0f)));
		float fConst36 = std::floor(0.210389f * fConst0 + 0.5f);
		float fConst37 = fConst36 / fConst0;
		fConst38 = 8.634694f * fConst37;
		fConst39 = 6.9077554f * fConst37;
		float fConst40 = std::floor(0.024421f * fConst0 + 0.5f);
		iConst41 = int(std::min<float>(16384.0f, std::max<float>(0.0f, fConst36 - fConst40)));
		iConst42 = int(std::min<float>(2048.0f, std::max<float>(0.0f, fConst40 + -1.0f)));
		float fConst43 = std::floor(0.192303f * fConst0 + 0.5f);
		float fConst44 = fConst43 / fConst0;
		fConst45 = 8.634694f * fConst44;
		fConst46 = 6.9077554f * fConst44;
		float fConst47 = std::floor(0.029291f * fConst0 + 0.5f);
		iConst48 = int(std::min<float>(8192.0f, std::max<float>(0.0f, fConst43 - fConst47)));
		iConst49 = int(std::min<float>(2048.0f, std::max<float>(0.0f, fConst47 + -1.0f)));
		float fConst50 = std::floor(0.256891f * fConst0 + 0.5f);
		float fConst51 = fConst50 / fConst0;
		fConst52 = 8.634694f * fConst51;
		fConst53 = 6.9077554f * fConst51;
		float fConst54 = std::floor(0.027333f * fConst0 + 0.5f);
		iConst55 = int(std::min<float>(16384.0f, std::max<float>(0.0f, fConst50 - fConst54)));
		iConst56 = int(std::min<float>(2048.0f, std::max<float>(0.0f, fConst54 + -1.0f)));
		float fConst57 = std::floor(0.219991f * fConst0 + 0.5f);
		float fConst58 = fConst57 / fConst0;
		fConst59 = 8.634694f * fConst58;
		fConst60 = 6.9077554f * fConst58;
		float fConst61 = std::floor(0.019123f * fConst0 + 0.5f);
		iConst62 = int(std::min<float>(16384.0f, std::max<float>(0.0f, fConst57 - fConst61)));
		iConst63 = int(std::min<float>(1024.0f, std::max<float>(0.0f, fConst61 + -1.0f)));
	}
	
	void instanceResetUserInterface() {
		fHslider0 = FAUSTFLOAT(3.0f);
		fHslider1 = FAUSTFLOAT(6e+03f);
	}
	
	void instanceClear() {
		for (int l0 = 0; l0 < 2; l0 = l0 + 1) {
			fRec11[l0] = 0.0f;
		}
		for (int l1 = 0; l1 < 2; l1 = l1 + 1) {
			fRec12[l1] = 0.0f;
		}
		for (int l2 = 0; l2 < 2; l2 = l2 + 1) {
			fRec13[l2] = 0.0f;
		}
		for (int l3 = 0; l3 < 2; l3 = l3 + 1) {
			fRec10[l3] = 0.0f;
		}
		IOTA0 = 0;
		for (int l4 = 0; l4 < 16384; l4 = l4 + 1) {
			fVec0[l4] = 0.0f;
		}
		for (int l5 = 0; l5 < 16384; l5 = l5 + 1) {
			fVec1[l5] = 0.0f;
		}
		for (int l6 = 0; l6 < 4096; l6 = l6 + 1) {
			fVec2[l6] = 0.0f;
		}
		for (int l7 = 0; l7 < 2; l7 = l7 + 1) {
			fRec8[l7] = 0.0f;
		}
		for (int l8 = 0; l8 < 2; l8 = l8 + 1) {
			fRec17[l8] = 0.0f;
		}
		for (int l9 = 0; l9 < 2; l9 = l9 + 1) {
			fRec16[l9] = 0.0f;
		}
		for (int l10 = 0; l10 < 16384; l10 = l10 + 1) {
			fVec3[l10] = 0.0f;
		}
		for (int l11 = 0; l11 < 2048; l11 = l11 + 1) {
			fVec4[l11] = 0.0f;
		}
		for (int l12 = 0; l12 < 2; l12 = l12 + 1) {
			fRec14[l12] = 0.0f;
		}
		for (int l13 = 0; l13 < 2; l13 = l13 + 1) {
			fRec21[l13] = 0.0f;
		}
		for (int l14 = 0; l14 < 2; l14 = l14 + 1) {
			fRec20[l14] = 0.0f;
		}
		for (int l15 = 0; l15 < 16384; l15 = l15 + 1) {
			fVec5[l15] = 0.0f;
		}
		for (int l16 = 0; l16 < 4096; l16 = l16 + 1) {
			fVec6[l16] = 0.0f;
		}
		for (int l17 = 0; l17 < 2; l17 = l17 + 1) {
			fRec18[l17] = 0.0f;
		}
		for (int l18 = 0; l18 < 2; l18 = l18 + 1) {
			fRec25[l18] = 0.0f;
		}
		for (int l19 = 0; l19 < 2; l19 = l19 + 1) {
			fRec24[l19] = 0.0f;
		}
		for (int l20 = 0; l20 < 16384; l20 = l20 + 1) {
			fVec7[l20] = 0.0f;
		}
		for (int l21 = 0; l21 < 2048; l21 = l21 + 1) {
			fVec8[l21] = 0.0f;
		}
		for (int l22 = 0; l22 < 2; l22 = l22 + 1) {
			fRec22[l22] = 0.0f;
		}
		for (int l23 = 0; l23 < 2; l23 = l23 + 1) {
			fRec29[l23] = 0.0f;
		}
		for (int l24 = 0; l24 < 2; l24 = l24 + 1) {
			fRec28[l24] = 0.0f;
		}
		for (int l25 = 0; l25 < 32768; l25 = l25 + 1) {
			fVec9[l25] = 0.0f;
		}
		for (int l26 = 0; l26 < 16384; l26 = l26 + 1) {
			fVec10[l26] = 0.0f;
		}
		for (int l27 = 0; l27 < 4096; l27 = l27 + 1) {
			fVec11[l27] = 0.0f;
		}
		for (int l28 = 0; l28 < 2; l28 = l28 + 1) {
			fRec26[l28] = 0.0f;
		}
		for (int l29 = 0; l29 < 2; l29 = l29 + 1) {
			fRec33[l29] = 0.0f;
		}
		for (int l30 = 0; l30 < 2; l30 = l30 + 1) {
			fRec32[l30] = 0.0f;
		}
		for (int l31 = 0; l31 < 16384; l31 = l31 + 1) {
			fVec12[l31] = 0.0f;
		}
		for (int l32 = 0; l32 < 4096; l32 = l32 + 1) {
			fVec13[l32] = 0.0f;
		}
		for (int l33 = 0; l33 < 2; l33 = l33 + 1) {
			fRec30[l33] = 0.0f;
		}
		for (int l34 = 0; l34 < 2; l34 = l34 + 1) {
			fRec37[l34] = 0.0f;
		}
		for (int l35 = 0; l35 < 2; l35 = l35 + 1) {
			fRec36[l35] = 0.0f;
		}
		for (int l36 = 0; l36 < 32768; l36 = l36 + 1) {
			fVec14[l36] = 0.0f;
		}
		for (int l37 = 0; l37 < 4096; l37 = l37 + 1) {
			fVec15[l37] = 0.0f;
		}
		for (int l38 = 0; l38 < 2; l38 = l38 + 1) {
			fRec34[l38] = 0.0f;
		}
		for (int l39 = 0; l39 < 2; l39 = l39 + 1) {
			fRec41[l39] = 0.0f;
		}
		for (int l40 = 0; l40 < 2; l40 = l40 + 1) {
			fRec40[l40] = 0.0f;
		}
		for (int l41 = 0; l41 < 32768; l41 = l41 + 1) {
			fVec16[l41] = 0.0f;
		}
		for (int l42 = 0; l42 < 2048; l42 = l42 + 1) {
			fVec17[l42] = 0.0f;
		}
		for (int l43 = 0; l43 < 2; l43 = l43 + 1) {
			fRec38[l43] = 0.0f;
		}
		for (int l44 = 0; l44 < 3; l44 = l44 + 1) {
			fRec0[l44] = 0.0f;
		}
		for (int l45 = 0; l45 < 3; l45 = l45 + 1) {
			fRec1[l45] = 0.0f;
		}
		for (int l46 = 0; l46 < 3; l46 = l46 + 1) {
			fRec2[l46] = 0.0f;
		}
		for (int l47 = 0; l47 < 3; l47 = l47 + 1) {
			fRec3[l47] = 0.0f;
		}
		for (int l48 = 0; l48 < 3; l48 = l48 + 1) {
			fRec4[l48] = 0.0f;
		}
		for (int l49 = 0; l49 < 3; l49 = l49 + 1) {
			fRec5[l49] = 0.0f;
		}
		for (int l50 = 0; l50 < 3; l50 = l50 + 1) {
			fRec6[l50] = 0.0f;
		}
		for (int l51 = 0; l51 < 3; l51 = l51 + 1) {
			fRec7[l51] = 0.0f;
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
	
	ZitaDsp* clone() {
		return new ZitaDsp();
	}
	
	int getSampleRate() {
		return fSampleRate;
	}
	
	void buildUserInterface(UI* ui_interface) {
		ui_interface->openVerticalBox("zita");
		ui_interface->addHorizontalSlider("damp", &fHslider1, FAUSTFLOAT(6e+03f), FAUSTFLOAT(8e+02f), FAUSTFLOAT(1.6e+04f), FAUSTFLOAT(1.0f));
		ui_interface->addHorizontalSlider("t60", &fHslider0, FAUSTFLOAT(3.0f), FAUSTFLOAT(0.5f), FAUSTFLOAT(2e+01f), FAUSTFLOAT(0.01f));
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
			fRec11[0] = fSlow0 + fConst2 * fRec11[1];
			float fTemp0 = std::min<float>(2e+01f, std::max<float>(0.5f, fRec11[0]));
			float fTemp1 = std::exp(-(fConst5 / fTemp0));
			float fTemp2 = ZitaDsp_faustpower2_f(fTemp1);
			float fTemp3 = 1.0f - fTemp2;
			fRec12[0] = fSlow1 + fConst2 * fRec12[1];
			float fTemp4 = std::cos(fConst6 * std::min<float>(1.6e+04f, std::max<float>(8e+02f, fRec12[0])));
			float fTemp5 = 1.0f - fTemp4 * fTemp2;
			float fTemp6 = std::sqrt(std::max<float>(0.0f, ZitaDsp_faustpower2_f(fTemp5) / ZitaDsp_faustpower2_f(fTemp3) + -1.0f));
			float fTemp7 = fTemp5 / fTemp3;
			fRec13[0] = -(fConst10 * (fConst9 * fRec13[1] - (fRec4[1] + fRec4[2])));
			fRec10[0] = fTemp1 * (fTemp6 + (1.0f - fTemp7)) * (fRec4[1] + fRec13[0] * (std::exp(-(fConst7 / fTemp0)) / fTemp1 + -1.0f)) + (fTemp7 - fTemp6) * fRec10[1];
			fVec0[IOTA0 & 16383] = 0.35355338f * fRec10[0] + 1e-20f;
			fVec1[IOTA0 & 16383] = float(input0[i0]);
			float fTemp8 = 0.3f * fVec1[(IOTA0 - iConst13) & 16383];
			float fTemp9 = fTemp8 + fVec0[(IOTA0 - iConst12) & 16383] - 0.6f * fRec8[1];
			fVec2[IOTA0 & 4095] = fTemp9;
			fRec8[0] = fVec2[(IOTA0 - iConst14) & 4095];
			float fRec9 = 0.6f * fTemp9;
			float fTemp10 = std::exp(-(fConst17 / fTemp0));
			float fTemp11 = ZitaDsp_faustpower2_f(fTemp10);
			float fTemp12 = 1.0f - fTemp11;
			float fTemp13 = 1.0f - fTemp11 * fTemp4;
			float fTemp14 = std::sqrt(std::max<float>(0.0f, ZitaDsp_faustpower2_f(fTemp13) / ZitaDsp_faustpower2_f(fTemp12) + -1.0f));
			float fTemp15 = fTemp13 / fTemp12;
			fRec17[0] = -(fConst10 * (fConst9 * fRec17[1] - (fRec0[1] + fRec0[2])));
			fRec16[0] = fTemp10 * (fTemp14 + (1.0f - fTemp15)) * (fRec0[1] + fRec17[0] * (std::exp(-(fConst18 / fTemp0)) / fTemp10 + -1.0f)) + (fTemp15 - fTemp14) * fRec16[1];
			fVec3[IOTA0 & 16383] = 0.35355338f * fRec16[0] + 1e-20f;
			float fTemp16 = fVec3[(IOTA0 - iConst20) & 16383] + fTemp8 - 0.6f * fRec14[1];
			fVec4[IOTA0 & 2047] = fTemp16;
			fRec14[0] = fVec4[(IOTA0 - iConst21) & 2047];
			float fRec15 = 0.6f * fTemp16;
			float fTemp17 = fRec15 + fRec9;
			float fTemp18 = std::exp(-(fConst24 / fTemp0));
			float fTemp19 = ZitaDsp_faustpower2_f(fTemp18);
			float fTemp20 = 1.0f - fTemp19;
			float fTemp21 = 1.0f - fTemp4 * fTemp19;
			float fTemp22 = std::sqrt(std::max<float>(0.0f, ZitaDsp_faustpower2_f(fTemp21) / ZitaDsp_faustpower2_f(fTemp20) + -1.0f));
			float fTemp23 = fTemp21 / fTemp20;
			fRec21[0] = -(fConst10 * (fConst9 * fRec21[1] - (fRec2[1] + fRec2[2])));
			fRec20[0] = fTemp18 * (fTemp22 + (1.0f - fTemp23)) * (fRec2[1] + fRec21[0] * (std::exp(-(fConst25 / fTemp0)) / fTemp18 + -1.0f)) + (fTemp23 - fTemp22) * fRec20[1];
			fVec5[IOTA0 & 16383] = 0.35355338f * fRec20[0] + 1e-20f;
			float fTemp24 = fVec5[(IOTA0 - iConst27) & 16383] - (fTemp8 + 0.6f * fRec18[1]);
			fVec6[IOTA0 & 4095] = fTemp24;
			fRec18[0] = fVec6[(IOTA0 - iConst28) & 4095];
			float fRec19 = 0.6f * fTemp24;
			float fTemp25 = std::exp(-(fConst31 / fTemp0));
			float fTemp26 = ZitaDsp_faustpower2_f(fTemp25);
			float fTemp27 = 1.0f - fTemp26;
			float fTemp28 = 1.0f - fTemp4 * fTemp26;
			float fTemp29 = std::sqrt(std::max<float>(0.0f, ZitaDsp_faustpower2_f(fTemp28) / ZitaDsp_faustpower2_f(fTemp27) + -1.0f));
			float fTemp30 = fTemp28 / fTemp27;
			fRec25[0] = -(fConst10 * (fConst9 * fRec25[1] - (fRec6[1] + fRec6[2])));
			fRec24[0] = fTemp25 * (fTemp29 + (1.0f - fTemp30)) * (fRec6[1] + fRec25[0] * (std::exp(-(fConst32 / fTemp0)) / fTemp25 + -1.0f)) + (fTemp30 - fTemp29) * fRec24[1];
			fVec7[IOTA0 & 16383] = 0.35355338f * fRec24[0] + 1e-20f;
			float fTemp31 = fVec7[(IOTA0 - iConst34) & 16383] - (fTemp8 + 0.6f * fRec22[1]);
			fVec8[IOTA0 & 2047] = fTemp31;
			fRec22[0] = fVec8[(IOTA0 - iConst35) & 2047];
			float fRec23 = 0.6f * fTemp31;
			float fTemp32 = fRec23 + fRec19 + fTemp17;
			float fTemp33 = std::exp(-(fConst38 / fTemp0));
			float fTemp34 = ZitaDsp_faustpower2_f(fTemp33);
			float fTemp35 = 1.0f - fTemp34;
			float fTemp36 = 1.0f - fTemp4 * fTemp34;
			float fTemp37 = std::sqrt(std::max<float>(0.0f, ZitaDsp_faustpower2_f(fTemp36) / ZitaDsp_faustpower2_f(fTemp35) + -1.0f));
			float fTemp38 = fTemp36 / fTemp35;
			fRec29[0] = -(fConst10 * (fConst9 * fRec29[1] - (fRec1[1] + fRec1[2])));
			fRec28[0] = fTemp33 * (fTemp37 + (1.0f - fTemp38)) * (fRec1[1] + fRec29[0] * (std::exp(-(fConst39 / fTemp0)) / fTemp33 + -1.0f)) + (fTemp38 - fTemp37) * fRec28[1];
			fVec9[IOTA0 & 32767] = 0.35355338f * fRec28[0] + 1e-20f;
			fVec10[IOTA0 & 16383] = float(input1[i0]);
			float fTemp39 = 0.3f * fVec10[(IOTA0 - iConst13) & 16383];
			float fTemp40 = fTemp39 + 0.6f * fRec26[1] + fVec9[(IOTA0 - iConst41) & 32767];
			fVec11[IOTA0 & 4095] = fTemp40;
			fRec26[0] = fVec11[(IOTA0 - iConst42) & 4095];
			float fRec27 = -(0.6f * fTemp40);
			float fTemp41 = std::exp(-(fConst45 / fTemp0));
			float fTemp42 = ZitaDsp_faustpower2_f(fTemp41);
			float fTemp43 = 1.0f - fTemp42;
			float fTemp44 = 1.0f - fTemp4 * fTemp42;
			float fTemp45 = std::sqrt(std::max<float>(0.0f, ZitaDsp_faustpower2_f(fTemp44) / ZitaDsp_faustpower2_f(fTemp43) + -1.0f));
			float fTemp46 = fTemp44 / fTemp43;
			fRec33[0] = -(fConst10 * (fConst9 * fRec33[1] - (fRec5[1] + fRec5[2])));
			fRec32[0] = fTemp41 * (fTemp45 + (1.0f - fTemp46)) * (fRec5[1] + fRec33[0] * (std::exp(-(fConst46 / fTemp0)) / fTemp41 + -1.0f)) + (fTemp46 - fTemp45) * fRec32[1];
			fVec12[IOTA0 & 16383] = 0.35355338f * fRec32[0] + 1e-20f;
			float fTemp47 = fVec12[(IOTA0 - iConst48) & 16383] + fTemp39 + 0.6f * fRec30[1];
			fVec13[IOTA0 & 4095] = fTemp47;
			fRec30[0] = fVec13[(IOTA0 - iConst49) & 4095];
			float fRec31 = -(0.6f * fTemp47);
			float fTemp48 = std::exp(-(fConst52 / fTemp0));
			float fTemp49 = ZitaDsp_faustpower2_f(fTemp48);
			float fTemp50 = 1.0f - fTemp49;
			float fTemp51 = 1.0f - fTemp4 * fTemp49;
			float fTemp52 = std::sqrt(std::max<float>(0.0f, ZitaDsp_faustpower2_f(fTemp51) / ZitaDsp_faustpower2_f(fTemp50) + -1.0f));
			float fTemp53 = fTemp51 / fTemp50;
			fRec37[0] = -(fConst10 * (fConst9 * fRec37[1] - (fRec3[1] + fRec3[2])));
			fRec36[0] = fTemp48 * (fTemp52 + (1.0f - fTemp53)) * (fRec3[1] + fRec37[0] * (std::exp(-(fConst53 / fTemp0)) / fTemp48 + -1.0f)) + (fTemp53 - fTemp52) * fRec36[1];
			fVec14[IOTA0 & 32767] = 0.35355338f * fRec36[0] + 1e-20f;
			float fTemp54 = 0.6f * fRec34[1] + fVec14[(IOTA0 - iConst55) & 32767];
			fVec15[IOTA0 & 4095] = fTemp54 - fTemp39;
			fRec34[0] = fVec15[(IOTA0 - iConst56) & 4095];
			float fRec35 = 0.6f * (fTemp39 - fTemp54);
			float fTemp55 = std::exp(-(fConst59 / fTemp0));
			float fTemp56 = ZitaDsp_faustpower2_f(fTemp55);
			float fTemp57 = 1.0f - fTemp56;
			float fTemp58 = 1.0f - fTemp4 * fTemp56;
			float fTemp59 = std::sqrt(std::max<float>(0.0f, ZitaDsp_faustpower2_f(fTemp58) / ZitaDsp_faustpower2_f(fTemp57) + -1.0f));
			float fTemp60 = fTemp58 / fTemp57;
			fRec41[0] = -(fConst10 * (fConst9 * fRec41[1] - (fRec7[1] + fRec7[2])));
			fRec40[0] = fTemp55 * (fTemp59 + (1.0f - fTemp60)) * (fRec7[1] + fRec41[0] * (std::exp(-(fConst60 / fTemp0)) / fTemp55 + -1.0f)) + (fTemp60 - fTemp59) * fRec40[1];
			fVec16[IOTA0 & 32767] = 0.35355338f * fRec40[0] + 1e-20f;
			float fTemp61 = 0.6f * fRec38[1] + fVec16[(IOTA0 - iConst62) & 32767];
			fVec17[IOTA0 & 2047] = fTemp61 - fTemp39;
			fRec38[0] = fVec17[(IOTA0 - iConst63) & 2047];
			float fRec39 = 0.6f * (fTemp39 - fTemp61);
			fRec0[0] = fRec38[1] + fRec34[1] + fRec30[1] + fRec26[1] + fRec22[1] + fRec18[1] + fRec8[1] + fRec14[1] + fRec39 + fRec35 + fRec31 + fRec27 + fTemp32;
			fRec1[0] = fRec22[1] + fRec18[1] + fRec8[1] + fRec14[1] + fTemp32 - (fRec38[1] + fRec34[1] + fRec30[1] + fRec26[1] + fRec39 + fRec35 + fRec27 + fRec31);
			float fTemp62 = fRec19 + fRec23;
			fRec2[0] = fRec30[1] + fRec26[1] + fRec8[1] + fRec14[1] + fRec31 + fRec27 + fTemp17 - (fRec38[1] + fRec34[1] + fRec22[1] + fRec18[1] + fRec39 + fRec35 + fTemp62);
			fRec3[0] = fRec38[1] + fRec34[1] + fRec8[1] + fRec14[1] + fRec39 + fRec35 + fTemp17 - (fRec30[1] + fRec26[1] + fRec22[1] + fRec18[1] + fRec31 + fRec27 + fTemp62);
			float fTemp63 = fRec9 + fRec23;
			float fTemp64 = fRec15 + fRec19;
			fRec4[0] = fRec34[1] + fRec26[1] + fRec18[1] + fRec14[1] + fRec35 + fRec27 + fTemp64 - (fRec38[1] + fRec30[1] + fRec22[1] + fRec8[1] + fRec39 + fRec31 + fTemp63);
			fRec5[0] = fRec38[1] + fRec30[1] + fRec18[1] + fRec14[1] + fRec39 + fRec31 + fTemp64 - (fRec34[1] + fRec26[1] + fRec22[1] + fRec8[1] + fRec35 + fRec27 + fTemp63);
			float fTemp65 = fRec9 + fRec19;
			float fTemp66 = fRec15 + fRec23;
			fRec6[0] = fRec38[1] + fRec26[1] + fRec22[1] + fRec14[1] + fRec39 + fRec27 + fTemp66 - (fRec34[1] + fRec30[1] + fRec18[1] + fRec8[1] + fRec35 + fRec31 + fTemp65);
			fRec7[0] = fRec34[1] + fRec30[1] + fRec22[1] + fRec14[1] + fRec35 + fRec31 + fTemp66 - (fRec38[1] + fRec26[1] + fRec18[1] + fRec8[1] + fRec39 + fRec27 + fTemp65);
			output0[i0] = FAUSTFLOAT(0.37f * (fRec1[0] + fRec2[0]));
			output1[i0] = FAUSTFLOAT(0.37f * (fRec1[0] - fRec2[0]));
			fRec11[1] = fRec11[0];
			fRec12[1] = fRec12[0];
			fRec13[1] = fRec13[0];
			fRec10[1] = fRec10[0];
			IOTA0 = IOTA0 + 1;
			fRec8[1] = fRec8[0];
			fRec17[1] = fRec17[0];
			fRec16[1] = fRec16[0];
			fRec14[1] = fRec14[0];
			fRec21[1] = fRec21[0];
			fRec20[1] = fRec20[0];
			fRec18[1] = fRec18[0];
			fRec25[1] = fRec25[0];
			fRec24[1] = fRec24[0];
			fRec22[1] = fRec22[0];
			fRec29[1] = fRec29[0];
			fRec28[1] = fRec28[0];
			fRec26[1] = fRec26[0];
			fRec33[1] = fRec33[0];
			fRec32[1] = fRec32[0];
			fRec30[1] = fRec30[0];
			fRec37[1] = fRec37[0];
			fRec36[1] = fRec36[0];
			fRec34[1] = fRec34[0];
			fRec41[1] = fRec41[0];
			fRec40[1] = fRec40[0];
			fRec38[1] = fRec38[0];
			fRec0[2] = fRec0[1];
			fRec0[1] = fRec0[0];
			fRec1[2] = fRec1[1];
			fRec1[1] = fRec1[0];
			fRec2[2] = fRec2[1];
			fRec2[1] = fRec2[0];
			fRec3[2] = fRec3[1];
			fRec3[1] = fRec3[0];
			fRec4[2] = fRec4[1];
			fRec4[1] = fRec4[0];
			fRec5[2] = fRec5[1];
			fRec5[1] = fRec5[0];
			fRec6[2] = fRec6[1];
			fRec6[1] = fRec6[0];
			fRec7[2] = fRec7[1];
			fRec7[1] = fRec7[0];
		}
	}

};

#endif
