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
        // one library, so the C++ runtime goes in statically (the NDK's c++_static and its c++abi)
        b.cpp_link_stdlib(Some("c++_static"));
        println!("cargo:rustc-link-lib=static=c++abi");
    }
    b.compile("fxengine");
    println!("cargo:rerun-if-changed=fx");
}
