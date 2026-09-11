package net.itagguard

import android.content.Context
import android.media.RingtoneManager
import android.net.Uri

object Cfg {
    private fun p(c: Context) = c.getSharedPreferences("cfg", Context.MODE_PRIVATE)

    var Context.mac: String
        get() = p(this).getString("mac", "") ?: ""
        set(v) { p(this).edit().putString("mac", v.uppercase()).apply() }

    var Context.timeoutSec: Int
        get() = p(this).getInt("timeout", 15)
        set(v) { p(this).edit().putInt("timeout", v).apply() }

    var Context.confirmSec: Int
        get() = p(this).getInt("confirm", 5)
        set(v) { p(this).edit().putInt("confirm", v).apply() }

    var Context.soundUri: String
        get() = p(this).getString("sound", null)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION).toString()
        set(v) { p(this).edit().putString("sound", v).apply(); bumpChannel() }

    var Context.loudMode: Boolean
        get() = p(this).getBoolean("loud", true)
        set(v) { p(this).edit().putBoolean("loud", v).apply(); bumpChannel() }

    var Context.vibrate: Boolean
        get() = p(this).getBoolean("vib", true)
        set(v) { p(this).edit().putBoolean("vib", v).apply(); bumpChannel() }

    var Context.autostart: Boolean
        get() = p(this).getBoolean("autostart", true)
        set(v) { p(this).edit().putBoolean("autostart", v).apply() }

    val Context.channelVer: Int get() = p(this).getInt("chver", 1)

    private fun Context.bumpChannel() {
        p(this).edit().putInt("chver", channelVer + 1).apply()
    }

    fun Context.soundUriParsed(): Uri? =
        soundUri.takeIf { it.isNotEmpty() }?.let { Uri.parse(it) }
}
