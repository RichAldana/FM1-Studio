@file:Suppress("unused", "UNUSED_PARAMETER")
package androidx.core.content

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter

object ContextCompat {
    const val RECEIVER_EXPORTED = 0x2
    const val RECEIVER_NOT_EXPORTED = 0x4
    @JvmStatic fun checkSelfPermission(context: Context, permission: String): Int = 0
    @JvmStatic fun registerReceiver(context: Context, receiver: BroadcastReceiver?, filter: IntentFilter, flags: Int): Intent? = null
}

open class FileProvider : android.content.ContentProvider() {
    companion object {
        @JvmStatic fun getUriForFile(context: Context, authority: String, file: java.io.File): android.net.Uri = android.net.Uri.EMPTY
    }
    override fun onCreate() = true
    override fun query(uri: android.net.Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?): android.database.Cursor? = null
    override fun getType(uri: android.net.Uri): String? = null
    override fun insert(uri: android.net.Uri, values: android.content.ContentValues?): android.net.Uri? = null
    override fun delete(uri: android.net.Uri, s: String?, a: Array<out String>?) = 0
    override fun update(uri: android.net.Uri, v: android.content.ContentValues?, s: String?, a: Array<out String>?) = 0
}
