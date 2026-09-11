package net.itagguard

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothManager
import android.bluetooth.le.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.media.RingtoneManager
import android.net.Uri
import android.os.*
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.*
import androidx.core.content.ContextCompat
import net.itagguard.Cfg.autostart
import net.itagguard.Cfg.confirmSec
import net.itagguard.Cfg.loudMode
import net.itagguard.Cfg.mac
import net.itagguard.Cfg.soundUri
import net.itagguard.Cfg.timeoutSec
import net.itagguard.Cfg.vibrate
import java.text.SimpleDateFormat
import java.util.*

@SuppressLint("MissingPermission", "SetTextI18n")
class MainActivity : Activity() {

    private val h = Handler(Looper.getMainLooper())
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.ITALY)

    private lateinit var status: TextView
    private lateinit var detail: TextView
    private lateinit var logView: TextView
    private lateinit var macIn: EditText
    private lateinit var toIn: EditText
    private lateinit var cfIn: EditText
    private lateinit var soundLbl: TextView
    private lateinit var loudCb: CheckBox
    private lateinit var vibCb: CheckBox
    private lateinit var bootCb: CheckBox

    private val REQ_SOUND = 77

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        GuardService.ensureChannels(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(36, 48, 36, 48)
        }

        fun head(t: String) = root.addView(TextView(this).apply {
            text = t; textSize = 15f; setTypeface(null, Typeface.BOLD)
            setPadding(0, 34, 0, 8); setTextColor(Color.parseColor("#3367d6"))
        })
        fun label(t: String) = root.addView(TextView(this).apply {
            text = t; textSize = 13f; setPadding(0, 12, 0, 2)
        })
        fun btn(t: String, f: () -> Unit) = root.addView(Button(this).apply {
            text = t
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
                .apply { topMargin = 6 }
            setOnClickListener { f() }
        })
        fun row(vararg v: View) = root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            v.forEach { addView(it, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)) }
        })

        // ---- stato
        status = TextView(this).apply {
            textSize = 20f; setTypeface(null, Typeface.BOLD); gravity = Gravity.CENTER
            setPadding(0, 0, 0, 6)
        }
        detail = TextView(this).apply { textSize = 13f; gravity = Gravity.CENTER }
        root.addView(status); root.addView(detail)

        // ---- configurazione
        head("CONFIGURAZIONE")

        label("Indirizzo MAC del tag")
        macIn = EditText(this).apply {
            setText(mac.ifEmpty { "FF:FF:12:89:87:7A" })
            inputType = InputType.TYPE_CLASS_TEXT
            setSingleLine()
        }
        root.addView(macIn)
        btn("🔍 Cerca tag nelle vicinanze") { scanForTags() }

        label("Timeout: secondi di silenzio prima di sospettare la perdita")
        toIn = EditText(this).apply {
            setText(timeoutSec.toString()); inputType = InputType.TYPE_CLASS_NUMBER
        }
        root.addView(toIn)

        label("Conferma: secondi di verifica extra prima di notificare")
        cfIn = EditText(this).apply {
            setText(confirmSec.toString()); inputType = InputType.TYPE_CLASS_NUMBER
        }
        root.addView(cfIn)

        label("Suono della notifica")
        soundLbl = TextView(this).apply { textSize = 13f; setTextColor(Color.DKGRAY) }
        root.addView(soundLbl)
        btn("🔊 Scegli suono") { pickSound() }

        loudCb = CheckBox(this).apply {
            text = "Suona anche in modalità silenziosa (canale allarme)"
            isChecked = loudMode; textSize = 13f
        }
        vibCb = CheckBox(this).apply {
            text = "Vibrazione"; isChecked = vibrate; textSize = 13f
        }
        bootCb = CheckBox(this).apply {
            text = "Avvio automatico al riavvio del telefono"
            isChecked = autostart; textSize = 13f
        }
        root.addView(loudCb); root.addView(vibCb); root.addView(bootCb)

        btn("💾 SALVA E APPLICA") { saveAndApply() }

        // ---- sorveglianza
        head("SORVEGLIANZA")
        btn("▶ AVVIA monitoraggio") {
            saveAndApply(silent = true)
            if (mac.isEmpty()) { toast("Imposta prima il MAC"); return@btn }
            ContextCompat.startForegroundService(this, Intent(this, GuardService::class.java))
            toast("Servizio avviato")
        }
        btn("■ FERMA monitoraggio") {
            send(GuardService.ACT_STOP); toast("Servizio fermato")
        }
        btn("🔄 Azzera statistiche") {
            GuardService.maxGapMs = 0; send(GuardService.ACT_RESTART)
        }

        // ---- comandi tag
        head("COMANDI SUL TAG (connessione GATT)")
        root.addView(TextView(this).apply {
            text = "⚠ Alla disconnessione il tag emette un beep: è il suo firmware, " +
                   "non si può evitare."
            textSize = 12f; setTextColor(Color.parseColor("#a05000"))
        })
        row(Button(this).apply { text = "🔔 Suona"
                setOnClickListener { send(GuardService.ACT_RING) } },
            Button(this).apply { text = "🔇 Zittisci"
                setOnClickListener { send(GuardService.ACT_SILENCE) } })
        btn("⏏ Disconnetti dal tag") { send(GuardService.ACT_DISCONNECT) }

        // ---- permessi
        head("PERMESSI E BATTERIA")
        btn("1 · Concedi permessi Bluetooth e notifiche") { askPerms() }
        btn("2 · Escludi da ottimizzazione batteria") { askBattery() }
        btn("3 · Impostazioni app (autostart del produttore)") {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:$packageName")))
        }

        // ---- log
        head("LOG DIAGNOSTICO")
        logView = TextView(this).apply {
            textSize = 10.5f; typeface = Typeface.MONOSPACE
            setTextColor(Color.parseColor("#2b2b2b"))
        }
        root.addView(ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, 760)
            setBackgroundColor(Color.parseColor("#f0f0f0"))
            setPadding(10, 10, 10, 10)
            addView(logView)
        })
        row(Button(this).apply { text = "📤 Condividi"
                setOnClickListener { shareLog() } },
            Button(this).apply { text = "🗑 Pulisci"
                setOnClickListener { GuardService.logClear() } })

        setContentView(ScrollView(this).apply { addView(root) })
        refreshSoundLabel()
        tick()
    }

    /* ---------------- helpers ---------------- */

    private fun send(action: String) =
        ContextCompat.startForegroundService(this,
            Intent(this, GuardService::class.java).setAction(action))

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    private fun saveAndApply(silent: Boolean = false) {
        val m = macIn.text.toString().trim().uppercase()
        if (m.isNotEmpty() && !m.matches(Regex("([0-9A-F]{2}:){5}[0-9A-F]{2}"))) {
            toast("MAC non valido: usa il formato AA:BB:CC:DD:EE:FF"); return
        }
        mac = m
        timeoutSec = (toIn.text.toString().toIntOrNull() ?: 15).coerceIn(5, 600)
        confirmSec = (cfIn.text.toString().toIntOrNull() ?: 5).coerceIn(0, 120)
        loudMode = loudCb.isChecked
        vibrate = vibCb.isChecked
        autostart = bootCb.isChecked
        GuardService.ensureChannels(this)
        toIn.setText(timeoutSec.toString()); cfIn.setText(confirmSec.toString())
        if (GuardService.running) send(GuardService.ACT_RELOAD)
        if (!silent) toast("Configurazione salvata")
    }

    private fun refreshSoundLabel() {
        val u = soundUri
        val name = try {
            RingtoneManager.getRingtone(this, Uri.parse(u))?.getTitle(this) ?: u
        } catch (e: Exception) { u }
        soundLbl.text = "attuale: $name"
    }

    private fun pickSound() {
        startActivityForResult(Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
            putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_ALL)
            putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, "Suono per il tag perso")
            putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
            putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, Uri.parse(soundUri))
        }, REQ_SOUND)
    }

    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)
        if (req == REQ_SOUND && res == RESULT_OK) {
            val u: Uri? = data?.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
            if (u != null) {
                soundUri = u.toString()
                GuardService.ensureChannels(this)
                refreshSoundLabel()
                toast("Suono aggiornato")
            }
        }
    }

    private fun askPerms() {
        val need = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 31) {
            need += Manifest.permission.BLUETOOTH_SCAN
            need += Manifest.permission.BLUETOOTH_CONNECT
        } else need += Manifest.permission.ACCESS_FINE_LOCATION
        if (Build.VERSION.SDK_INT >= 33) need += Manifest.permission.POST_NOTIFICATIONS
        val missing = need.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) toast("Permessi già concessi")
        else requestPermissions(missing.toTypedArray(), 1)
    }

    private fun askBattery() {
        startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Uri.parse("package:$packageName")))
    }

    /* ---------------- ricerca tag ---------------- */

    private fun scanForTags() {
        val ad = getSystemService(BluetoothManager::class.java).adapter
        if (ad == null || !ad.isEnabled) { toast("Attiva il Bluetooth"); return }
        if (Build.VERSION.SDK_INT >= 31 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN)
            != PackageManager.PERMISSION_GRANTED) { askPerms(); return }

        val found = LinkedHashMap<String, Pair<String, Int>>()
        val sc = ad.bluetoothLeScanner
        val cb = object : ScanCallback() {
            override fun onScanResult(t: Int, r: ScanResult) {
                val nm = r.scanRecord?.deviceName ?: r.device.name ?: "(anonimo)"
                found[r.device.address] = nm to r.rssi
            }
        }
        val dlg = AlertDialog.Builder(this)
            .setTitle("Scansione in corso…")
            .setMessage("8 secondi. Tieni il tag vicino al telefono.")
            .setCancelable(false).show()

        sc.startScan(null,
            ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), cb)

        h.postDelayed({
            runCatching { sc.stopScan(cb) }
            dlg.dismiss()
            if (found.isEmpty()) { toast("Nessun dispositivo trovato"); return@postDelayed }
            val list = found.entries.sortedByDescending { it.value.second }
            val labels = list.map { "${it.value.first}\n${it.key} · ${it.value.second} dBm" }
            AlertDialog.Builder(this)
                .setTitle("Scegli il tag (${list.size} trovati)")
                .setItems(labels.toTypedArray()) { _, i ->
                    macIn.setText(list[i].key)
                    toast("MAC impostato — premi SALVA")
                }
                .setNegativeButton("Annulla", null)
                .show()
        }, 8000)
    }

    /* ---------------- log e refresh ---------------- */

    private fun shareLog() {
        val txt = buildString {
            append("iTag Guard\n")
            append("mac=${mac} timeout=${timeoutSec}s conferma=${confirmSec}s\n")
            append("gapMax=${GuardService.maxGapMs}ms rssi=${GuardService.rssi}\n\n")
            append(GuardService.logDump())
        }
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "iTag Guard log")
            putExtra(Intent.EXTRA_TEXT, txt)
        }, "Condividi log"))
    }

    private fun tick() {
        val run = GuardService.running
        status.text = if (run) "● ${GuardService.stateText}" else "○ servizio fermo"
        status.setTextColor(when {
            !run -> Color.GRAY
            GuardService.stateText.startsWith("TAG PERSO") -> Color.parseColor("#c62828")
            GuardService.stateText.startsWith("in portata") -> Color.parseColor("#2e7d32")
            else -> Color.parseColor("#ef6c00")
        })
        val seen = if (GuardService.lastSeenWall > 0)
            fmt.format(Date(GuardService.lastSeenWall)) else "—"
        detail.text = "ultimo contatto $seen · gap max ${GuardService.maxGapMs} ms · " +
                      "GATT: ${GuardService.gattText}"
        logView.text = GuardService.logDump().lines().takeLast(80).joinToString("\n")
        h.postDelayed({ tick() }, 1000)
    }

    override fun onDestroy() { h.removeCallbacksAndMessages(null); super.onDestroy() }
}
