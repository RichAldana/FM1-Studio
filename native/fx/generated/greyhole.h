/* ------------------------------------------------------------
name: "greyhole"
Code generated with Faust 2.70.3 (https://faust.grame.fr)
Compilation options: -lang cpp -i -nvi -ct 1 -cn GreyholeDsp -scn FaustDsp -es 1 -mcd 16 -mdd 1024 -mdy 33 -single -ftz 0
------------------------------------------------------------ */

#ifndef  __GreyholeDsp_H__
#define  __GreyholeDsp_H__

#ifndef FAUSTFLOAT
#define FAUSTFLOAT float
#endif 

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <math.h>

#ifndef FAUSTCLASS 
#define FAUSTCLASS GreyholeDsp
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

const static int iGreyholeDspSIG0Wave0[1302] = {2,3,5,7,11,13,17,19,23,29,31,37,41,43,47,53,59,61,67,71,73,79,83,89,97,101,103,107,109,113,127,131,137,139,149,151,157,163,167,173,179,181,191,193,197,199,211,223,227,229,233,239,241,251,257,263,269,271,277,281,283,293,307,311,313,317,331,337,347,349,353,359,367,373,379,383,389,397,401,409,419,421,431,433,439,443,449,457,461,463,467,479,487,491,499,503,509,521,523,541,547,557,563,569,571,577,587,593,599,601,607,613,617,619,631,641,643,647,653,659,661,673,677,683,691,701,709,719,727,733,739,743,751,757,761,769,773,787,797,809,811,821,823,827,829,839,853,857,859,863,877,881,883,887,907,911,919,929,937,941,947,953,967,971,977,983,991,997,1009,1013,1019,1021,1031,1033,1039,1049,1051,1061,1063,1069,1087,1091,1093,1097,1103,1109,1117,1123,1129,1151,1153,1163,1171,1181,1187,1193,1201,1213,1217,1223,1229,1231,1237,1249,1259,1277,1279,1283,1289,1291,1297,1301,1303,1307,1319,1321,1327,1361,1367,1373,1381,1399,1409,1423,1427,1429,1433,1439,1447,1451,1453,1459,1471,1481,1483,1487,1489,1493,1499,1511,1523,1531,1543,1549,1553,1559,1567,1571,1579,1583,1597,1601,1607,1609,1613,1619,1621,1627,1637,1657,1663,1667,1669,1693,1697,1699,1709,1721,1723,1733,1741,1747,1753,1759,1777,1783,1787,1789,1801,1811,1823,1831,1847,1861,1867,1871,1873,1877,1879,1889,1901,1907,1913,1931,1933,1949,1951,1973,1979,1987,1993,1997,1999,2003,2011,2017,2027,2029,2039,2053,2063,2069,2081,2083,2087,2089,2099,2111,2113,2129,2131,2137,2141,2143,2153,2161,2179,2203,2207,2213,2221,2237,2239,2243,2251,2267,2269,2273,2281,2287,2293,2297,2309,2311,2333,2339,2341,2347,2351,2357,2371,2377,2381,2383,2389,2393,2399,2411,2417,2423,2437,2441,2447,2459,2467,2473,2477,2503,2521,2531,2539,2543,2549,2551,2557,2579,2591,2593,2609,2617,2621,2633,2647,2657,2659,2663,2671,2677,2683,2687,2689,2693,2699,2707,2711,2713,2719,2729,2731,2741,2749,2753,2767,2777,2789,2791,2797,2801,2803,2819,2833,2837,2843,2851,2857,2861,2879,2887,2897,2903,2909,2917,2927,2939,2953,2957,2963,2969,2971,2999,3001,3011,3019,3023,3037,3041,3049,3061,3067,3079,3083,3089,3109,3119,3121,3137,3163,3167,3169,3181,3187,3191,3203,3209,3217,3221,3229,3251,3253,3257,3259,3271,3299,3301,3307,3313,3319,3323,3329,3331,3343,3347,3359,3361,3371,3373,3389,3391,3407,3413,3433,3449,3457,3461,3463,3467,3469,3491,3499,3511,3517,3527,3529,3533,3539,3541,3547,3557,3559,3571,3581,3583,3593,3607,3613,3617,3623,3631,3637,3643,3659,3671,3673,3677,3691,3697,3701,3709,3719,3727,3733,3739,3761,3767,3769,3779,3793,3797,3803,3821,3823,3833,3847,3851,3853,3863,3877,3881,3889,3907,3911,3917,3919,3923,3929,3931,3943,3947,3967,3989,4001,4003,4007,4013,4019,4021,4027,4049,4051,4057,4073,4079,4091,4093,4099,4111,4127,4129,4133,4139,4153,4157,4159,4177,4201,4211,4217,4219,4229,4231,4241,4243,4253,4259,4261,4271,4273,4283,4289,4297,4327,4337,4339,4349,4357,4363,4373,4391,4397,4409,4421,4423,4441,4447,4451,4457,4463,4481,4483,4493,4507,4513,4517,4519,4523,4547,4549,4561,4567,4583,4591,4597,4603,4621,4637,4639,4643,4649,4651,4657,4663,4673,4679,4691,4703,4721,4723,4729,4733,4751,4759,4783,4787,4789,4793,4799,4801,4813,4817,4831,4861,4871,4877,4889,4903,4909,4919,4931,4933,4937,4943,4951,4957,4967,4969,4973,4987,4993,4999,5003,5009,5011,5021,5023,5039,5051,5059,5077,5081,5087,5099,5101,5107,5113,5119,5147,5153,5167,5171,5179,5189,5197,5209,5227,5231,5233,5237,5261,5273,5279,5281,5297,5303,5309,5323,5333,5347,5351,5381,5387,5393,5399,5407,5413,5417,5419,5431,5437,5441,5443,5449,5471,5477,5479,5483,5501,5503,5507,5519,5521,5527,5531,5557,5563,5569,5573,5581,5591,5623,5639,5641,5647,5651,5653,5657,5659,5669,5683,5689,5693,5701,5711,5717,5737,5741,5743,5749,5779,5783,5791,5801,5807,5813,5821,5827,5839,5843,5849,5851,5857,5861,5867,5869,5879,5881,5897,5903,5923,5927,5939,5953,5981,5987,6007,6011,6029,6037,6043,6047,6053,6067,6073,6079,6089,6091,6101,6113,6121,6131,6133,6143,6151,6163,6173,6197,6199,6203,6211,6217,6221,6229,6247,6257,6263,6269,6271,6277,6287,6299,6301,6311,6317,6323,6329,6337,6343,6353,6359,6361,6367,6373,6379,6389,6397,6421,6427,6449,6451,6469,6473,6481,6491,6521,6529,6547,6551,6553,6563,6569,6571,6577,6581,6599,6607,6619,6637,6653,6659,6661,6673,6679,6689,6691,6701,6703,6709,6719,6733,6737,6761,6763,6779,6781,6791,6793,6803,6823,6827,6829,6833,6841,6857,6863,6869,6871,6883,6899,6907,6911,6917,6947,6949,6959,6961,6967,6971,6977,6983,6991,6997,7001,7013,7019,7027,7039,7043,7057,7069,7079,7103,7109,7121,7127,7129,7151,7159,7177,7187,7193,7207,7211,7213,7219,7229,7237,7243,7247,7253,7283,7297,7307,7309,7321,7331,7333,7349,7351,7369,7393,7411,7417,7433,7451,7457,7459,7477,7481,7487,7489,7499,7507,7517,7523,7529,7537,7541,7547,7549,7559,7561,7573,7577,7583,7589,7591,7603,7607,7621,7639,7643,7649,7669,7673,7681,7687,7691,7699,7703,7717,7723,7727,7741,7753,7757,7759,7789,7793,7817,7823,7829,7841,7853,7867,7873,7877,7879,7883,7901,7907,7919,7927,7933,7937,7949,7951,7963,7993,8009,8011,8017,8039,8053,8059,8069,8081,8087,8089,8093,8101,8111,8117,8123,8147,8161,8167,8171,8179,8191,8209,8219,8221,8231,8233,8237,8243,8263,8269,8273,8287,8291,8293,8297,8311,8317,8329,8353,8363,8369,8377,8387,8389,8419,8423,8429,8431,8443,8447,8461,8467,8501,8513,8521,8527,8537,8539,8543,8563,8573,8581,8597,8599,8609,8623,8627,8629,8641,8647,8663,8669,8677,8681,8689,8693,8699,8707,8713,8719,8731,8737,8741,8747,8753,8761,8779,8783,8803,8807,8819,8821,8831,8837,8839,8849,8861,8863,8867,8887,8893,8923,8929,8933,8941,8951,8963,8969,8971,8999,9001,9007,9011,9013,9029,9041,9043,9049,9059,9067,9091,9103,9109,9127,9133,9137,9151,9157,9161,9173,9181,9187,9199,9203,9209,9221,9227,9239,9241,9257,9277,9281,9283,9293,9311,9319,9323,9337,9341,9343,9349,9371,9377,9391,9397,9403,9413,9419,9421,9431,9433,9437,9439,9461,9463,9467,9473,9479,9491,9497,9511,9521,9533,9539,9547,9551,9587,9601,9613,9619,9623,9629,9631,9643,9649,9661,9677,9679,9689,9697,9719,9721,9733,9739,9743,9749,9767,9769,9781,9787,9791,9803,9811,9817,9829,9833,9839,9851,9857,9859,9871,9883,9887,9901,9907,9923,9929,9931,9941,9949,9967,9973,10007,10009,10037,10039,10061,10067,10069,10079,10091,10093,10099,10103,10111,10133,10139,10141,10151,10159,10163,10169,10177,10181,10193,10211,10223,10243,10247,10253,10259,10267,10271,10273,10289,10301,10303,10313,10321,10331,10333,10337,10343,10357,10369,10391,10399,10427,10429,10433,10453,10457,10459,10463,10477,10487,10499,10501,10513,10529,10531,10559,10567,10589,10597,10601,10607,10613,10627,10631,10639,10651,10657,10663,10667};
class GreyholeDspSIG0 {
	
