package com.countdown.app

import android.app.Activity
import android.app.AlertDialog
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.format.DateFormat
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import java.util.Calendar

class MainActivity : Activity() {

    private lateinit var titleText: TextView
    private lateinit var countdownText: TextView
    private lateinit var endsText: TextView
    private lateinit var nameInput: EditText
    private lateinit var selectedText: TextView

    /** The date/time picked in the editor (saved when the user taps Save). */
    private var pendingTarget: Long = 0L

    private val handler = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() {
            renderCountdown()
            handler.postDelayed(this, 250)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        titleText = findViewById(R.id.title_text)
        countdownText = findViewById(R.id.countdown_text)
        endsText = findViewById(R.id.ends_text)
        nameInput = findViewById(R.id.name_input)
        selectedText = findViewById(R.id.selected_text)

        val savedName = CountdownStore.name(this)
        nameInput.setText(if (CountdownStore.target(this) == 0L) "" else savedName)
        pendingTarget = CountdownStore.target(this)
        renderSelected()

        findViewById<Button>(R.id.pick_button).setOnClickListener { pickDateTime() }
        findViewById<Button>(R.id.save_button).setOnClickListener { save() }
        findViewById<Button>(R.id.widget_button).setOnClickListener { addWidget() }
        findViewById<Button>(R.id.glow_button).setOnClickListener { pickGlow() }
    }

    override fun onResume() {
        super.onResume()
        handler.post(ticker)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(ticker)
    }

    private fun pickGlow() {
        val all = Glow.values()
        val current = CountdownStore.glow(this)
        AlertDialog.Builder(this)
            .setTitle("Widget glow")
            .setSingleChoiceItems(all.map { it.label }.toTypedArray(), all.indexOf(current)) { dlg, which ->
                CountdownStore.setGlow(this, all[which])
                CountdownWidget.updateAll(this)
                renderCountdown()
                dlg.dismiss()
            }
            .show()
    }

    private fun renderCountdown() {
        val target = CountdownStore.target(this)
        val glow = CountdownStore.glow(this).resolve(target - System.currentTimeMillis())
        findViewById<View>(R.id.card).setBackgroundResource(if (glow == Glow.NONE) R.drawable.card_bg else glow.drawable)
        countdownText.setTextColor(glow.color)
        findViewById<Button>(R.id.glow_button).text = "Widget glow: " + CountdownStore.glow(this).label.substringBefore(" (").substringBefore(":")
        titleText.text = CountdownStore.name(this)
        if (target == 0L) {
            countdownText.text = "--:--:--"
            endsText.text = "Pick a date and time below to start"
            return
        }
        val remaining = target - System.currentTimeMillis()
        if (remaining <= 0) {
            countdownText.text = "00:00:00"
            endsText.text = "Time's up! 🎉"
        } else {
            countdownText.text = CountdownStore.format(remaining)
            endsText.text = "Ends " + describe(target)
        }
    }

    private fun renderSelected() {
        selectedText.text = if (pendingTarget == 0L) "No date picked yet" else describe(pendingTarget)
    }

    private fun describe(millis: Long): String {
        val date = DateFormat.format("EEE, MMM d, yyyy", millis)
        val time = DateFormat.getTimeFormat(this).format(millis)
        return "$date at $time"
    }

    private fun pickDateTime() {
        val cal = Calendar.getInstance()
        cal.timeInMillis = if (pendingTarget > System.currentTimeMillis()) {
            pendingTarget
        } else {
            System.currentTimeMillis() + 24L * 60 * 60 * 1000
        }

        val dateDialog = DatePickerDialog(
            this,
            { _, year, month, day ->
                TimePickerDialog(
                    this,
                    { _, hour, minute ->
                        cal.set(year, month, day, hour, minute, 0)
                        cal.set(Calendar.MILLISECOND, 0)
                        pendingTarget = cal.timeInMillis
                        renderSelected()
                    },
                    cal.get(Calendar.HOUR_OF_DAY),
                    cal.get(Calendar.MINUTE),
                    DateFormat.is24HourFormat(this)
                ).show()
            },
            cal.get(Calendar.YEAR),
            cal.get(Calendar.MONTH),
            cal.get(Calendar.DAY_OF_MONTH)
        )
        dateDialog.datePicker.minDate = System.currentTimeMillis() - 1000
        dateDialog.show()
    }

    private fun save() {
        if (pendingTarget <= System.currentTimeMillis()) {
            toast("Pick a date and time in the future first")
            return
        }
        val name = nameInput.text.toString().ifBlank { CountdownStore.DEFAULT_NAME }
        CountdownStore.save(this, name, pendingTarget)
        CountdownWidget.updateAll(this)
        renderCountdown()
        nameInput.clearFocus()
        toast("Countdown saved")
    }

    private fun addWidget() {
        val mgr = getSystemService(AppWidgetManager::class.java)
        if (mgr != null && mgr.isRequestPinAppWidgetSupported) {
            mgr.requestPinAppWidget(ComponentName(this, CountdownWidget::class.java), null, null)
        } else {
            toast("Long-press your home screen, tap Widgets, then find Countdown")
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
}
