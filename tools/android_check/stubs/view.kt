@file:Suppress("unused", "UNUSED_PARAMETER")
package androidx.core.view

import android.view.View

class Insets(@JvmField val left: Int, @JvmField val top: Int, @JvmField val right: Int, @JvmField val bottom: Int)

class WindowInsetsCompat {
    fun getInsets(typeMask: Int): Insets = Insets(0, 0, 0, 0)
    object Type {
        @JvmStatic fun systemBars(): Int = 0
        @JvmStatic fun displayCutout(): Int = 0
    }
}

fun interface OnApplyWindowInsetsListener { fun onApplyWindowInsets(v: View, insets: WindowInsetsCompat): WindowInsetsCompat }

object ViewCompat {
    @JvmStatic fun setOnApplyWindowInsetsListener(v: View, listener: OnApplyWindowInsetsListener?) {}
}
