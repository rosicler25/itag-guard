package net.itagguard

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import net.itagguard.Cfg.autostart
import net.itagguard.Cfg.mac

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        if (!c.autostart || c.mac.isEmpty()) return
        runCatching {
            ContextCompat.startForegroundService(c, Intent(c, GuardService::class.java))
        }
    }
}
