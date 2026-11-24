// The pad's effects (fx/: fxengine.cpp with Faust's generated code, and the wrappers of Airwindows, Surge's sst
// libraries and the Eurorack-derived ones with their vendored sources in fx/third_party) built into the library.

/// the Airwindows sources (fx/third_party/airwin2rack/src)
const AIRWIN: &[&str] = &[
    "airwin_consolidated_base.cpp",
    "autogen_airwin/Isolator3.cpp", "autogen_airwin/Isolator3Proc.cpp",
    "autogen_airwin/kCosmos.cpp", "autogen_airwin/kCosmosProc.cpp",
    "autogen_airwin/CrunchCoat.cpp", "autogen_airwin/CrunchCoatProc.cpp",
    "autogen_airwin/DeRez2.cpp", "autogen_airwin/DeRez2Proc.cpp",
    "autogen_airwin/BeziComp.cpp", "autogen_airwin/BeziCompProc.cpp",
];

/// Clouds, Rings, Warps and the stmlib they need (fx/third_party/eurorack)
const EURORACK: &[&str] = &[
    "stmlib/utils/random.cc", "stmlib/dsp/atan.cc", "stmlib/dsp/units.cc",
    "clouds/dsp/correlator.cc", "clouds/dsp/granular_processor.cc", "clouds/dsp/mu_law.cc",
    "clouds/dsp/pvoc/frame_transformation.cc", "clouds/dsp/pvoc/phase_vocoder.cc", "clouds/dsp/pvoc/stft.cc",
    "clouds/resources.cc",
    "rings/dsp/fm_voice.cc", "rings/dsp/part.cc", "rings/dsp/string_synth_part.cc", "rings/dsp/string.cc",
    "rings/dsp/resonator.cc", "rings/resources.cc",
    "warps/dsp/modulator.cc", "warps/dsp/oscillator.cc", "warps/dsp/vocoder.cc", "warps/dsp/filter_bank.cc",
    "warps/resources.cc",
];

/// Surge XT's spring reverb and BBD delay lines (fx/third_party/surge, chowdsp)
const SURGE: &[&str] = &[
    "spring_reverb/SpringReverbProc.cpp", "shared/StateVariableFilter.cpp", "bbd_utils/BBDDelayLine.cpp",
];

fn main() {
    let target = std::env::var("TARGET").unwrap_or_default();
    let mut b = cc::Build::new();
    b.cpp(true)
        .file("fx/fxengine.cpp")
        .file("fx/airwin.cpp")
        .file("fx/surge.cpp")
        .file("fx/mi.cpp")
        .files(AIRWIN.iter().map(|f| format!("fx/third_party/airwin2rack/src/{f}")))
        .files(EURORACK.iter().map(|f| format!("fx/third_party/eurorack/{f}")))
        .files(SURGE.iter().map(|f| format!("fx/third_party/surge/src/common/dsp/effects/chowdsp/{f}")))
        .include("fx")
        .include("fx/third_party/eurorack")
        .include("fx/third_party/fmt/include")
        .include("fx/third_party/sst/sst-basic-blocks/include")
        .include("fx/third_party/sst/sst-filters/include")
        .include("fx/third_party/sst/sst-effects/include")
        .include("fx/third_party/sst/sst-waveshapers/include")
        .include("fx/third_party/simde")
        .include("fx/third_party/surge/shim")
        .include("fx/third_party/surge/src/common")
        .include("fx/third_party/surge/src/common/dsp")
        .include("fx/third_party/surge/src/common/dsp/vembertech")
        .include("fx/third_party/surge/src/common/dsp/utilities")
        .include("fx/third_party/surge/src/common/dsp/effects")
        .define("TEST", None)                       // stmlib: the portable code paths, not the STM32's
        .define("FMT_HEADER_ONLY", None)
        .define("SURGE_COMPILE_BLOCK_SIZE", "32")
        .warnings(false)
        .flag_if_supported("-std=c++20")
        .flag_if_supported("-Wno-unused-parameter")
        .flag_if_supported("-Wno-unused-variable")
        .flag_if_supported("-Wno-unused-function")
        .opt_level(3);
    if target.contains("android") {
        // one library, so the C++ runtime goes in statically: the NDK's c++_static, then its c++abi
        b.cpp_link_stdlib(Some("c++_static"));
    }
    // compile() declares fxengine then c++_static to the linker; c++abi has to come after both (a static library
    // only gives what was asked for before it), or the .so is left with holes and won't load on the phone
    b.compile("fxengine");
    if target.contains("android") {
        // rustc looks for a "static=" library itself and doesn't know the NDK's folders, so c++abi goes to the
        // linker (NDK clang) as a plain -lc++abi: clang knows where it is, and there is only libc++abi.a.
        // No -L to that folder: it also holds libc.a, which would then win over the phone's libc.so.
        println!("cargo:rustc-link-lib=c++abi");
        // a symbol nobody provides fails the build here instead of the app on the phone
        println!("cargo:rustc-cdylib-link-arg=-Wl,--no-undefined");
    }
    println!("cargo:rerun-if-changed=fx");
}

