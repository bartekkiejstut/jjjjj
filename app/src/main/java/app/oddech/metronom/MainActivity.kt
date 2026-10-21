package app.oddech.metronom

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.os.Vibrator
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

class MainActivity : Activity() {

    private val intervals = intArrayOf(5000, 5500, 6000)
    private val labels = arrayOf("5 s", "5,5 s", "6 s")

    private val rhythmButtons = ArrayList<Button>()
    private lateinit var startStop: Button
    private lateinit var strengthLabel: TextView
    private lateinit var rateLabel: TextView
    private lateinit var prefs: SharedPreferences
    private lateinit var vibrator: Vibrator

    private var lastTestPulse = 0L

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences(MetronomeService.PREFS, MODE_PRIVATE)
        vibrator = MetronomeService.getVibrator(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(24), dp(24), dp(24), dp(24))
        }
        // Android 15 wymusza edge-to-edge, więc odsuwamy treść od pasków systemowych.
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(
                dp(24) + bars.left,
                dp(24) + bars.top,
                dp(24) + bars.right,
                dp(24) + bars.bottom
            )
            insets
        }

        val title = TextView(this).apply {
            text = "Oddech koherentny"
            textSize = 24f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }
        root.addView(title, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(16); bottomMargin = dp(24) })

        // --- 3 przyciski rytmu ---
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for (i in intervals.indices) {
            val b = Button(this).apply {
                text = labels[i]
                textSize = 20f
                isAllCaps = false
                setTextColor(Color.WHITE)
                setOnClickListener {
                    prefs.edit().putInt(MetronomeService.KEY_INTERVAL, intervals[i]).apply()
                    refreshRhythm()
                }
            }
            rhythmButtons.add(b)
            row.addView(b, LinearLayout.LayoutParams(0, dp(72), 1f).apply {
                marginStart = dp(4); marginEnd = dp(4)
            })
        }
        root.addView(row, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        rateLabel = TextView(this).apply {
            textSize = 16f
            gravity = Gravity.CENTER
        }
        root.addView(rateLabel, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(12); bottomMargin = dp(32) })

        // --- Suwak siły ---
        strengthLabel = TextView(this).apply {
            textSize = 18f
            gravity = Gravity.CENTER
        }
        root.addView(strengthLabel, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        val savedAmp = prefs.getInt(MetronomeService.KEY_AMPLITUDE, MetronomeService.DEFAULT_AMPLITUDE)
            .coerceIn(1, 255)
        val seek = SeekBar(this).apply {
            max = 254 // progress 0..254 -> amplituda 1..255
            progress = savedAmp - 1
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                    val amp = progress + 1
                    updateStrengthLabel(amp)
                    if (fromUser) {
                        prefs.edit().putInt(MetronomeService.KEY_AMPLITUDE, amp).apply()
                        val now = SystemClock.elapsedRealtime()
                        if (now - lastTestPulse > 350) {
                            lastTestPulse = now
                            MetronomeService.pulse(vibrator, amp)
                        }
                    }
                }

                override fun onStartTrackingTouch(sb: SeekBar?) {}

                override fun onStopTrackingTouch(sb: SeekBar?) {
                    val amp = (sb?.progress ?: 0) + 1
                    prefs.edit().putInt(MetronomeService.KEY_AMPLITUDE, amp).apply()
                    lastTestPulse = SystemClock.elapsedRealtime()
                    MetronomeService.pulse(vibrator, amp)
                }
            })
        }
        root.addView(seek, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(56)
        ).apply { topMargin = dp(8) })
        updateStrengthLabel(savedAmp)

        // Rozpychacz, żeby duży przycisk był na dole.
        root.addView(View(this), LinearLayout.LayoutParams(0, 0, 1f))

        // --- START / STOP ---
        startStop = Button(this).apply {
            textSize = 32f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            setOnClickListener { toggle() }
        }
        root.addView(startStop, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(140)
        ))

        setContentView(root)
        refreshRhythm()
        refreshStartButton()
    }

    override fun onStart() {
        super.onStart()
        MetronomeService.listener = { runOnUiThread { refreshStartButton() } }
        refreshStartButton()
    }

    override fun onStop() {
        MetronomeService.listener = null
        super.onStop()
    }

    private fun toggle() {
        if (MetronomeService.isRunning) {
            startService(
                Intent(this, MetronomeService::class.java).setAction(MetronomeService.ACTION_STOP)
            )
        } else {
            requestNotificationPermissionIfNeeded()
            ContextCompat.startForegroundService(
                this,
                Intent(this, MetronomeService::class.java).setAction(MetronomeService.ACTION_START)
            )
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 100)
        }
    }

    private fun updateStrengthLabel(amp: Int) {
        strengthLabel.text = "Siła wibracji: $amp / 255"
    }

    private fun refreshRhythm() {
        val current = prefs.getInt(MetronomeService.KEY_INTERVAL, MetronomeService.DEFAULT_INTERVAL_MS)
        for (i in intervals.indices) {
            val selected = intervals[i] == current
            rhythmButtons[i].backgroundTintList = ColorStateList.valueOf(
                if (selected) Color.parseColor("#1E88E5") else Color.parseColor("#555555")
            )
        }
        val perMinute = 60000.0 / (2.0 * current)
        rateLabel.text = String.format("%.2f oddechu/min", perMinute).replace('.', ',')
    }

    private fun refreshStartButton() {
        val running = MetronomeService.isRunning
        startStop.text = if (running) "STOP" else "START"
        startStop.backgroundTintList = ColorStateList.valueOf(
            if (running) Color.parseColor("#D32F2F") else Color.parseColor("#2E7D32")
        )
    }
}