  private:
	
	int iGreyholeDspSIG0Wave0_idx;
	
  public:
	
	int getNumInputsGreyholeDspSIG0() {
		return 0;
	}
	int getNumOutputsGreyholeDspSIG0() {
		return 1;
	}
	
	void instanceInitGreyholeDspSIG0(int sample_rate) {
		iGreyholeDspSIG0Wave0_idx = 0;
	}
	
	void fillGreyholeDspSIG0(int count, int* table) {
		for (int i1 = 0; i1 < count; i1 = i1 + 1) {
			table[i1] = iGreyholeDspSIG0Wave0[iGreyholeDspSIG0Wave0_idx];
			iGreyholeDspSIG0Wave0_idx = (1 + iGreyholeDspSIG0Wave0_idx) % 1302;
		}
	}

};

static GreyholeDspSIG0* newGreyholeDspSIG0() { return (GreyholeDspSIG0*)new GreyholeDspSIG0(); }
static void deleteGreyholeDspSIG0(GreyholeDspSIG0* dsp) { delete dsp; }

static int itbl0GreyholeDspSIG0[1302];

class GreyholeDsp final : public FaustDsp {
	
 private:
	
	int iVec0[2];
	int fSampleRate;
	float fConst1;
	float fConst2;
	FAUSTFLOAT fHslider0;
	float fRec3[2];
	float fVec1[2];
	FAUSTFLOAT fHslider1;
	float fRec4[2];
	float fVec2[2];
	int IOTA0;
	float fVec3[2];
	float fConst3;
	float fRec5[2];
	float fRec6[2];
	FAUSTFLOAT fHslider2;
	float fRec7[2];
	float fVec4[2];
	float fConst4;
	float fVec5[131072];
	float fConst5;
	float fRec8[2];
	float fRec9[2];
	float fRec10[2];
	float fRec11[2];
	FAUSTFLOAT fHslider3;
	float fRec12[2];
	float fVec6[2];
	float fVec7[131072];
	FAUSTFLOAT fHslider4;
	float fRec26[2];
	float fRec25[2];
	float fVec8[16384];
	float fVec9[2];
	float fRec24[2];
	float fRec22[2];
	float fRec28[2];
	float fVec10[16384];
	float fVec11[2];
	float fRec27[2];
	float fRec23[2];
	float fVec12[16384];
	float fRec29[2];
	float fVec13[2];
	float fRec21[2];
	float fRec19[2];
	float fRec31[2];
	float fVec14[16384];
	float fVec15[2];
	float fRec30[2];
	float fRec20[2];
	float fVec16[16384];
	float fRec32[2];
	float fVec17[2];
	float fRec18[2];
	float fRec16[2];
	float fRec34[2];
	float fVec18[16384];
	float fVec19[2];
	float fRec33[2];
	float fRec17[2];
	float fVec20[16384];
	float fRec35[2];
	float fVec21[2];
	float fRec15[2];
	float fRec13[2];
	float fRec37[2];
	float fVec22[16384];
	float fVec23[2];
	float fRec36[2];
	float fRec14[2];
	float fRec50[2];
	float fVec24[16384];
	float fVec25[2];
	float fRec49[2];
	float fRec47[2];
	float fRec52[2];
	float fVec26[16384];
	float fVec27[2];
	float fRec51[2];
	float fRec48[2];
	float fVec28[16384];
	float fRec53[2];
	float fVec29[2];
	float fRec46[2];
	float fRec44[2];
	float fRec55[2];
	float fVec30[16384];
	float fVec31[2];
	float fRec54[2];
	float fRec45[2];
	float fVec32[16384];
	float fRec56[2];
	float fVec33[2];
	float fRec43[2];
	float fRec41[2];
	float fRec58[2];
	float fVec34[16384];
	float fVec35[2];
	float fRec57[2];
	float fRec42[2];
	float fVec36[16384];
	float fRec59[2];
	float fVec37[2];
	float fRec40[2];
	float fRec38[2];
	float fRec61[2];
	float fVec38[16384];
	float fVec39[2];
	float fRec60[2];
	float fRec39[2];
	float fRec74[2];
	float fVec40[16384];
	float fVec41[2];
	float fRec73[2];
	float fRec71[2];
	float fRec76[2];
	float fVec42[16384];
	float fVec43[2];
	float fRec75[2];
	float fRec72[2];
	float fVec44[16384];
	float fRec77[2];
	float fVec45[2];
	float fRec70[2];
	float fRec68[2];
	float fRec79[2];
	float fVec46[16384];
	float fVec47[2];
	float fRec78[2];
	float fRec69[2];
	float fVec48[16384];
	float fRec80[2];
	float fVec49[2];
	float fRec67[2];
	float fRec65[2];
	float fRec82[2];
	float fVec50[16384];
	float fVec51[2];
	float fRec81[2];
	float fRec66[2];
	float fVec52[16384];
	float fRec83[2];
	float fVec53[2];
	float fRec64[2];
	float fRec62[2];
	float fRec85[2];
	float fVec54[16384];
	float fVec55[2];
	float fRec84[2];
	float fRec63[2];
	float fRec2[2];
	float fRec0[1024];
	float fRec86[2];
	float fRec1[1024];
	
 public:
	GreyholeDsp() {}

	void metadata(Meta* m) { 
		m->declare("basics.lib/name", "Faust Basic Element Library");
		m->declare("basics.lib/tabulateNd", "Copyright (C) 2023 Bart Brouns <bart@magnetophon.nl>");
		m->declare("basics.lib/version", "1.12.0");
		m->declare("compile_options", "-lang cpp -i -nvi -ct 1 -cn GreyholeDsp -scn FaustDsp -es 1 -mcd 16 -mdd 1024 -mdy 33 -single -ftz 0");
		m->declare("delays.lib/fdelay1a:author", "Julius O. Smith III");
		m->declare("delays.lib/fdelay4:author", "Julius O. Smith III");
		m->declare("delays.lib/fdelayltv:author", "Julius O. Smith III");
		m->declare("delays.lib/name", "Faust Delay Library");
		m->declare("delays.lib/version", "1.1.0");
		m->declare("filename", "greyhole.dsp");
		m->declare("filters.lib/lowpass0_highpass1", "MIT-style STK-4.3 license");
		m->declare("filters.lib/name", "Faust Filters Library");
		m->declare("filters.lib/nlf2:author", "Julius O. Smith III");
		m->declare("filters.lib/nlf2:copyright", "Copyright (C) 2003-2019 by Julius O. Smith III <jos@ccrma.stanford.edu>");
		m->declare("filters.lib/nlf2:license", "MIT-style STK-4.3 license");
		m->declare("filters.lib/tf1:author", "Julius O. Smith III");
		m->declare("filters.lib/tf1:copyright", "Copyright (C) 2003-2019 by Julius O. Smith III <jos@ccrma.stanford.edu>");
		m->declare("filters.lib/tf1:license", "MIT-style STK-4.3 license");
		m->declare("filters.lib/version", "1.3.0");
		m->declare("maths.lib/author", "GRAME");
		m->declare("maths.lib/copyright", "GRAME");
		m->declare("maths.lib/license", "LGPL with exception");
		m->declare("maths.lib/name", "Faust Math Library");
		m->declare("maths.lib/version", "2.7.0");
		m->declare("name", "greyhole");
		m->declare("oscillators.lib/name", "Faust Oscillator Library");
		m->declare("oscillators.lib/version", "1.5.0");
		m->declare("platform.lib/name", "Generic Platform Library");
		m->declare("platform.lib/version", "1.3.0");
		m->declare("reverbs.lib/greyhole:author", "Julian Parker, bug fixes and minor interface changes by Till Bovermann");
		m->declare("reverbs.lib/greyhole:license", "GPL2+");
		m->declare("reverbs.lib/name", "Faust Reverb Library");
		m->declare("reverbs.lib/version", "1.2.1");
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
		GreyholeDspSIG0* sig0 = newGreyholeDspSIG0();
		sig0->instanceInitGreyholeDspSIG0(sample_rate);
		sig0->fillGreyholeDspSIG0(1302, itbl0GreyholeDspSIG0);
		deleteGreyholeDspSIG0(sig0);
	}
	
	void instanceConstants(int sample_rate) {
		fSampleRate = sample_rate;
		float fConst0 = std::min<float>(1.92e+05f, std::max<float>(1.0f, float(fSampleRate)));
		fConst1 = 44.1f / fConst0;
		fConst2 = 1.0f - fConst1;
		fConst3 = 3.1415927f / fConst0;
		fConst4 = 0.00056689343f * fConst0;
		fConst5 = std::floor(std::min<float>(65533.0f, 0.2f * fConst0));
	}
	
	void instanceResetUserInterface() {
		fHslider0 = FAUSTFLOAT(0.3f);
		fHslider1 = FAUSTFLOAT(0.8f);
		fHslider2 = FAUSTFLOAT(0.1f);
		fHslider3 = FAUSTFLOAT(0.5f);
		fHslider4 = FAUSTFLOAT(1.5f);
	}
	
