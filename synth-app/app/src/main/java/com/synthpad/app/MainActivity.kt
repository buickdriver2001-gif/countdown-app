package com.synthpad.app

import android.app.Activity
import android.app.AlertDialog
import android.app.RecoverableSecurityException
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.MediaPlayer
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

class MainActivity : Activity() {
    companion object {
        /** A finished recording waiting for "Save or Discard" (SynthPad won't be closed while there is one). */
        @Volatile var pendingRecording: Recorder? = null
    }

    private lateinit var engine: SynthEngine
    private lateinit var keyboard: KeyboardView
    private lateinit var knobRow: LinearLayout
    private lateinit var tabRow: LinearLayout
    private lateinit var presetLabel: TextView
    private lateinit var octaveLabel: TextView
    private lateinit var recButton: TextView
    private lateinit var volumeKnob: KnobView
    private lateinit var tempoChip: TextView
    private val prefs by lazy { getSharedPreferences("synthpad", MODE_PRIVATE) }
    private val tapTimes = ArrayList<Long>()

    private var page = 0
    private var presetIndex = 0
    private val knobs = ArrayList<KnobView>()

    private var recorder: Recorder? = null
    private val handler = Handler(Looper.getMainLooper())
    private var player: MediaPlayer? = null

    private val d by lazy { resources.displayMetrics.density }
    private fun dp(v: Int) = (v * d).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // A crash report from before the latest update is out of date: drop it
        try {
            val updated = packageManager.getPackageInfo(packageName, 0).lastUpdateTime
            if (App.crashFile(this).lastModified() < updated) App.crashFile(this).delete()
        } catch (_: Exception) {
        }
        if (App.crashFile(this).exists()) {
            startActivity(Intent(this, CrashActivity::class.java))
            finish()
            return
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        engine = SynthEngine(this)
        engine.onError = { error ->
            App.saveCrash(this, "sound engine", error)
            runOnUiThread {
                startActivity(Intent(this, CrashActivity::class.java))
                finish()
            }
        }
        ParamMath.bpm = prefs.getFloat("bpm", 120f)
        ParamMath.sync = prefs.getBoolean("sync", true)
        engine.metronome = prefs.getBoolean("metronome", false)
        setContentView(buildUi())
        LaunchFade.attach(this, "SYNTHPAD")
        updateTempoChip()
        // Hide the status bar only once the screen exists (doing it earlier crashes on Android 16)
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                window.decorView.post { window.insetsController?.hide(WindowInsets.Type.statusBars()) }
            } else {
                @Suppress("DEPRECATION")
                window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
            }
        } catch (_: Exception) {
        }
        applyPreset(0)
        ready = true
    }

    private var ready = false

    /** If the app crashed last time, show what went wrong so it can be fixed. */
    private fun showCrashReport() {
        val file = App.crashFile(this)
        if (!file.exists() || isFinishing) return
        val report = file.readText()
        file.delete()
        val text = TextView(this).apply {
            this.text = report.lines().take(25).joinToString("\n")
            setTextIsSelectable(true)
            textSize = 11f
            setTextColor(Theme.TEXT)
            setPadding(dp(20), dp(8), dp(20), dp(8))
        }
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("SynthPad hit an error")
            .setMessage("Tap Copy and paste this to Claude so it can be fixed.")
            .setView(android.widget.ScrollView(this).apply { addView(text) })
            .setPositiveButton("Copy") { _, _ ->
                val cm = getSystemService(android.content.ClipboardManager::class.java)
                cm?.setPrimaryClip(android.content.ClipData.newPlainText("SynthPad error", report))
                Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Close", null)
            .show()
    }

    override fun onStart() {
        super.onStart()
        if (ready) engine.start()
    }

    override fun onStop() {
        super.onStop()
        if (!ready) return
        keyboard.releaseAll()
        if (recorder != null) stopRecording()
        engine.stop()
        stopPlayback()
    }

    // ------------------------------------------------------------------ UI building

    private fun pill(color: Int, radius: Int = 18, stroke: Int? = null): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = radius * d
            if (stroke != null) setStroke(dp(1), stroke)
        }

    private fun chip(text: String, onClick: () -> Unit): TextView = TextView(this).apply {
        this.text = text
        setTextColor(Theme.TEXT)
        textSize = 13f
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        gravity = Gravity.CENTER
        setPadding(dp(14), 0, dp(14), 0)
        background = pill(Theme.PANEL_LIGHT)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(34)).apply {
            marginStart = dp(6)
        }
        setOnClickListener { onClick() }
    }

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Theme.BG)
            setPadding(dp(12), dp(6), dp(12), dp(6))
        }

        // ---- Top bar
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        top.addView(chip("⌂") { HubLink.open(this) }.apply { contentDescription = "Back to NEXUS" })
        top.addView(TextView(this).apply {
            text = "SYNTHPAD"
            setTextColor(Theme.ACCENT)
            textSize = 16f
            letterSpacing = 0.2f
            typeface = Typeface.create("sans-serif-black", Typeface.NORMAL)
            setPadding(dp(4), 0, dp(10), 0)
        })
        top.addView(chip("‹") { applyPreset((presetIndex - 1 + PRESETS.size) % PRESETS.size) })
        presetLabel = chip("Preset") { choosePreset() }.apply {
            minWidth = dp(150)
            background = pill(Theme.PANEL_LIGHT, stroke = Theme.ACCENT_DIM)
        }
        top.addView(presetLabel)
        top.addView(chip("›") { applyPreset((presetIndex + 1) % PRESETS.size) })
        top.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        top.addView(chip("−") { shiftOctave(-1) })
        octaveLabel = TextView(this).apply {
            setTextColor(Theme.TEXT_MUTED)
            textSize = 12f
            gravity = Gravity.CENTER
            minWidth = dp(52)
        }
        top.addView(octaveLabel)
        top.addView(chip("+") { shiftOctave(1) }.apply {
            (layoutParams as LinearLayout.LayoutParams).marginStart = 0
        })
        tempoChip = chip("♩ 120") { showTempo() }.apply {
            (layoutParams as LinearLayout.LayoutParams).marginStart = dp(14)
            minWidth = dp(76)
            contentDescription = "Tempo"
        }
        top.addView(tempoChip)
        recButton = chip("●  REC") { toggleRecording() }.apply {
            setTextColor(Theme.REC)
            (layoutParams as LinearLayout.LayoutParams).marginStart = dp(14)
            minWidth = dp(92)
        }
        top.addView(recButton)
        top.addView(chip("♫  SAVED") { showRecordings() })
        root.addView(top, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(42)))

        // ---- Middle: scope + volume + knob pages
        val middle = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(6), 0, dp(6))
        }
        middle.addView(ScopeView(this, engine), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))

        volumeKnob = KnobView(this, P.VOLUME, engine.params[P.VOLUME.ordinal]) { p, v ->
            engine.params[p.ordinal] = v
        }
        middle.addView(FrameLayout(this).apply {
            addView(volumeKnob, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
            setPadding(dp(6), 0, dp(2), 0)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT))

        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = pill(Theme.PANEL, 14)
            setPadding(dp(8), dp(6), dp(8), 0)
        }
        tabRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        controls.addView(HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(tabRow)
        })
        knobRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        controls.addView(HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(knobRow)
        })
        middle.addView(controls, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.7f))

        root.addView(middle, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(132)))

        // ---- Keyboard
        keyboard = KeyboardView(this, { engine.noteOn(it) }, { engine.noteOff(it) })
        root.addView(keyboard, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        buildTabs()
        buildKnobs()
        updateOctave()
        return root
    }

    private fun buildTabs() {
        tabRow.removeAllViews()
        PAGES.forEachIndexed { i, pg ->
            tabRow.addView(TextView(this).apply {
                text = pg.name
                textSize = 11f
                letterSpacing = 0.12f
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                gravity = Gravity.CENTER
                setPadding(dp(12), 0, dp(12), 0)
                val selected = i == page
                setTextColor(if (selected) Theme.BG else Theme.TEXT_MUTED)
                background = if (selected) pill(Theme.ACCENT, 12) else null
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(26)).apply {
                    marginEnd = dp(2)
                }
                setOnClickListener {
                    page = i
                    buildTabs()
                    buildKnobs()
                }
            })
        }
    }

    private fun buildKnobs() {
        knobRow.removeAllViews()
        knobs.clear()
        for (p in PAGES[page].params) {
            val k = KnobView(this, p, engine.params[p.ordinal]) { param, v ->
                engine.params[param.ordinal] = v
            }
            knobs.add(k)
            knobRow.addView(k)
        }
    }

    // ------------------------------------------------------------------ presets & octave

    // ------------------------------------------------------------------ tempo

    private fun updateTempoChip() {
        tempoChip.text = "♩ ${ParamMath.bpm.roundToInt()}"
        tempoChip.contentDescription = "Tempo ${ParamMath.bpm.roundToInt()} BPM. Tap to change."
        for (k in knobs) k.invalidate()
    }

    private fun setBpm(v: Float) {
        ParamMath.bpm = v.coerceIn(60f, 200f)
        prefs.edit().putFloat("bpm", ParamMath.bpm).apply()
        updateTempoChip()
    }

    private fun showTempo() {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(8), dp(22), dp(4))
        }
        val big = TextView(this).apply {
            textSize = 42f
            setTextColor(Theme.ACCENT)
            gravity = Gravity.CENTER
            typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
        }
        val seek = android.widget.SeekBar(this).apply { max = 140 }
        fun refresh() {
            big.text = "${ParamMath.bpm.roundToInt()} BPM"
            seek.progress = ParamMath.bpm.roundToInt() - 60
        }
        box.addView(big)
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        for ((label, delta) in listOf("−5" to -5f, "−1" to -1f, "+1" to 1f, "+5" to 5f)) {
            row.addView(chip(label) { setBpm(ParamMath.bpm.roundToInt() + delta); refresh() }.apply {
                (layoutParams as LinearLayout.LayoutParams).height = dp(44)
                minWidth = dp(58)
            })
        }
        box.addView(row)
        seek.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) { setBpm(60f + progress); big.text = "${ParamMath.bpm.roundToInt()} BPM" }
            }
            override fun onStartTrackingTouch(sb: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(sb: android.widget.SeekBar?) {}
        })
        box.addView(seek, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)))
        box.addView(chip("👆  Tap tempo (tap along to the beat)") {
            val now = System.currentTimeMillis()
            if (tapTimes.isNotEmpty() && now - tapTimes.last() > 2000) tapTimes.clear()
            tapTimes.add(now)
            if (tapTimes.size > 5) tapTimes.removeAt(0)
            if (tapTimes.size >= 2) {
                val avg = (tapTimes.last() - tapTimes.first()).toFloat() / (tapTimes.size - 1)
                setBpm(60000f / avg)
                refresh()
            }
        }.apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)).apply { topMargin = dp(6) }
        })
        fun sw(label: String, on: Boolean, change: (Boolean) -> Unit) = android.widget.Switch(this).apply {
            text = label
            isChecked = on
            setTextColor(Theme.TEXT)
            textSize = 15f
            setPadding(0, dp(10), 0, dp(10))
            setOnCheckedChangeListener { _, c -> change(c) }
        }
        box.addView(sw("Sync effects to tempo (arp, gate, echo, wobble, auto-pan)", ParamMath.sync) {
            ParamMath.sync = it; prefs.edit().putBoolean("sync", it).apply(); updateTempoChip()
        })
        box.addView(sw("Metronome click (not recorded)", engine.metronome) {
            engine.metronome = it; prefs.edit().putBoolean("metronome", it).apply()
        })
        refresh()
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Tempo")
            .setView(android.widget.ScrollView(this).apply { addView(box) })
            .setPositiveButton("Done", null)
            .show()
    }

    private fun applyPreset(i: Int) {
        presetIndex = i
        val values = PRESETS[i].resolved(engine.params[P.VOLUME.ordinal])
        System.arraycopy(values, 0, engine.params, 0, values.size)
        presetLabel.text = "${PRESETS[i].name}  ▾"
        for (k in knobs) k.setValue(engine.params[k.param.ordinal])
    }

    private fun choosePreset() {
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Sound presets")
            .setSingleChoiceItems(PRESETS.map { it.name }.toTypedArray(), presetIndex) { dlg, which ->
                applyPreset(which)
                dlg.dismiss()
            }
            .show()
    }

    private fun shiftOctave(delta: Int) {
        val next = (keyboard.baseNote + delta * 12).coerceIn(24, 84)
        keyboard.setBase(next)
        updateOctave()
    }

    private fun updateOctave() {
        octaveLabel.text = "OCT ${keyboard.baseNote / 12 - 1}"
    }

    // ------------------------------------------------------------------ recording

    private val recTick = object : Runnable {
        override fun run() {
            val r = recorder ?: return
            val s = r.seconds.toInt()
            val dot = if ((System.currentTimeMillis() / 500) % 2 == 0L) "●" else "○"
            recButton.text = String.format(Locale.US, "%s  %d:%02d", dot, s / 60, s % 60)
            handler.postDelayed(this, 250)
        }
    }

    private fun toggleRecording() {
        if (recorder == null) startRecording() else stopRecording()
    }

    private fun startRecording() {
        val file = File(cacheDir, "take_${System.currentTimeMillis()}.raw")
        val r = Recorder(file, engine.sampleRate)
        recorder = r
        engine.recorder = r
        recButton.background = pill(Theme.REC)
        recButton.setTextColor(Theme.TEXT)
        handler.post(recTick)
        Toast.makeText(this, "Recording… play something, then tap again to stop", Toast.LENGTH_SHORT).show()
    }

    private fun stopRecording() {
        val r = recorder ?: return
        pendingRecording = r
        engine.recorder = null
        recorder = null
        handler.removeCallbacks(recTick)
        recButton.text = "●  REC"
        recButton.background = pill(Theme.PANEL_LIGHT)
        recButton.setTextColor(Theme.REC)
        Thread {
            r.finish()
            runOnUiThread { askToSave(r) }
        }.start()
    }

    private fun askToSave(r: Recorder) {
        if (isDestroyed) { pendingRecording = null; r.file.delete(); return }
        if (r.frames < engine.sampleRate / 4) {
            pendingRecording = null
            r.file.delete()
            Toast.makeText(this, "Recording was too short", Toast.LENGTH_SHORT).show()
            return
        }
        val input = EditText(this).apply {
            setText("SynthPad " + SimpleDateFormat("yyyy-MM-dd HH.mm", Locale.US).format(Date()))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            selectAll()
        }
        val box = FrameLayout(this).apply {
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(input)
        }
        val secs = r.seconds.toInt()
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(String.format(Locale.US, "Save recording (%d:%02d)", secs / 60, secs % 60))
            .setMessage("Saved as a .wav file in your phone's Music/SynthPad folder.")
            .setView(box)
            .setCancelable(false)
            .setPositiveButton("Save") { _, _ ->
                pendingRecording = null
                val name = input.text.toString()
                Thread {
                    val ok = WavStore.save(applicationContext, r.file, r.sampleRate, name)
                    runOnUiThread {
                        Toast.makeText(
                            this,
                            if (ok) "Saved to Music/SynthPad" else "Couldn't save the recording",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }.start()
            }
            .setNegativeButton("Discard") { _, _ -> pendingRecording = null; r.file.delete() }
            .show()
    }

    // ------------------------------------------------------------------ saved recordings

    private fun showRecordings() {
        val items = WavStore.list(this, engine.sampleRate)
        val builder = AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Saved recordings")
            .setNegativeButton("Close", null)
        if (items.isEmpty()) {
            builder.setMessage("No recordings yet.\n\nTap REC, play something, then tap it again to save a .wav file.")
        } else {
            val labels = items.map {
                String.format(Locale.US, "%s   (%d:%02d)", it.name, it.seconds / 60, it.seconds % 60)
            }.toTypedArray()
            builder.setItems(labels) { _, which -> recordingOptions(items[which]) }
        }
        builder.show()
    }

    private fun recordingOptions(item: WavStore.Item) {
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(item.name)
            .setItems(arrayOf("▶  Play", "↗  Share", "✕  Delete")) { _, which ->
                when (which) {
                    0 -> play(item)
                    1 -> share(item)
                    2 -> delete(item)
                }
            }
            .show()
    }

    private fun play(item: WavStore.Item) {
        stopPlayback()
        try {
            val mp = MediaPlayer()
            mp.setDataSource(this, item.uri)
            mp.prepare()
            mp.start()
            player = mp
            val dlg = AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Playing")
                .setMessage(item.name)
                .setPositiveButton("Stop", null)
                .setOnDismissListener { stopPlayback() }
                .show()
            mp.setOnCompletionListener { if (dlg.isShowing) dlg.dismiss() }
        } catch (e: Exception) {
            Toast.makeText(this, "Couldn't play this file", Toast.LENGTH_SHORT).show()
        }
    }

    private fun stopPlayback() {
        player?.let {
            try { it.stop() } catch (_: Exception) {}
            it.release()
        }
        player = null
    }

    private fun share(item: WavStore.Item) {
        val send = Intent(Intent.ACTION_SEND)
            .setType("audio/wav")
            .putExtra(Intent.EXTRA_STREAM, item.uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(Intent.createChooser(send, "Share recording"))
    }

    private fun delete(item: WavStore.Item) {
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Delete \"${item.name}\"?")
            .setPositiveButton("Delete") { _, _ ->
                try {
                    contentResolver.delete(item.uri, null, null)
                    Toast.makeText(this, "Deleted", Toast.LENGTH_SHORT).show()
                } catch (e: RecoverableSecurityException) {
                    try {
                        @Suppress("DEPRECATION")
                        startIntentSenderForResult(e.userAction.actionIntent.intentSender, 7, null, 0, 0, 0)
                    } catch (_: Exception) {
                        Toast.makeText(this, "Delete it from your Files app instead", Toast.LENGTH_LONG).show()
                    }
                } catch (e: Exception) {
                    Toast.makeText(this, "Delete it from your Files app instead", Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
