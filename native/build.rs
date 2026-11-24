// The pad's effects (fx/fxengine.cpp, with Faust's generated code) built into the library.
fn main() {
    let target = std::env::var("TARGET").unwrap_or_default();
    let mut b = cc::Build::new();
    b.cpp(true)
        .file("fx/fxengine.cpp")
        .include("fx")
        .flag_if_supported("-std=c++17")
        .flag_if_supported("-fno-exceptions")
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

