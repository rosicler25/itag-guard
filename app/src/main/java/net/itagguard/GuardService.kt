package net.itagguard

import android.annotation.SuppressLint
import android.app.*
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.*
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.os.*
import android.util.Log
import androidx.core.app.NotificationCompat
import net.itagguard.Cfg.autostart
import net.itagguard.Cfg.channelVer
import net.itagguard.Cfg.confirmSec
import net.itagguard.Cfg.loudMode
import net.itagguard.Cfg.mac
import net.itagguard.Cfg.soundUriParsed
import net.itagguard.Cfg.timeoutSec
import net.itagguard.Cfg.vibrate
import java.text.SimpleDateFormat
import android.Manifest
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import java.util.*

@SuppressLint("MissingPermission")
class GuardService : Service() {

    companion object {
        const val NID_STATUS = 1
        const val NID_ALERT = 2
        const val CH_STATUS = "status"

        const val ACT_START = "start"
        const val ACT_STOP = "stop"
        const val ACT_RING = "ring"
        const val ACT_SILENCE = "silence"
        const val ACT_DISCONNECT = "disconnect"
        const val ACT_RESTART = "restart"
        const val ACT_ALERT_DISMISSED = "alert_dismissed"
        const val ACT_RELOAD = "reload"

        @Volatile var running = false
        @Volatile var stateText = "fermo"
        @Volatile var rssi = 0
        @Volatile var maxGapMs = 0L
        @Volatile var lastSeenWall = 0L
        @Volatile var gattText = "non connesso"

        private val buf = ArrayDeque<String>()
        private val fmt = SimpleDateFormat("HH:mm:ss", Locale.ITALY)

        fun logLine(s: String) {
            synchronized(buf) {
                buf.addLast("${fmt.format(Date())}  $s")
                while (buf.size > 500) buf.removeFirst()
            }
            Log.i("Guard", s)
        }

        fun logDump(): String = synchronized(buf) { buf.joinToString("\n") }
        fun logClear() = synchronized(buf) { buf.clear() }

        fun alertChannelId(c: Context) = "alert_v${c.channelVer}"

        fun ensureChannels(c: Context) {
            val nm = c.getSystemService(NotificationManager::class.java)

            if (nm.getNotificationChannel(CH_STATUS) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(CH_STATUS, "Stato sorveglianza",
                        NotificationManager.IMPORTANCE_LOW).apply {
                        setSound(null, null)
                        enableVibration(false)
                        setShowBadge(false)
                        description = "Notifica permanente del servizio"
                    })
            }

            val id = alertChannelId(c)
            if (nm.getNotificationChannel(id) == null) {
                nm.notificationChannels
                    .filter { it.id.startsWith("alert_v") && it.id != id }
                    .forEach { nm.deleteNotificationChannel(it.id) }

                val usage = if (c.loudMode) AudioAttributes.USAGE_ALARM
                            else AudioAttributes.USAGE_NOTIFICATION
                nm.createNotificationChannel(
                    NotificationChannel(id, "Allarme tag perso",
                        NotificationManager.IMPORTANCE_HIGH).apply {
                        setSound(c.soundUriParsed(), AudioAttributes.Builder()
                            .setUsage(usage)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build())
                        enableVibration(c.vibrate)
                        vibrationPattern = longArrayOf(0, 500, 300, 500)
                        setShowBadge(true)
                        description = "Suona una volta quando il tag esce dal raggio"
                    })
            }
        }
    }

    private lateinit var h: Handler
    private lateinit var nm: NotificationManager
    private var scanner: BluetoothLeScanner? = null

    private var macAddr = ""
    private var timeoutMs = 15_000L
    private var confirmMs = 5_000L

    private var lastSeenEl = 0L
    private var confirmStart = 0L
    private var lost = false
    private var alertVisible = false
    private var lostAtWall = 0L
    private var backAtWall = 0L
    private var scanMode = ScanSettings.SCAN_MODE_BALANCED
    private var btOn = true

    private var liveDevice: BluetoothDevice? = null
    private var gatt: BluetoothGatt? = null
    private var wl: PowerManager.WakeLock? = null

    /* ------------------------------------------------ scan */

    private val scanCb = object : ScanCallback() {
        override fun onScanResult(type: Int, r: ScanResult) {
            if (!r.device.address.equals(macAddr, true)) return
            liveDevice = r.device
            val now = SystemClock.elapsedRealtime()

            if (lastSeenEl > 0) {
                val gap = now - lastSeenEl
                if (gap > maxGapMs) { maxGapMs = gap; logLine("nuovo gap max ${gap} ms (rssi ${r.rssi})") }
                else if (gap > 4000) logLine("gap ${gap} ms (rssi ${r.rssi})")
            } else logLine("primo pacchetto ricevuto, rssi ${r.rssi}")

            lastSeenEl = now
            lastSeenWall = System.currentTimeMillis()
            rssi = r.rssi
            confirmStart = 0

            if (lost) {
                lost = false
                backAtWall = System.currentTimeMillis()
                logLine("TAG TORNATO IN LINEA")
                if (alertVisible) showAlert(recovered = true, playSound = false)
            }
            if (scanMode == ScanSettings.SCAN_MODE_LOW_LATENCY)
                restartScan(ScanSettings.SCAN_MODE_BALANCED)
        }

        override fun onScanFailed(err: Int) {
            logLine("onScanFailed($err)")
            h.postDelayed({ if (running) restartScan(scanMode) }, 30_000)
        }
    }

    private fun scanPermissionGranted(): Boolean {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Manifest.permission.BLUETOOTH_SCAN
        } else {
            Manifest.permission.ACCESS_FINE_LOCATION
        }
    
        return ContextCompat.checkSelfPermission(this, permission) ==
            PackageManager.PERMISSION_GRANTED
    }
    
    private fun scanPermissionErrorText(): String {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            "Permesso Bluetooth non concesso"
        } else {
            "Permesso Posizione non concesso"
        }
    }

    private fun restartScan(mode: Int) {
        if (macAddr.isEmpty()) {
            stateText = "MAC non impostato"
            return
        }
    
        if (!scanPermissionGranted()) {
            stateText = scanPermissionErrorText()
            logLine("scan non avviato: $stateText")
            updateStatusNotif()
            return
        }
        val ad = (getSystemService(BluetoothManager::class.java)).adapter
        if (ad == null || !ad.isEnabled) { btOn = false; stateText = "Bluetooth spento"; return }
        btOn = true
        scanner = ad.bluetoothLeScanner
        try { scanner?.stopScan(scanCb) } catch (_: Exception) {}
        scanMode = mode
        // il filtro è OBBLIGATORIO: senza, a schermo spento Android non consegna risultati
        val filters = listOf(ScanFilter.Builder().setDeviceAddress(macAddr).build())
        val settings = ScanSettings.Builder()
            .setScanMode(mode)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE)
            .setNumOfMatches(ScanSettings.MATCH_NUM_ONE_ADVERTISEMENT)
            .setReportDelay(0)
            .build()
        try {
            scanner?.startScan(filters, settings, scanCb)
            logLine("scan avviato (${if (mode == ScanSettings.SCAN_MODE_LOW_LATENCY) "LOW_LATENCY" else "BALANCED"})")
        } catch (e: Exception) { logLine("startScan fallito: ${e.message}") }
    }

    /* ------------------------------------------------ watchdog */

    private val watchdog = object : Runnable {
        override fun run() {
            if (btOn && lastSeenEl > 0L && !lost) {
                val age = SystemClock.elapsedRealtime() - lastSeenEl
                if (age > timeoutMs) {
                    if (confirmStart == 0L) {
                        confirmStart = SystemClock.elapsedRealtime()
                        restartScan(ScanSettings.SCAN_MODE_LOW_LATENCY)
                        logLine("timeout superato, verifica in corso")
                    } else if (SystemClock.elapsedRealtime() - confirmStart > confirmMs) {
                        lost = true
                        lostAtWall = System.currentTimeMillis()
                        logLine("TAG PERSO — notifica inviata")
                        showAlert(recovered = false, playSound = true)
                    }
                }
            }
            if (alertVisible && lost) showAlert(recovered = false, playSound = false)

            stateText = when {
                !btOn -> "Bluetooth spento"
                !scanPermissionGranted() -> scanPermissionErrorText()
                macAddr.isEmpty() -> "MAC non impostato"
                lastSeenEl == 0L -> "in attesa del primo segnale…"
                lost -> "TAG PERSO"
                confirmStart > 0L -> "verifica in corso…"
                else -> "in portata · $rssi dBm"
            }
            updateStatusNotif()
            h.postDelayed(this, 2000)
        }
    }

    /* ------------------------------------------------ lifecycle */

    override fun onCreate() {
        super.onCreate()
        h = Handler(Looper.getMainLooper())
        nm = getSystemService(NotificationManager::class.java)
        ensureChannels(this)
        startFg()
        reloadCfg()
        registerReceiver(btRx, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED))
        wl = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "itag:scan").apply { acquire() }
        running = true
        logLine("=== servizio avviato ===")
        restartScan(ScanSettings.SCAN_MODE_BALANCED)
        h.post(watchdog)
    }

    private fun startFg() {
        val n = buildStatusNotif()
        if (Build.VERSION.SDK_INT >= 29)
            startForeground(NID_STATUS, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        else startForeground(NID_STATUS, n)
    }

    private fun reloadCfg() {
        macAddr = mac
        timeoutMs = timeoutSec.toLong() * 1000
        confirmMs = confirmSec.toLong() * 1000
        ensureChannels(this)
        logLine("config: mac=$macAddr timeout=${timeoutSec}s conferma=${confirmSec}s")
    }

    override fun onStartCommand(i: Intent?, flags: Int, id: Int): Int {
        when (i?.action) {
            ACT_STOP -> { stopSelf(); return START_NOT_STICKY }
            ACT_RING -> ringTag()
            ACT_SILENCE -> writeAlert(0)
            ACT_DISCONNECT -> disconnectGatt()
            ACT_RESTART -> { lastSeenEl = 0; maxGapMs = 0; lost = false
                             confirmStart = 0; restartScan(ScanSettings.SCAN_MODE_BALANCED) }
            ACT_ALERT_DISMISSED -> { alertVisible = false; logLine("notifica di allarme scartata") }
            ACT_RELOAD -> { reloadCfg(); lastSeenEl = 0; lost = false; confirmStart = 0
                            restartScan(ScanSettings.SCAN_MODE_BALANCED) }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        logLine("=== servizio fermato ===")
        h.removeCallbacksAndMessages(null)
        try { scanner?.stopScan(scanCb) } catch (_: Exception) {}
        runCatching { unregisterReceiver(btRx) }
        closeGatt()
        wl?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }

    override fun onBind(i: Intent?): IBinder? = null

    private val btRx = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            when (i.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1)) {
                BluetoothAdapter.STATE_OFF -> {
                    btOn = false; logLine("Bluetooth OFF — sorveglianza sospesa")
                }
                BluetoothAdapter.STATE_ON -> h.postDelayed({
                    btOn = true; lastSeenEl = 0; lost = false; confirmStart = 0
                    logLine("Bluetooth ON — riavvio scan")
                    restartScan(ScanSettings.SCAN_MODE_BALANCED)
                }, 2000)
            }
        }
    }

    /* ------------------------------------------------ notifiche */

    private fun pi(action: String, code: Int): PendingIntent =
        PendingIntent.getService(this, code,
            Intent(this, GuardService::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    private fun buildStatusNotif(): Notification {
        val open = PendingIntent.getActivity(this, 100,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE)
        val sub = if (lastSeenWall > 0)
            "ultimo contatto ${fmt.format(Date(lastSeenWall))}" else "nessun contatto"
        return NotificationCompat.Builder(this, CH_STATUS)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentTitle("iTag Guard · $stateText")
            .setContentText(sub)
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(open)
            .addAction(0, "Suona", pi(ACT_RING, 101))
            .addAction(0, "Riavvia", pi(ACT_RESTART, 102))
            .build()
    }

    private fun updateStatusNotif() = nm.notify(NID_STATUS, buildStatusNotif())

    private fun showAlert(recovered: Boolean, playSound: Boolean) {
        val open = PendingIntent.getActivity(this, 200,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE)

        val title: String
        val text: String
        if (recovered) {
            title = "✔ Tag di nuovo in linea"
            text = "Tornato alle ${fmt.format(Date(backAtWall))} · " +
                   "era sparito alle ${fmt.format(Date(lostAtWall))}"
        } else {
            title = "⚠ Tag fuori portata"
            val mins = ((System.currentTimeMillis() - lastSeenWall) / 60000).toInt()
            val ago = when {
                mins < 1 -> "meno di un minuto fa"
                mins == 1 -> "1 minuto fa"
                mins < 60 -> "$mins minuti fa"
                else -> "${mins / 60} h ${mins % 60} min fa"
            }
            text = "Ultimo contatto ${fmt.format(Date(lastSeenWall))} ($ago)"
        }

        val n = NotificationCompat.Builder(this, alertChannelId(this))
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(false)
            .setOngoing(false)
            .setOnlyAlertOnce(true)          // gli aggiornamenti non risuonano
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setWhen(if (recovered) backAtWall else lostAtWall)
            .setShowWhen(true)
            .setContentIntent(open)
            .setDeleteIntent(pi(ACT_ALERT_DISMISSED, 201))
            .addAction(0, "Suona tag", pi(ACT_RING, 202))
            .build()

        if (playSound && alertVisible) nm.cancel(NID_ALERT)  // forza il suono su un nuovo evento
        nm.notify(NID_ALERT, n)
        alertVisible = true
    }

    /* ------------------------------------------------ GATT */

    private fun targetDevice(): BluetoothDevice? {
        liveDevice?.let { return it }
        val ad = getSystemService(BluetoothManager::class.java).adapter ?: return null
        return try {
            if (Build.VERSION.SDK_INT >= 31)
                ad.getRemoteLeDevice(macAddr, BluetoothDevice.ADDRESS_TYPE_RANDOM)
            else ad.getRemoteDevice(macAddr)
        } catch (e: Exception) { logLine("device non risolvibile: ${e.message}"); null }
    }

    private val gattCb = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    gattText = "connesso"; logLine("GATT connesso (status $status)")
                    g.discoverServices()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    gattText = "non connesso"
                    logLine("GATT disconnesso (status $status) — il tag emetterà un beep")
                    runCatching { g.close() }
                    if (gatt === g) gatt = null
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            logLine("servizi scoperti: ${g.services.size}")
            gattText = "connesso, invio comando"
            writeAlert(1)
        }

        override fun onCharacteristicWrite(g: BluetoothGatt,
                                           c: BluetoothGattCharacteristic, status: Int) {
            logLine("write ${c.uuid.toString().substring(4, 8)} status=$status")
            gattText = if (status == 0) "comando inviato" else "errore write $status"
        }
    }

    private fun ringTag() {
        if (macAddr.isEmpty()) return
        if (gatt != null) { writeAlert(1); return }
        val d = targetDevice() ?: run { logLine("tag non ancora visto: impossibile connettersi"); return }
        gattText = "connessione…"
        logLine("connessione GATT per far suonare")
        gatt = d.connectGatt(this, false, gattCb, BluetoothDevice.TRANSPORT_LE)
    }

    private fun writeAlert(level: Int) {
        val g = gatt ?: return
        val svc = g.getService(UUID_ALERT_SVC) ?: run { logLine("servizio 0x1802 assente"); return }
        val ch = svc.getCharacteristic(UUID_ALERT_CHR) ?: run { logLine("char 0x2A06 assente"); return }
        val v = byteArrayOf(level.toByte())
        if (Build.VERSION.SDK_INT >= 33) {
            g.writeCharacteristic(ch, v, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE)
        } else {
            @Suppress("DEPRECATION")
            run {
                ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                ch.value = v
                g.writeCharacteristic(ch)
            }
        }
        logLine("alert level = $level")
    }

    private fun disconnectGatt() {
        gatt?.let { logLine("disconnessione GATT richiesta"); it.disconnect() }
            ?: run { gattText = "non connesso" }
    }

    private fun closeGatt() {
        gatt?.let { runCatching { it.disconnect() }; runCatching { it.close() } }
        gatt = null; gattText = "non connesso"
    }

    private val UUID_ALERT_SVC: java.util.UUID =
        java.util.UUID.fromString("00001802-0000-1000-8000-00805f9b34fb")
    private val UUID_ALERT_CHR: java.util.UUID =
        java.util.UUID.fromString("00002a06-0000-1000-8000-00805f9b34fb")
}
