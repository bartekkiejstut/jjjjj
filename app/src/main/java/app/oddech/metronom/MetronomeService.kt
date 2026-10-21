package app.oddech.metronom

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat

class MetronomeService : Service() {

    companion object {
        const val ACTION_START = "app.oddech.metronom.START"
        const val ACTION_STOP = "app.oddech.metronom.STOP"

        const val PREFS = "metronome_prefs"
        const val KEY_INTERVAL = "interval_ms"
        const val KEY_AMPLITUDE = "amplitude_v2" // nowy klucz = reset zapisanej siły do wartości domyślnej

        const val DEFAULT_INTERVAL_MS = 5500
        const val DEFAULT_AMPLITUDE = 255
        const val PULSE_MS = 150L // długość pojedynczego impulsu
        const val GAP_MS = 110L   // przerwa między dwoma impulsami

        private const val CHANNEL_ID = "metronome_channel"
        private const val NOTIFICATION_ID = 1

        @Volatile
        var isRunning: Boolean = false
            private set

        // Wywoływany zawsze w wątku głównym.
        var listener: ((Boolean) -> Unit)? = null

        fun getVibrator(context: Context): Vibrator {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                vm.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }
        }

        // Podwójny impuls: wibracja - przerwa - wibracja.
        @Suppress("DEPRECATION")
        fun pulse(vibrator: Vibrator, amplitude: Int) {
            val timings = longArrayOf(0, PULSE_MS, GAP_MS, PULSE_MS)
            val effect = if (vibrator.hasAmplitudeControl()) {
                val a = amplitude.coerceIn(1, 255)
                VibrationEffect.createWaveform(timings, intArrayOf(0, a, 0, a), -1)
            } else {
                VibrationEffect.createWaveform(timings, -1)
            }
            // USAGE_ALARM: wibracja działa przy zgaszonym ekranie i nie jest
            // wyciszana jak "dotyk/klawiatura".
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            vibrator.vibrate(effect, attrs)
        }
    }

    private lateinit var prefs: SharedPreferences
    private lateinit var vibrator: Vibrator
    private var wakeLock: PowerManager.WakeLock? = null
    private var worker: Thread? = null

    @Volatile
    private var active = false

    override fun onCreate() {
        super.onCreate()
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        vibrator = getVibrator(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopEverything()
            return START_NOT_STICKY
        }

        createChannel()
        val notification = buildNotification()
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)

        acquireWakeLock()
        startLoop()

        isRunning = true
        listener?.invoke(true)
        return START_NOT_STICKY
    }

    private fun startLoop() {
        if (worker?.isAlive == true) return
        active = true
        worker = Thread({
            var next = SystemClock.elapsedRealtime()
            while (active) {
                val wait = next - SystemClock.elapsedRealtime()
                if (wait > 0) {
                    try {
                        Thread.sleep(wait)
                    } catch (e: InterruptedException) {
                        break
                    }
                }
                if (!active) break

                pulse(vibrator, prefs.getInt(KEY_AMPLITUDE, DEFAULT_AMPLITUDE))

                val interval = prefs.getInt(KEY_INTERVAL, DEFAULT_INTERVAL_MS).toLong()
                next += interval
                val now = SystemClock.elapsedRealtime()
                if (next < now) next = now + interval // dogonienie po zawieszeniu
            }
        }, "metronome-loop").also { it.start() }
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "oddech:metronome").apply {
            setReferenceCounted(false)
            acquire(8 * 60 * 60 * 1000L) // maks. 8 h
        }
    }

    private fun stopEverything() {
        active = false
        worker?.interrupt()
        vibrator.cancel()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun createChannel() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Metronom oddechu",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Stałe powiadomienie podczas działania metronomu"
                setSound(null, null)
                enableVibration(false)
            }
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, MetronomeService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentTitle("Oddech koherentny")
            .setContentText("Metronom wibracyjny działa")
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(android.R.drawable.ic_media_pause, "STOP", stopIntent)
            .build()
    }

    override fun onDestroy() {
        active = false
        worker?.interrupt()
        vibrator.cancel()
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        isRunning = false
        listener?.invoke(false)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
