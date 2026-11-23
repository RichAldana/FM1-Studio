// Compile-only stand-ins for the few AndroidX APIs FM1 Mix uses (the real libraries are fetched by Gradle on GitHub;
// these only let tools/android_check/check.sh type-check the app here). Signatures as in androidx.core 1.13 and
// androidx.activity 1.9.
@file:Suppress("unused", "UNUSED_PARAMETER")

package androidx.activity.result

fun interface ActivityResultCallback<O> { fun onActivityResult(result: O) }
abstract class ActivityResultLauncher<I> { abstract fun launch(input: I) }