	void instanceClear() {
		for (int l0 = 0; l0 < 2; l0 = l0 + 1) {
			iVec0[l0] = 0;
		}
		for (int l1 = 0; l1 < 2; l1 = l1 + 1) {
			fRec3[l1] = 0.0f;
		}
		for (int l2 = 0; l2 < 2; l2 = l2 + 1) {
			fVec1[l2] = 0.0f;
		}
		for (int l3 = 0; l3 < 2; l3 = l3 + 1) {
			fRec4[l3] = 0.0f;
		}
		for (int l4 = 0; l4 < 2; l4 = l4 + 1) {
			fVec2[l4] = 0.0f;
		}
		IOTA0 = 0;
		for (int l5 = 0; l5 < 2; l5 = l5 + 1) {
			fVec3[l5] = 0.0f;
		}
		for (int l6 = 0; l6 < 2; l6 = l6 + 1) {
			fRec5[l6] = 0.0f;
		}
		for (int l7 = 0; l7 < 2; l7 = l7 + 1) {
			fRec6[l7] = 0.0f;
		}
		for (int l8 = 0; l8 < 2; l8 = l8 + 1) {
			fRec7[l8] = 0.0f;
		}
		for (int l9 = 0; l9 < 2; l9 = l9 + 1) {
			fVec4[l9] = 0.0f;
		}
		for (int l10 = 0; l10 < 131072; l10 = l10 + 1) {
			fVec5[l10] = 0.0f;
		}
		for (int l11 = 0; l11 < 2; l11 = l11 + 1) {
			fRec8[l11] = 0.0f;
		}
		for (int l12 = 0; l12 < 2; l12 = l12 + 1) {
			fRec9[l12] = 0.0f;
		}
		for (int l13 = 0; l13 < 2; l13 = l13 + 1) {
			fRec10[l13] = 0.0f;
		}
		for (int l14 = 0; l14 < 2; l14 = l14 + 1) {
			fRec11[l14] = 0.0f;
		}
		for (int l15 = 0; l15 < 2; l15 = l15 + 1) {
			fRec12[l15] = 0.0f;
		}
		for (int l16 = 0; l16 < 2; l16 = l16 + 1) {
			fVec6[l16] = 0.0f;
		}
		for (int l17 = 0; l17 < 131072; l17 = l17 + 1) {
			fVec7[l17] = 0.0f;
		}
		for (int l18 = 0; l18 < 2; l18 = l18 + 1) {
			fRec26[l18] = 0.0f;
		}
		for (int l19 = 0; l19 < 2; l19 = l19 + 1) {
			fRec25[l19] = 0.0f;
		}
		for (int l20 = 0; l20 < 16384; l20 = l20 + 1) {
			fVec8[l20] = 0.0f;
		}
		for (int l21 = 0; l21 < 2; l21 = l21 + 1) {
			fVec9[l21] = 0.0f;
		}
		for (int l22 = 0; l22 < 2; l22 = l22 + 1) {
			fRec24[l22] = 0.0f;
		}
		for (int l23 = 0; l23 < 2; l23 = l23 + 1) {
			fRec22[l23] = 0.0f;
		}
		for (int l24 = 0; l24 < 2; l24 = l24 + 1) {
			fRec28[l24] = 0.0f;
		}
		for (int l25 = 0; l25 < 16384; l25 = l25 + 1) {
			fVec10[l25] = 0.0f;
		}
		for (int l26 = 0; l26 < 2; l26 = l26 + 1) {
			fVec11[l26] = 0.0f;
		}
		for (int l27 = 0; l27 < 2; l27 = l27 + 1) {
			fRec27[l27] = 0.0f;
		}
		for (int l28 = 0; l28 < 2; l28 = l28 + 1) {
			fRec23[l28] = 0.0f;
		}
		for (int l29 = 0; l29 < 16384; l29 = l29 + 1) {
			fVec12[l29] = 0.0f;
		}
		for (int l30 = 0; l30 < 2; l30 = l30 + 1) {
			fRec29[l30] = 0.0f;
		}
		for (int l31 = 0; l31 < 2; l31 = l31 + 1) {
			fVec13[l31] = 0.0f;
		}
		for (int l32 = 0; l32 < 2; l32 = l32 + 1) {
			fRec21[l32] = 0.0f;
		}
		for (int l33 = 0; l33 < 2; l33 = l33 + 1) {
			fRec19[l33] = 0.0f;
		}
		for (int l34 = 0; l34 < 2; l34 = l34 + 1) {
			fRec31[l34] = 0.0f;
		}
		for (int l35 = 0; l35 < 16384; l35 = l35 + 1) {
			fVec14[l35] = 0.0f;
		}
		for (int l36 = 0; l36 < 2; l36 = l36 + 1) {
			fVec15[l36] = 0.0f;
		}
		for (int l37 = 0; l37 < 2; l37 = l37 + 1) {
			fRec30[l37] = 0.0f;
		}
		for (int l38 = 0; l38 < 2; l38 = l38 + 1) {
			fRec20[l38] = 0.0f;
		}
		for (int l39 = 0; l39 < 16384; l39 = l39 + 1) {
			fVec16[l39] = 0.0f;
		}
		for (int l40 = 0; l40 < 2; l40 = l40 + 1) {
			fRec32[l40] = 0.0f;
		}
		for (int l41 = 0; l41 < 2; l41 = l41 + 1) {
			fVec17[l41] = 0.0f;
		}
		for (int l42 = 0; l42 < 2; l42 = l42 + 1) {
			fRec18[l42] = 0.0f;
		}
		for (int l43 = 0; l43 < 2; l43 = l43 + 1) {
			fRec16[l43] = 0.0f;
		}
		for (int l44 = 0; l44 < 2; l44 = l44 + 1) {
			fRec34[l44] = 0.0f;
		}
		for (int l45 = 0; l45 < 16384; l45 = l45 + 1) {
			fVec18[l45] = 0.0f;
		}
		for (int l46 = 0; l46 < 2; l46 = l46 + 1) {
			fVec19[l46] = 0.0f;
		}
		for (int l47 = 0; l47 < 2; l47 = l47 + 1) {
			fRec33[l47] = 0.0f;
		}
		for (int l48 = 0; l48 < 2; l48 = l48 + 1) {
			fRec17[l48] = 0.0f;
		}
		for (int l49 = 0; l49 < 16384; l49 = l49 + 1) {
			fVec20[l49] = 0.0f;
		}
		for (int l50 = 0; l50 < 2; l50 = l50 + 1) {
			fRec35[l50] = 0.0f;
		}
		for (int l51 = 0; l51 < 2; l51 = l51 + 1) {
			fVec21[l51] = 0.0f;
		}
		for (int l52 = 0; l52 < 2; l52 = l52 + 1) {
			fRec15[l52] = 0.0f;
		}
		for (int l53 = 0; l53 < 2; l53 = l53 + 1) {
			fRec13[l53] = 0.0f;
		}
		for (int l54 = 0; l54 < 2; l54 = l54 + 1) {
			fRec37[l54] = 0.0f;
		}
		for (int l55 = 0; l55 < 16384; l55 = l55 + 1) {
			fVec22[l55] = 0.0f;
		}
		for (int l56 = 0; l56 < 2; l56 = l56 + 1) {
			fVec23[l56] = 0.0f;
		}
		for (int l57 = 0; l57 < 2; l57 = l57 + 1) {
			fRec36[l57] = 0.0f;
		}
		for (int l58 = 0; l58 < 2; l58 = l58 + 1) {
			fRec14[l58] = 0.0f;
		}
		for (int l59 = 0; l59 < 2; l59 = l59 + 1) {
			fRec50[l59] = 0.0f;
		}
		for (int l60 = 0; l60 < 16384; l60 = l60 + 1) {
			fVec24[l60] = 0.0f;
		}
		for (int l61 = 0; l61 < 2; l61 = l61 + 1) {
			fVec25[l61] = 0.0f;
		}
		for (int l62 = 0; l62 < 2; l62 = l62 + 1) {
			fRec49[l62] = 0.0f;
		}
		for (int l63 = 0; l63 < 2; l63 = l63 + 1) {
			fRec47[l63] = 0.0f;
		}
		for (int l64 = 0; l64 < 2; l64 = l64 + 1) {
			fRec52[l64] = 0.0f;
		}
		for (int l65 = 0; l65 < 16384; l65 = l65 + 1) {
			fVec26[l65] = 0.0f;
		}
		for (int l66 = 0; l66 < 2; l66 = l66 + 1) {
			fVec27[l66] = 0.0f;
		}
		for (int l67 = 0; l67 < 2; l67 = l67 + 1) {
			fRec51[l67] = 0.0f;
		}
		for (int l68 = 0; l68 < 2; l68 = l68 + 1) {
			fRec48[l68] = 0.0f;
		}
		for (int l69 = 0; l69 < 16384; l69 = l69 + 1) {
			fVec28[l69] = 0.0f;
		}
		for (int l70 = 0; l70 < 2; l70 = l70 + 1) {
			fRec53[l70] = 0.0f;
		}
		for (int l71 = 0; l71 < 2; l71 = l71 + 1) {
			fVec29[l71] = 0.0f;
		}
		for (int l72 = 0; l72 < 2; l72 = l72 + 1) {
			fRec46[l72] = 0.0f;
		}
		for (int l73 = 0; l73 < 2; l73 = l73 + 1) {
			fRec44[l73] = 0.0f;
		}
		for (int l74 = 0; l74 < 2; l74 = l74 + 1) {
			fRec55[l74] = 0.0f;
		}
		for (int l75 = 0; l75 < 16384; l75 = l75 + 1) {
			fVec30[l75] = 0.0f;
		}
		for (int l76 = 0; l76 < 2; l76 = l76 + 1) {
			fVec31[l76] = 0.0f;
		}
		for (int l77 = 0; l77 < 2; l77 = l77 + 1) {
			fRec54[l77] = 0.0f;
		}
		for (int l78 = 0; l78 < 2; l78 = l78 + 1) {
			fRec45[l78] = 0.0f;
		}
		for (int l79 = 0; l79 < 16384; l79 = l79 + 1) {
			fVec32[l79] = 0.0f;
		}
		for (int l80 = 0; l80 < 2; l80 = l80 + 1) {
			fRec56[l80] = 0.0f;
		}
		for (int l81 = 0; l81 < 2; l81 = l81 + 1) {
			fVec33[l81] = 0.0f;
		}
		for (int l82 = 0; l82 < 2; l82 = l82 + 1) {
			fRec43[l82] = 0.0f;
		}
		for (int l83 = 0; l83 < 2; l83 = l83 + 1) {
			fRec41[l83] = 0.0f;
		}
		for (int l84 = 0; l84 < 2; l84 = l84 + 1) {
			fRec58[l84] = 0.0f;
		}
		for (int l85 = 0; l85 < 16384; l85 = l85 + 1) {
			fVec34[l85] = 0.0f;
		}
		for (int l86 = 0; l86 < 2; l86 = l86 + 1) {
			fVec35[l86] = 0.0f;
		}
		for (int l87 = 0; l87 < 2; l87 = l87 + 1) {
			fRec57[l87] = 0.0f;
		}
		for (int l88 = 0; l88 < 2; l88 = l88 + 1) {
			fRec42[l88] = 0.0f;
		}
		for (int l89 = 0; l89 < 16384; l89 = l89 + 1) {
			fVec36[l89] = 0.0f;
		}
		for (int l90 = 0; l90 < 2; l90 = l90 + 1) {
			fRec59[l90] = 0.0f;
		}
		for (int l91 = 0; l91 < 2; l91 = l91 + 1) {
			fVec37[l91] = 0.0f;
		}
		for (int l92 = 0; l92 < 2; l92 = l92 + 1) {
			fRec40[l92] = 0.0f;
		}
		for (int l93 = 0; l93 < 2; l93 = l93 + 1) {
			fRec38[l93] = 0.0f;
		}
		for (int l94 = 0; l94 < 2; l94 = l94 + 1) {
			fRec61[l94] = 0.0f;
		}
		for (int l95 = 0; l95 < 16384; l95 = l95 + 1) {
			fVec38[l95] = 0.0f;
		}
		for (int l96 = 0; l96 < 2; l96 = l96 + 1) {
			fVec39[l96] = 0.0f;
		}
		for (int l97 = 0; l97 < 2; l97 = l97 + 1) {
			fRec60[l97] = 0.0f;
		}
		for (int l98 = 0; l98 < 2; l98 = l98 + 1) {
			fRec39[l98] = 0.0f;
		}
		for (int l99 = 0; l99 < 2; l99 = l99 + 1) {
			fRec74[l99] = 0.0f;
		}
		for (int l100 = 0; l100 < 16384; l100 = l100 + 1) {
			fVec40[l100] = 0.0f;
		}
		for (int l101 = 0; l101 < 2; l101 = l101 + 1) {
			fVec41[l101] = 0.0f;
		}
		for (int l102 = 0; l102 < 2; l102 = l102 + 1) {
			fRec73[l102] = 0.0f;
		}
		for (int l103 = 0; l103 < 2; l103 = l103 + 1) {
			fRec71[l103] = 0.0f;
		}
		for (int l104 = 0; l104 < 2; l104 = l104 + 1) {
			fRec76[l104] = 0.0f;
		}
		for (int l105 = 0; l105 < 16384; l105 = l105 + 1) {
			fVec42[l105] = 0.0f;
		}
		for (int l106 = 0; l106 < 2; l106 = l106 + 1) {
			fVec43[l106] = 0.0f;
		}
		for (int l107 = 0; l107 < 2; l107 = l107 + 1) {
			fRec75[l107] = 0.0f;
		}
		for (int l108 = 0; l108 < 2; l108 = l108 + 1) {
			fRec72[l108] = 0.0f;
		}
		for (int l109 = 0; l109 < 16384; l109 = l109 + 1) {
			fVec44[l109] = 0.0f;
		}
		for (int l110 = 0; l110 < 2; l110 = l110 + 1) {
			fRec77[l110] = 0.0f;
		}
		for (int l111 = 0; l111 < 2; l111 = l111 + 1) {
			fVec45[l111] = 0.0f;
		}
		for (int l112 = 0; l112 < 2; l112 = l112 + 1) {
			fRec70[l112] = 0.0f;
		}
		for (int l113 = 0; l113 < 2; l113 = l113 + 1) {
			fRec68[l113] = 0.0f;
		}
		for (int l114 = 0; l114 < 2; l114 = l114 + 1) {
			fRec79[l114] = 0.0f;
		}
		for (int l115 = 0; l115 < 16384; l115 = l115 + 1) {
			fVec46[l115] = 0.0f;
		}
		for (int l116 = 0; l116 < 2; l116 = l116 + 1) {
			fVec47[l116] = 0.0f;
		}
		for (int l117 = 0; l117 < 2; l117 = l117 + 1) {
			fRec78[l117] = 0.0f;
		}
		for (int l118 = 0; l118 < 2; l118 = l118 + 1) {
			fRec69[l118] = 0.0f;
		}
		for (int l119 = 0; l119 < 16384; l119 = l119 + 1) {
			fVec48[l119] = 0.0f;
		}
		for (int l120 = 0; l120 < 2; l120 = l120 + 1) {
			fRec80[l120] = 0.0f;
		}
		for (int l121 = 0; l121 < 2; l121 = l121 + 1) {
			fVec49[l121] = 0.0f;
		}
		for (int l122 = 0; l122 < 2; l122 = l122 + 1) {
			fRec67[l122] = 0.0f;
		}
		for (int l123 = 0; l123 < 2; l123 = l123 + 1) {
			fRec65[l123] = 0.0f;
		}
		for (int l124 = 0; l124 < 2; l124 = l124 + 1) {
			fRec82[l124] = 0.0f;
		}
		for (int l125 = 0; l125 < 16384; l125 = l125 + 1) {
			fVec50[l125] = 0.0f;
		}
		for (int l126 = 0; l126 < 2; l126 = l126 + 1) {
			fVec51[l126] = 0.0f;
		}
		for (int l127 = 0; l127 < 2; l127 = l127 + 1) {
			fRec81[l127] = 0.0f;
		}
		for (int l128 = 0; l128 < 2; l128 = l128 + 1) {
			fRec66[l128] = 0.0f;
		}
		for (int l129 = 0; l129 < 16384; l129 = l129 + 1) {
			fVec52[l129] = 0.0f;
		}
		for (int l130 = 0; l130 < 2; l130 = l130 + 1) {
			fRec83[l130] = 0.0f;
		}
		for (int l131 = 0; l131 < 2; l131 = l131 + 1) {
			fVec53[l131] = 0.0f;
		}
		for (int l132 = 0; l132 < 2; l132 = l132 + 1) {
			fRec64[l132] = 0.0f;
		}
		for (int l133 = 0; l133 < 2; l133 = l133 + 1) {
			fRec62[l133] = 0.0f;
		}
		for (int l134 = 0; l134 < 2; l134 = l134 + 1) {
			fRec85[l134] = 0.0f;
		}
		for (int l135 = 0; l135 < 16384; l135 = l135 + 1) {
			fVec54[l135] = 0.0f;
		}
		for (int l136 = 0; l136 < 2; l136 = l136 + 1) {
			fVec55[l136] = 0.0f;
		}
		for (int l137 = 0; l137 < 2; l137 = l137 + 1) {
			fRec84[l137] = 0.0f;
		}
		for (int l138 = 0; l138 < 2; l138 = l138 + 1) {
			fRec63[l138] = 0.0f;
		}
		for (int l139 = 0; l139 < 2; l139 = l139 + 1) {
			fRec2[l139] = 0.0f;
		}
		for (int l140 = 0; l140 < 1024; l140 = l140 + 1) {
			fRec0[l140] = 0.0f;
		}
		for (int l141 = 0; l141 < 2; l141 = l141 + 1) {
			fRec86[l141] = 0.0f;
		}
		for (int l142 = 0; l142 < 1024; l142 = l142 + 1) {
			fRec1[l142] = 0.0f;
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
	
	GreyholeDsp* clone() {
		return new GreyholeDsp();
	}
	
	int getSampleRate() {
		return fSampleRate;
	}
	
	void buildUserInterface(UI* ui_interface) {
		ui_interface->openVerticalBox("greyhole");
		ui_interface->addHorizontalSlider("damp", &fHslider0, FAUSTFLOAT(0.3f), FAUSTFLOAT(0.0f), FAUSTFLOAT(0.95f), FAUSTFLOAT(0.001f));
		ui_interface->addHorizontalSlider("diffusion", &fHslider3, FAUSTFLOAT(0.5f), FAUSTFLOAT(0.0f), FAUSTFLOAT(0.99f), FAUSTFLOAT(0.001f));
		ui_interface->addHorizontalSlider("feedback", &fHslider1, FAUSTFLOAT(0.8f), FAUSTFLOAT(0.0f), FAUSTFLOAT(0.95f), FAUSTFLOAT(0.001f));
		ui_interface->addHorizontalSlider("modDepth", &fHslider2, FAUSTFLOAT(0.1f), FAUSTFLOAT(0.0f), FAUSTFLOAT(1.0f), FAUSTFLOAT(0.001f));
		ui_interface->addHorizontalSlider("size", &fHslider4, FAUSTFLOAT(1.5f), FAUSTFLOAT(0.5f), FAUSTFLOAT(3.0f), FAUSTFLOAT(0.001f));
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
		float fSlow3 = fConst1 * float(fHslider3);
		float fSlow4 = fConst1 * float(fHslider4);
		for (int i0 = 0; i0 < count; i0 = i0 + 1) {
			iVec0[0] = 1;
			fRec3[0] = fSlow0 + fConst2 * fRec3[1];
			float fTemp0 = std::min<float>(0.95f, std::max<float>(0.0f, fRec3[0]));
			fVec1[0] = fTemp0;
			float fTemp1 = fTemp0 + fVec1[1];
			fRec4[0] = fSlow1 + fConst2 * fRec4[1];
			float fTemp2 = std::min<float>(0.95f, std::max<float>(0.0f, fRec4[0]));
			fVec2[0] = fTemp2;
			float fTemp3 = fTemp2 + fVec2[1];
			fVec3[0] = 2.0f;
			float fTemp4 = fConst3 * (fVec3[1] + 2.0f);
			float fTemp5 = std::cos(fTemp4);
			float fTemp6 = std::sin(fTemp4);
			fRec5[0] = fRec6[1] * fTemp6 + fRec5[1] * fTemp5;
			int iTemp7 = 1 - iVec0[1];
			fRec6[0] = float(iTemp7) + fRec6[1] * fTemp5 - fTemp6 * fRec5[1];
			fRec7[0] = fSlow2 + fConst2 * fRec7[1];
			float fTemp8 = std::min<float>(1.0f, std::max<float>(0.0f, fRec7[0]));
			fVec4[0] = fTemp8;
			float fTemp9 = fTemp8 + fVec4[1];
			float fTemp10 = fConst4 * fTemp9 * (fRec6[0] + 1.0f);
			float fTemp11 = fTemp10 + 8.500005f;
			int iTemp12 = int(fTemp11);
			float fTemp13 = std::floor(fTemp11);
			float fTemp14 = fTemp10 + (7.0f - fTemp13);
			float fTemp15 = fTemp10 + (8.0f - fTemp13);
			float fTemp16 = fTemp10 + (9.0f - fTemp13);
			float fTemp17 = fTemp10 + (1e+01f - fTemp13);
			float fTemp18 = fTemp17 * fTemp16;
			float fTemp19 = fTemp18 * fTemp15;
			float fTemp20 = (fTemp10 + (6.0f - fTemp13)) * (fTemp14 * (fTemp15 * (0.041666668f * fRec0[(IOTA0 - (std::min<int>(512, std::max<int>(0, iTemp12)) + 1)) & 1023] * fTemp16 - 0.16666667f * fTemp17 * fRec0[(IOTA0 - (std::min<int>(512, std::max<int>(0, iTemp12 + 1)) + 1)) & 1023]) + 0.25f * fTemp18 * fRec0[(IOTA0 - (std::min<int>(512, std::max<int>(0, iTemp12 + 2)) + 1)) & 1023]) - 0.16666667f * fTemp19 * fRec0[(IOTA0 - (std::min<int>(512, std::max<int>(0, iTemp12 + 3)) + 1)) & 1023]) + 0.041666668f * fTemp19 * fTemp14 * fRec0[(IOTA0 - (std::min<int>(512, std::max<int>(0, iTemp12 + 4)) + 1)) & 1023];
			fVec5[IOTA0 & 131071] = fTemp20;
			float fTemp21 = ((fRec8[1] != 0.0f) ? (((fRec9[1] > 0.0f) & (fRec9[1] < 1.0f)) ? fRec8[1] : 0.0f) : (((fRec9[1] == 0.0f) & (fConst5 != fRec10[1])) ? 4.5351473e-05f : (((fRec9[1] == 1.0f) & (fConst5 != fRec11[1])) ? -4.5351473e-05f : 0.0f)));
			fRec8[0] = fTemp21;
			fRec9[0] = std::max<float>(0.0f, std::min<float>(1.0f, fRec9[1] + fTemp21));
			fRec10[0] = (((fRec9[1] >= 1.0f) & (fRec11[1] != fConst5)) ? fConst5 : fRec10[1]);
			fRec11[0] = (((fRec9[1] <= 0.0f) & (fRec10[1] != fConst5)) ? fConst5 : fRec11[1]);
			int iTemp22 = int(std::min<float>(65536.0f, std::max<float>(0.0f, fRec10[0])));
			float fTemp23 = fVec5[(IOTA0 - iTemp22) & 131071];
			int iTemp24 = int(std::min<float>(65536.0f, std::max<float>(0.0f, fRec11[0])));
			float fTemp25 = float(input0[i0]) + 0.5f * (fTemp23 + fRec9[0] * (fVec5[(IOTA0 - iTemp24) & 131071] - fTemp23)) * fTemp3;
			fRec12[0] = fSlow3 + fConst2 * fRec12[1];
			float fTemp26 = std::min<float>(0.99f, std::max<float>(0.0f, fRec12[0]));
			fVec6[0] = fTemp26;
			float fTemp27 = fTemp26 + fVec6[1];
			float fTemp28 = 0.5f * fTemp27;
			float fTemp29 = std::sin(fTemp28);
			float fTemp30 = std::cos(fTemp28);
			float fTemp31 = fConst4 * fTemp9 * (fRec5[0] + 1.0f);
			float fTemp32 = fTemp31 + 8.500005f;
			int iTemp33 = int(fTemp32);
			float fTemp34 = std::floor(fTemp32);
			float fTemp35 = fTemp31 + (7.0f - fTemp34);
			float fTemp36 = fTemp31 + (8.0f - fTemp34);
			float fTemp37 = fTemp31 + (9.0f - fTemp34);
			float fTemp38 = fTemp31 + (1e+01f - fTemp34);
			float fTemp39 = fTemp38 * fTemp37;
			float fTemp40 = fTemp39 * fTemp36;
			float fTemp41 = (fTemp31 + (6.0f - fTemp34)) * (fTemp35 * (fTemp36 * (0.041666668f * fRec1[(IOTA0 - (std::min<int>(512, std::max<int>(0, iTemp33)) + 1)) & 1023] * fTemp37 - 0.16666667f * fTemp38 * fRec1[(IOTA0 - (std::min<int>(512, std::max<int>(0, iTemp33 + 1)) + 1)) & 1023]) + 0.25f * fTemp39 * fRec1[(IOTA0 - (std::min<int>(512, std::max<int>(0, iTemp33 + 2)) + 1)) & 1023]) - 0.16666667f * fTemp40 * fRec1[(IOTA0 - (std::min<int>(512, std::max<int>(0, iTemp33 + 3)) + 1)) & 1023]) + 0.041666668f * fTemp40 * fTemp35 * fRec1[(IOTA0 - (std::min<int>(512, std::max<int>(0, iTemp33 + 4)) + 1)) & 1023];
			fVec7[IOTA0 & 131071] = fTemp41;
			float fTemp42 = fVec7[(IOTA0 - iTemp22) & 131071];
			float fTemp43 = float(input1[i0]) + 0.5f * fTemp3 * (fTemp42 + fRec9[0] * (fVec7[(IOTA0 - iTemp24) & 131071] - fTemp42));
			float fTemp44 = fTemp30 * fTemp43 - fTemp29 * fRec14[1];
			float fTemp45 = fTemp30 * fTemp44 - fTemp29 * fRec17[1];
			float fTemp46 = fTemp30 * fTemp45 - fTemp29 * fRec20[1];
			fRec26[0] = fSlow4 + fConst2 * fRec26[1];
			float fTemp47 = std::min<float>(3.0f, std::max<float>(0.5f, fRec26[0]));
			int iTemp48 = itbl0GreyholeDspSIG0[int(49.0f * fTemp47)];
			fRec25[0] = 0.9999f * (fRec25[1] + float(iTemp7 * iTemp48)) + 0.0001f * float(iTemp48);
			float fTemp49 = fRec25[0] + -1.49999f;
			float fTemp50 = std::floor(fTemp49);
			fVec8[IOTA0 & 16383] = fTemp29 * fRec23[1] - fTemp30 * fTemp46;
			float fTemp51 = fVec8[(IOTA0 - std::min<int>(8192, std::max<int>(0, int(fTemp49)))) & 16383];
			fVec9[0] = fTemp51;
			fRec24[0] = fVec9[1] - (fTemp50 + (2.0f - fRec25[0])) * (fRec24[1] - fTemp51) / (fRec25[0] - fTemp50);
			fRec22[0] = fRec24[0];
			int iTemp52 = itbl0GreyholeDspSIG0[int(59.0f * fTemp47)];
			fRec28[0] = 0.9999f * (fRec28[1] + float(iTemp7 * iTemp52)) + 0.0001f * float(iTemp52);
			float fTemp53 = fRec28[0] + -1.49999f;
			float fTemp54 = std::floor(fTemp53);
			float fTemp55 = fTemp25 * fTemp30 - fTemp29 * fRec13[1];
			float fTemp56 = fTemp30 * fTemp55 - fTemp29 * fRec16[1];
			float fTemp57 = fTemp30 * fTemp56 - fTemp29 * fRec19[1];
			fVec10[IOTA0 & 16383] = fTemp30 * fTemp57 - fTemp29 * fRec22[1];
			float fTemp58 = fVec10[(IOTA0 - std::min<int>(8192, std::max<int>(0, int(fTemp53)))) & 16383];
			fVec11[0] = fTemp58;
			fRec27[0] = fVec11[1] - (fTemp54 + (2.0f - fRec28[0])) * (fRec27[1] - fTemp58) / (fRec28[0] - fTemp54);
			fRec23[0] = fRec27[0];
			fVec12[IOTA0 & 16383] = fTemp30 * fRec23[1] + fTemp29 * fTemp46;
			int iTemp59 = itbl0GreyholeDspSIG0[int(36.0f * fTemp47)];
			fRec29[0] = 0.9999f * (fRec29[1] + float(iTemp7 * iTemp59)) + 0.0001f * float(iTemp59);
			float fTemp60 = fRec29[0] + -1.49999f;
			float fTemp61 = fVec12[(IOTA0 - std::min<int>(8192, std::max<int>(0, int(fTemp60)))) & 16383];
			fVec13[0] = fTemp61;
			float fTemp62 = std::floor(fTemp60);
			float fTemp63 = fRec29[0] - fTemp62;
			float fTemp64 = fTemp62 + (2.0f - fRec29[0]);
			fRec21[0] = -(fRec21[1] * fTemp64 / fTemp63 + fTemp64 * fTemp61 / fTemp63 + fVec13[1]);
			fRec19[0] = fRec21[0];
			int iTemp65 = itbl0GreyholeDspSIG0[int(46.0f * fTemp47)];
			fRec31[0] = 0.9999f * (fRec31[1] + float(iTemp7 * iTemp65)) + 0.0001f * float(iTemp65);
			float fTemp66 = fRec31[0] + -1.49999f;
			float fTemp67 = std::floor(fTemp66);
			fVec14[IOTA0 & 16383] = fRec22[1] * fTemp30 + fTemp29 * fTemp57;
			float fTemp68 = fVec14[(IOTA0 - std::min<int>(8192, std::max<int>(0, int(fTemp66)))) & 16383];
			fVec15[0] = fTemp68;
			fRec30[0] = fVec15[1] - (fTemp67 + (2.0f - fRec31[0])) * (fRec30[1] - fTemp68) / (fRec31[0] - fTemp67);
			fRec20[0] = fRec30[0];
			fVec16[IOTA0 & 16383] = fTemp30 * fRec20[1] + fTemp29 * fTemp45;
			int iTemp69 = itbl0GreyholeDspSIG0[int(23.0f * fTemp47)];
			fRec32[0] = 0.9999f * (fRec32[1] + float(iTemp7 * iTemp69)) + 0.0001f * float(iTemp69);
			float fTemp70 = fRec32[0] + -1.49999f;
			float fTemp71 = fVec16[(IOTA0 - std::min<int>(8192, std::max<int>(0, int(fTemp70)))) & 16383];
			fVec17[0] = fTemp71;
			float fTemp72 = std::floor(fTemp70);
			float fTemp73 = fRec32[0] - fTemp72;
			float fTemp74 = fTemp72 + (2.0f - fRec32[0]);
			fRec18[0] = -(fRec18[1] * fTemp74 / fTemp73 + fTemp74 * fTemp71 / fTemp73 + fVec17[1]);
			fRec16[0] = fRec18[0];
			int iTemp75 = itbl0GreyholeDspSIG0[int(33.0f * fTemp47)];
			fRec34[0] = 0.9999f * (fRec34[1] + float(iTemp7 * iTemp75)) + 0.0001f * float(iTemp75);
			float fTemp76 = fRec34[0] + -1.49999f;
			float fTemp77 = std::floor(fTemp76);
			fVec18[IOTA0 & 16383] = fRec19[1] * fTemp30 + fTemp29 * fTemp56;
			float fTemp78 = fVec18[(IOTA0 - std::min<int>(8192, std::max<int>(0, int(fTemp76)))) & 16383];
			fVec19[0] = fTemp78;
			fRec33[0] = fVec19[1] - (fTemp77 + (2.0f - fRec34[0])) * (fRec33[1] - fTemp78) / (fRec34[0] - fTemp77);
			fRec17[0] = fRec33[0];
			fVec20[IOTA0 & 16383] = fTemp30 * fRec17[1] + fTemp29 * fTemp44;
			int iTemp79 = itbl0GreyholeDspSIG0[int(1e+01f * fTemp47)];
			fRec35[0] = 0.9999f * (fRec35[1] + float(iTemp7 * iTemp79)) + 0.0001f * float(iTemp79);
			float fTemp80 = fRec35[0] + -1.49999f;
			float fTemp81 = fVec20[(IOTA0 - std::min<int>(8192, std::max<int>(0, int(fTemp80)))) & 16383];
			fVec21[0] = fTemp81;
			float fTemp82 = std::floor(fTemp80);
			float fTemp83 = fRec35[0] - fTemp82;
			float fTemp84 = fTemp82 + (2.0f - fRec35[0]);
			fRec15[0] = -(fRec15[1] * fTemp84 / fTemp83 + fTemp84 * fTemp81 / fTemp83 + fVec21[1]);
			fRec13[0] = fRec15[0];
			int iTemp85 = itbl0GreyholeDspSIG0[int(2e+01f * fTemp47)];
			fRec37[0] = 0.9999f * (fRec37[1] + float(iTemp7 * iTemp85)) + 0.0001f * float(iTemp85);
			float fTemp86 = fRec37[0] + -1.49999f;
			float fTemp87 = std::floor(fTemp86);
			fVec22[IOTA0 & 16383] = fRec16[1] * fTemp30 + fTemp29 * fTemp55;
			float fTemp88 = fVec22[(IOTA0 - std::min<int>(8192, std::max<int>(0, int(fTemp86)))) & 16383];
			fVec23[0] = fTemp88;
			fRec36[0] = fVec23[1] - (fTemp87 + (2.0f - fRec37[0])) * (fRec36[1] - fTemp88) / (fRec37[0] - fTemp87);
			fRec14[0] = fRec36[0];
			float fTemp89 = fRec13[1] * fTemp30 + fTemp29 * fTemp25;
			float fTemp90 = -0.5f * fTemp27;
			float fTemp91 = std::sin(fTemp90);
			float fTemp92 = std::cos(fTemp90);
			float fTemp93 = fTemp30 * fRec14[1] + fTemp29 * fTemp43;
			float fTemp94 = fTemp92 * fTemp93 - fTemp91 * fRec39[1];
			float fTemp95 = fTemp92 * fTemp94 - fTemp91 * fRec42[1];
			float fTemp96 = fTemp92 * fTemp95 - fTemp91 * fRec45[1];
			int iTemp97 = itbl0GreyholeDspSIG0[int(68.0f * fTemp47)];
			fRec50[0] = 0.9999f * (fRec50[1] + float(iTemp7 * iTemp97)) + 0.0001f * float(iTemp97);
			float fTemp98 = fRec50[0] + -1.49999f;
			float fTemp99 = std::floor(fTemp98);
			fVec24[IOTA0 & 16383] = fTemp91 * fRec48[1] - fTemp92 * fTemp96;
			float fTemp100 = fVec24[(IOTA0 - std::min<int>(8192, std::max<int>(0, int(fTemp98)))) & 16383];
			fVec25[0] = fTemp100;
			fRec49[0] = fVec25[1] - (fTemp99 + (2.0f - fRec50[0])) * (fRec49[1] - fTemp100) / (fRec50[0] - fTemp99);
			fRec47[0] = fRec49[0];
			int iTemp101 = itbl0GreyholeDspSIG0[int(78.0f * fTemp47)];
			fRec52[0] = 0.9999f * (fRec52[1] + float(iTemp7 * iTemp101)) + 0.0001f * float(iTemp101);
			float fTemp102 = fRec52[0] + -1.49999f;
			float fTemp103 = std::floor(fTemp102);
			float fTemp104 = fTemp89 * fTemp92 - fTemp91 * fRec38[1];
			float fTemp105 = fTemp92 * fTemp104 - fTemp91 * fRec41[1];
			float fTemp106 = fTemp92 * fTemp105 - fTemp91 * fRec44[1];
			fVec26[IOTA0 & 16383] = fTemp92 * fTemp106 - fRec47[1] * fTemp91;
			float fTemp107 = fVec26[(IOTA0 - std::min<int>(8192, std::max<int>(0, int(fTemp102)))) & 16383];
			fVec27[0] = fTemp107;
			fRec51[0] = fVec27[1] - (fTemp103 + (2.0f - fRec52[0])) * (fRec51[1] - fTemp107) / (fRec52[0] - fTemp103);
			fRec48[0] = fRec51[0];
			fVec28[IOTA0 & 16383] = fTemp92 * fRec48[1] + fTemp91 * fTemp96;
			int iTemp108 = itbl0GreyholeDspSIG0[int(55.0f * fTemp47)];
			fRec53[0] = 0.9999f * (fRec53[1] + float(iTemp7 * iTemp108)) + 0.0001f * float(iTemp108);
			float fTemp109 = fRec53[0] + -1.49999f;
			float fTemp110 = fVec28[(IOTA0 - std::min<int>(8192, std::max<int>(0, int(fTemp109)))) & 16383];
			fVec29[0] = fTemp110;
			float fTemp111 = std::floor(fTemp109);
			float fTemp112 = fRec53[0] - fTemp111;
			float fTemp113 = fTemp111 + (2.0f - fRec53[0]);
			fRec46[0] = -(fRec46[1] * fTemp113 / fTemp112 + fTemp113 * fTemp110 / fTemp112 + fVec29[1]);
			fRec44[0] = fRec46[0];
			int iTemp114 = itbl0GreyholeDspSIG0[int(65.0f * fTemp47)];
			fRec55[0] = 0.9999f * (fRec55[1] + float(iTemp7 * iTemp114)) + 0.0001f * float(iTemp114);
			float fTemp115 = fRec55[0] + -1.49999f;
			float fTemp116 = std::floor(fTemp115);
			fVec30[IOTA0 & 16383] = fRec47[1] * fTemp92 + fTemp91 * fTemp106;
			float fTemp117 = fVec30[(IOTA0 - std::min<int>(8192, std::max<int>(0, int(fTemp115)))) & 16383];
			fVec31[0] = fTemp117;
			fRec54[0] = fVec31[1] - (fTemp116 + (2.0f - fRec55[0])) * (fRec54[1] - fTemp117) / (fRec55[0] - fTemp116);
			fRec45[0] = fRec54[0];
			fVec32[IOTA0 & 16383] = fTemp92 * fRec45[1] + fTemp91 * fTemp95;
			int iTemp118 = itbl0GreyholeDspSIG0[int(42.0f * fTemp47)];
			fRec56[0] = 0.9999f * (fRec56[1] + float(iTemp7 * iTemp118)) + 0.0001f * float(iTemp118);
			float fTemp119 = fRec56[0] + -1.49999f;
			float fTemp120 = fVec32[(IOTA0 - std::min<int>(8192, std::max<int>(0, int(fTemp119)))) & 16383];
			fVec33[0] = fTemp120;
			float fTemp121 = std::floor(fTemp119);
			float fTemp122 = fRec56[0] - fTemp121;
			float fTemp123 = fTemp121 + (2.0f - fRec56[0]);
			fRec43[0] = -(fRec43[1] * fTemp123 / fTemp122 + fTemp123 * fTemp120 / fTemp122 + fVec33[1]);
			fRec41[0] = fRec43[0];
			int iTemp124 = itbl0GreyholeDspSIG0[int(52.0f * fTemp47)];
			fRec58[0] = 0.9999f * (fRec58[1] + float(iTemp7 * iTemp124)) + 0.0001f * float(iTemp124);
			float fTemp125 = fRec58[0] + -1.49999f;
			float fTemp126 = std::floor(fTemp125);
			fVec34[IOTA0 & 16383] = fRec44[1] * fTemp92 + fTemp91 * fTemp105;
			float fTemp127 = fVec34[(IOTA0 - std::min<int>(8192, std::max<int>(0, int(fTemp125)))) & 16383];
			fVec35[0] = fTemp127;
			fRec57[0] = fVec35[1] - (fTemp126 + (2.0f - fRec58[0])) * (fRec57[1] - fTemp127) / (fRec58[0] - fTemp126);
			fRec42[0] = fRec57[0];
			fVec36[IOTA0 & 16383] = fTemp92 * fRec42[1] + fTemp91 * fTemp94;
			int iTemp128 = itbl0GreyholeDspSIG0[int(29.0f * fTemp47)];
			fRec59[0] = 0.9999f * (fRec59[1] + float(iTemp7 * iTemp128)) + 0.0001f * float(iTemp128);
			float fTemp129 = fRec59[0] + -1.49999f;
			float fTemp130 = fVec36[(IOTA0 - std::min<int>(8192, std::max<int>(0, int(fTemp129)))) & 16383];
			fVec37[0] = fTemp130;
			float fTemp131 = std::floor(fTemp129);
			float fTemp132 = fRec59[0] - fTemp131;
			float fTemp133 = fTemp131 + (2.0f - fRec59[0]);
			fRec40[0] = -(fRec40[1] * fTemp133 / fTemp132 + fTemp133 * fTemp130 / fTemp132 + fVec37[1]);
			fRec38[0] = fRec40[0];
			int iTemp134 = itbl0GreyholeDspSIG0[int(39.0f * fTemp47)];
			fRec61[0] = 0.9999f * (fRec61[1] + float(iTemp7 * iTemp134)) + 0.0001f * float(iTemp134);
			float fTemp135 = fRec61[0] + -1.49999f;
			float fTemp136 = std::floor(fTemp135);
			fVec38[IOTA0 & 16383] = fRec41[1] * fTemp92 + fTemp91 * fTemp104;
			float fTemp137 = fVec38[(IOTA0 - std::min<int>(8192, std::max<int>(0, int(fTemp135)))) & 16383];
			fVec39[0] = fTemp137;
			fRec60[0] = fVec39[1] - (fTemp136 + (2.0f - fRec61[0])) * (fRec60[1] - fTemp137) / (fRec61[0] - fTemp136);
			fRec39[0] = fRec60[0];
			float fTemp138 = fRec38[1] * fTemp92 + fTemp91 * fTemp89;
			float fTemp139 = fTemp92 * fRec39[1] + fTemp91 * fTemp93;
			float fTemp140 = fTemp30 * fTemp139 - fTemp29 * fRec63[1];
			float fTemp141 = fTemp30 * fTemp140 - fTemp29 * fRec66[1];
			float fTemp142 = fTemp30 * fTemp141 - fTemp29 * fRec69[1];
			int iTemp143 = itbl0GreyholeDspSIG0[int(87.0f * fTemp47)];
			fRec74[0] = 0.9999f * (fRec74[1] + float(iTemp7 * iTemp143)) + 0.0001f * float(iTemp143);
			float fTemp144 = fRec74[0] + -1.49999f;
			float fTemp145 = std::floor(fTemp144);
			fVec40[IOTA0 & 16383] = fTemp29 * fRec72[1] - fTemp30 * fTemp142;
			float fTemp146 = fVec40[(IOTA0 - std::min<int>(8192, std::max<int>(0, int(fTemp144)))) & 16383];
			fVec41[0] = fTemp146;
			fRec73[0] = fVec41[1] - (fTemp145 + (2.0f - fRec74[0])) * (fRec73[1] - fTemp146) / (fRec74[0] - fTemp145);
			fRec71[0] = fRec73[0];
			int iTemp147 = itbl0GreyholeDspSIG0[int(97.0f * fTemp47)];
			fRec76[0] = 0.9999f * (fRec76[1] + float(iTemp7 * iTemp147)) + 0.0001f * float(iTemp147);
			float fTemp148 = fRec76[0] + -1.49999f;
			float fTemp149 = std::floor(fTemp148);
			float fTemp150 = fTemp30 * fTemp138 - fTemp29 * fRec62[1];
			float fTemp151 = fTemp30 * fTemp150 - fTemp29 * fRec65[1];
			float fTemp152 = fTemp30 * fTemp151 - fTemp29 * fRec68[1];
			fVec42[IOTA0 & 16383] = fTemp30 * fTemp152 - fRec71[1] * fTemp29;
			float fTemp153 = fVec42[(IOTA0 - std::min<int>(8192, std::max<int>(0, int(fTemp148)))) & 16383];
			fVec43[0] = fTemp153;
			fRec75[0] = fVec43[1] - (fTemp149 + (2.0f - fRec76[0])) * (fRec75[1] - fTemp153) / (fRec76[0] - fTemp149);
			fRec72[0] = fRec75[0];
			fVec44[IOTA0 & 16383] = fTemp30 * fRec72[1] + fTemp29 * fTemp142;
			int iTemp154 = itbl0GreyholeDspSIG0[int(74.0f * fTemp47)];
			fRec77[0] = 0.9999f * (fRec77[1] + float(iTemp7 * iTemp154)) + 0.0001f * float(iTemp154);
			float fTemp155 = fRec77[0] + -1.49999f;
			float fTemp156 = fVec44[(IOTA0 - std::min<int>(8192, std::max<int>(0, int(fTemp155)))) & 16383];
			fVec45[0] = fTemp156;
			float fTemp157 = std::floor(fTemp155);
			float fTemp158 = fRec77[0] - fTemp157;
			float fTemp159 = fTemp157 + (2.0f - fRec77[0]);
			fRec70[0] = -(fRec70[1] * fTemp159 / fTemp158 + fTemp159 * fTemp156 / fTemp158 + fVec45[1]);
			fRec68[0] = fRec70[0];
			int iTemp160 = itbl0GreyholeDspSIG0[int(84.0f * fTemp47)];
			fRec79[0] = 0.9999f * (fRec79[1] + float(iTemp7 * iTemp160)) + 0.0001f * float(iTemp160);
			float fTemp161 = fRec79[0] + -1.49999f;
			float fTemp162 = std::floor(fTemp161);
			fVec46[IOTA0 & 16383] = fRec71[1] * fTemp30 + fTemp29 * fTemp152;
			float fTemp163 = fVec46[(IOTA0 - std::min<int>(8192, std::max<int>(0, int(fTemp161)))) & 16383];
			fVec47[0] = fTemp163;
			fRec78[0] = fVec47[1] - (fTemp162 + (2.0f - fRec79[0])) * (fRec78[1] - fTemp163) / (fRec79[0] - fTemp162);
			fRec69[0] = fRec78[0];
			fVec48[IOTA0 & 16383] = fTemp30 * fRec69[1] + fTemp29 * fTemp141;
			int iTemp164 = itbl0GreyholeDspSIG0[int(61.0f * fTemp47)];
			fRec80[0] = 0.9999f * (fRec80[1] + float(iTemp7 * iTemp164)) + 0.0001f * float(iTemp164);
			float fTemp165 = fRec80[0] + -1.49999f;
			float fTemp166 = fVec48[(IOTA0 - std::min<int>(8192, std::max<int>(0, int(fTemp165)))) & 16383];
			fVec49[0] = fTemp166;
			float fTemp167 = std::floor(fTemp165);
			float fTemp168 = fRec80[0] - fTemp167;
			float fTemp169 = fTemp167 + (2.0f - fRec80[0]);
			fRec67[0] = -(fRec67[1] * fTemp169 / fTemp168 + fTemp169 * fTemp166 / fTemp168 + fVec49[1]);
			fRec65[0] = fRec67[0];
			int iTemp170 = itbl0GreyholeDspSIG0[int(71.0f * fTemp47)];
			fRec82[0] = 0.9999f * (fRec82[1] + float(iTemp7 * iTemp170)) + 0.0001f * float(iTemp170);
			float fTemp171 = fRec82[0] + -1.49999f;
			float fTemp172 = std::floor(fTemp171);
			fVec50[IOTA0 & 16383] = fRec68[1] * fTemp30 + fTemp29 * fTemp151;
			float fTemp173 = fVec50[(IOTA0 - std::min<int>(8192, std::max<int>(0, int(fTemp171)))) & 16383];
			fVec51[0] = fTemp173;
			fRec81[0] = fVec51[1] - (fTemp172 + (2.0f - fRec82[0])) * (fRec81[1] - fTemp173) / (fRec82[0] - fTemp172);
			fRec66[0] = fRec81[0];
			fVec52[IOTA0 & 16383] = fTemp30 * fRec66[1] + fTemp29 * fTemp140;
			int iTemp174 = itbl0GreyholeDspSIG0[int(48.0f * fTemp47)];
			fRec83[0] = 0.9999f * (fRec83[1] + float(iTemp7 * iTemp174)) + 0.0001f * float(iTemp174);
			float fTemp175 = fRec83[0] + -1.49999f;
			float fTemp176 = fVec52[(IOTA0 - std::min<int>(8192, std::max<int>(0, int(fTemp175)))) & 16383];
			fVec53[0] = fTemp176;
			float fTemp177 = std::floor(fTemp175);
			float fTemp178 = fRec83[0] - fTemp177;
			float fTemp179 = fTemp177 + (2.0f - fRec83[0]);
			fRec64[0] = -(fRec64[1] * fTemp179 / fTemp178 + fTemp179 * fTemp176 / fTemp178 + fVec53[1]);
			fRec62[0] = fRec64[0];
			int iTemp180 = itbl0GreyholeDspSIG0[int(58.0f * fTemp47)];
			fRec85[0] = 0.9999f * (fRec85[1] + float(iTemp7 * iTemp180)) + 0.0001f * float(iTemp180);
			float fTemp181 = fRec85[0] + -1.49999f;
			float fTemp182 = std::floor(fTemp181);
			fVec54[IOTA0 & 16383] = fRec65[1] * fTemp30 + fTemp29 * fTemp150;
			float fTemp183 = fVec54[(IOTA0 - std::min<int>(8192, std::max<int>(0, int(fTemp181)))) & 16383];
			fVec55[0] = fTemp183;
			fRec84[0] = fVec55[1] - (fTemp182 + (2.0f - fRec85[0])) * (fRec84[1] - fTemp183) / (fRec85[0] - fTemp182);
			fRec63[0] = fRec84[0];
			float fTemp184 = 1.0f - 0.5f * fTemp1;
			fRec2[0] = fTemp184 * (fRec62[1] * fTemp30 + fTemp29 * fTemp138) + 0.5f * fTemp1 * fRec2[1];
			fRec0[IOTA0 & 1023] = fRec2[0];
			fRec86[0] = fTemp184 * (fTemp30 * fRec63[1] + fTemp29 * fTemp139) + 0.5f * fTemp1 * fRec86[1];
			fRec1[IOTA0 & 1023] = fRec86[0];
			output0[i0] = FAUSTFLOAT(fRec0[IOTA0 & 1023]);
			output1[i0] = FAUSTFLOAT(fRec1[IOTA0 & 1023]);
			iVec0[1] = iVec0[0];
			fRec3[1] = fRec3[0];
			fVec1[1] = fVec1[0];
			fRec4[1] = fRec4[0];
			fVec2[1] = fVec2[0];
			IOTA0 = IOTA0 + 1;
			fVec3[1] = fVec3[0];
			fRec5[1] = fRec5[0];
			fRec6[1] = fRec6[0];
			fRec7[1] = fRec7[0];
			fVec4[1] = fVec4[0];
			fRec8[1] = fRec8[0];
			fRec9[1] = fRec9[0];
			fRec10[1] = fRec10[0];
			fRec11[1] = fRec11[0];
			fRec12[1] = fRec12[0];
			fVec6[1] = fVec6[0];
			fRec26[1] = fRec26[0];
			fRec25[1] = fRec25[0];
			fVec9[1] = fVec9[0];
			fRec24[1] = fRec24[0];
			fRec22[1] = fRec22[0];
			fRec28[1] = fRec28[0];
			fVec11[1] = fVec11[0];
			fRec27[1] = fRec27[0];
			fRec23[1] = fRec23[0];
			fRec29[1] = fRec29[0];
			fVec13[1] = fVec13[0];
			fRec21[1] = fRec21[0];
			fRec19[1] = fRec19[0];
			fRec31[1] = fRec31[0];
			fVec15[1] = fVec15[0];
			fRec30[1] = fRec30[0];
			fRec20[1] = fRec20[0];
			fRec32[1] = fRec32[0];
			fVec17[1] = fVec17[0];
			fRec18[1] = fRec18[0];
			fRec16[1] = fRec16[0];
			fRec34[1] = fRec34[0];
			fVec19[1] = fVec19[0];
			fRec33[1] = fRec33[0];
			fRec17[1] = fRec17[0];
			fRec35[1] = fRec35[0];
			fVec21[1] = fVec21[0];
			fRec15[1] = fRec15[0];
			fRec13[1] = fRec13[0];
			fRec37[1] = fRec37[0];
			fVec23[1] = fVec23[0];
			fRec36[1] = fRec36[0];
			fRec14[1] = fRec14[0];
			fRec50[1] = fRec50[0];
			fVec25[1] = fVec25[0];
			fRec49[1] = fRec49[0];
			fRec47[1] = fRec47[0];
			fRec52[1] = fRec52[0];
			fVec27[1] = fVec27[0];
			fRec51[1] = fRec51[0];
			fRec48[1] = fRec48[0];
			fRec53[1] = fRec53[0];
			fVec29[1] = fVec29[0];
			fRec46[1] = fRec46[0];
			fRec44[1] = fRec44[0];
			fRec55[1] = fRec55[0];
			fVec31[1] = fVec31[0];
			fRec54[1] = fRec54[0];
			fRec45[1] = fRec45[0];
			fRec56[1] = fRec56[0];
			fVec33[1] = fVec33[0];
			fRec43[1] = fRec43[0];
			fRec41[1] = fRec41[0];
			fRec58[1] = fRec58[0];
			fVec35[1] = fVec35[0];
			fRec57[1] = fRec57[0];
			fRec42[1] = fRec42[0];
			fRec59[1] = fRec59[0];
			fVec37[1] = fVec37[0];
			fRec40[1] = fRec40[0];
			fRec38[1] = fRec38[0];
			fRec61[1] = fRec61[0];
			fVec39[1] = fVec39[0];
			fRec60[1] = fRec60[0];
			fRec39[1] = fRec39[0];
			fRec74[1] = fRec74[0];
			fVec41[1] = fVec41[0];
			fRec73[1] = fRec73[0];
			fRec71[1] = fRec71[0];
			fRec76[1] = fRec76[0];
			fVec43[1] = fVec43[0];
			fRec75[1] = fRec75[0];
			fRec72[1] = fRec72[0];
			fRec77[1] = fRec77[0];
			fVec45[1] = fVec45[0];
			fRec70[1] = fRec70[0];
			fRec68[1] = fRec68[0];
			fRec79[1] = fRec79[0];
			fVec47[1] = fVec47[0];
			fRec78[1] = fRec78[0];
			fRec69[1] = fRec69[0];
			fRec80[1] = fRec80[0];
			fVec49[1] = fVec49[0];
			fRec67[1] = fRec67[0];
			fRec65[1] = fRec65[0];
			fRec82[1] = fRec82[0];
			fVec51[1] = fVec51[0];
			fRec81[1] = fRec81[0];
			fRec66[1] = fRec66[0];
			fRec83[1] = fRec83[0];
			fVec53[1] = fVec53[0];
			fRec64[1] = fRec64[0];
			fRec62[1] = fRec62[0];
			fRec85[1] = fRec85[0];
			fVec55[1] = fVec55[0];
			fRec84[1] = fRec84[0];
			fRec63[1] = fRec63[0];
			fRec2[1] = fRec2[0];
			fRec86[1] = fRec86[0];
		}
	}

};

#endif
