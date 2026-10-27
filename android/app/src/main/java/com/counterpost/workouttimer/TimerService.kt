package com.counterpost.workouttimer

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.speech.tts.TextToSpeech
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import kotlin.math.ceil

/**
 * Runs the workout timer in the foreground so it keeps going with the screen off.
 * Each phase end is also set as an alarm clock, which wakes the phone even in deep sleep.
 * Beeps use the alarm stream, so they sound in silent mode too.
 */
class TimerService : Service() {

    data class Phase(val name: String, val sec: Int, val set: Int)

    companion object {
        const val ACTION_START = "start"
        const val ACTION_PAUSE = "pause"
        const val ACTION_RESUME = "resume"
        const val ACTION_SKIP = "skip"
        const val ACTION_PREV = "prev"
        const val ACTION_STOP = "stop"
        const val ACTION_TICK = "tick"
        const val EXTRA_CFG = "cfg"
        private const val CHANNEL = "timer"
        private const val NOTIF_ID = 1

        @Volatile var stateJson: String = "{\"running\":false}"
        @Volatile var isAlive = false

        fun markStarting() { stateJson = "{\"running\":false,\"starting\":true}" }
    }

    private val handler = Handler(Looper.getMainLooper())
    private val loop = object : Runnable {
        override fun run() {
            tick()
            if (running) handler.postDelayed(this, 100)
        }
    }

    private var phases = listOf<Phase>()
    private var phasesJson = "[]"
    private var sets = 1
    private var sound = true
    private var voice = true
    private var idx = 0
    private var endAt = 0L      // elapsedRealtime when the current phase ends
    private var remain = 0L     // ms left, used while paused
    private var paused = false
    private var running = false
    private var lastSec = -1

    private var tone: ToneGenerator? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        isAlive = true
        try { tone = ToneGenerator(AudioManager.STREAM_ALARM, 100) } catch (_: Exception) {}
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.JAPAN
                tts?.setAudioAttributes(alarmAttrs())
                ttsReady = true
            }
        }
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "タイマー実行中", NotificationManager.IMPORTANCE_LOW).apply {
                setShowBadge(false)
            }
        )
    }

    override fun onDestroy() {
        isAlive = false
        handler.removeCallbacksAndMessages(null)
        cancelAlarm()
        releaseWake()
        tone?.release(); tone = null
        tts?.shutdown(); tts = null
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                goForeground()
                begin(intent.getStringExtra(EXTRA_CFG) ?: "{}")
            }
            ACTION_PAUSE -> pause()
            ACTION_RESUME -> resume()
            ACTION_SKIP -> skip()
            ACTION_PREV -> prev()
            ACTION_STOP -> stopTimer()
            ACTION_TICK -> tick()
        }
        if (!running && intent?.action != ACTION_START) {
            // Nothing to run (e.g. a stale alarm after the process was killed).
            if (phases.isEmpty()) stopSelf()
        }
        return START_NOT_STICKY
    }

    // ---------- timer ----------

    private fun begin(cfg: String) {
        val o = JSONObject(cfg)
        val arr = o.optJSONArray("phases") ?: JSONArray()
        phasesJson = arr.toString()
        phases = (0 until arr.length()).map {
            val p = arr.getJSONObject(it)
            Phase(p.optString("name", ""), p.optInt("sec", 1).coerceAtLeast(1), p.optInt("set", 0))
        }
        sets = o.optInt("sets", 1)
        sound = o.optBoolean("sound", true)
        voice = o.optBoolean("voice", true)
        if (phases.isEmpty()) { stopTimer(); return }

        handler.removeCallbacksAndMessages(null)  // drop a pending stop from a previous finish
        paused = false
        running = true
        acquireWake()
        setPhase(0)
        handler.removeCallbacks(loop)
        handler.post(loop)
    }

    private fun now() = SystemClock.elapsedRealtime()

    private fun remainingMs(): Long = if (paused) remain else (endAt - now()).coerceAtLeast(0)

    private fun setPhase(i: Int) {
        idx = i
        val ms = phases[i].sec * 1000L
        if (paused) remain = ms else endAt = now() + ms
        lastSec = phases[i].sec
        announce()
        scheduleAlarm()
        updateNotification()
        publish()
    }

    private fun tick() {
        if (!running || paused) return
        val t = now()
        if (t >= endAt) {
            while (t >= endAt) {
                if (idx + 1 >= phases.size) { finish(); return }
                idx++
                endAt += phases[idx].sec * 1000L
            }
            lastSec = ceil((endAt - t) / 1000.0).toInt()
            announce()
            scheduleAlarm()
            updateNotification()
        }
        val sec = ceil((endAt - t) / 1000.0).toInt()
        if (sec != lastSec) {
            lastSec = sec
            if (sec in 1..3 && phases[idx].sec > 3) {
                beep(ToneGenerator.TONE_PROP_BEEP, 150)
                vibrate(longArrayOf(0, 80))
            }
        }
        publish()
    }

    private fun pause() {
        if (!running || paused) return
        remain = remainingMs()
        paused = true
        cancelAlarm()
        updateNotification()
        publish()
    }

    private fun resume() {
        if (!running || !paused) return
        endAt = now() + remain
        paused = false
        scheduleAlarm()
        updateNotification()
        publish()
        handler.removeCallbacks(loop)
        handler.post(loop)
    }

    private fun skip() {
        if (!running) return
        if (idx + 1 >= phases.size) finish() else setPhase(idx + 1)
    }

    private fun prev() {
        if (!running) return
        val elapsed = phases[idx].sec * 1000L - remainingMs()
        setPhase(if (elapsed > 2000 || idx == 0) idx else idx - 1)
    }

    private fun finish() {
        running = false
        paused = false
        cancelAlarm()
        handler.removeCallbacks(loop)
        stateJson = JSONObject()
            .put("running", false).put("finished", true).put("sets", sets)
            .put("phases", JSONArray(phasesJson)).toString()
        if (sound) {
            for (k in 0..2) handler.postDelayed({ beep(ToneGenerator.TONE_CDMA_HIGH_L, 220) }, k * 300L)
            handler.postDelayed({ beep(ToneGenerator.TONE_CDMA_HIGH_L, 900) }, 900L)
        }
        vibrate(longArrayOf(0, 300, 150, 300, 150, 700))
        speak("おつかれさまでした", 1900)
        stopForeground(STOP_FOREGROUND_REMOVE)
        phases = emptyList()
        handler.postDelayed({ releaseWake(); stopSelf() }, 5000)
    }

    private fun stopTimer() {
        running = false
        paused = false
        phases = emptyList()
        cancelAlarm()
        handler.removeCallbacksAndMessages(null)
        tts?.stop()
        stateJson = "{\"running\":false}"
        stopForeground(STOP_FOREGROUND_REMOVE)
        releaseWake()
        stopSelf()
    }

    private fun publish() {
        if (!running) return
        stateJson = JSONObject()
            .put("running", true)
            .put("paused", paused)
            .put("idx", idx)
            .put("remainMs", remainingMs())
            .put("sets", sets)
            .put("phases", JSONArray(phasesJson))
            .toString()
    }

    // ---------- alerts ----------

    private fun alarmAttrs(): AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ALARM)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()

    private fun announce() {
        beep(ToneGenerator.TONE_CDMA_HIGH_L, 600)
        vibrate(longArrayOf(0, 400, 120, 400))
        speak(phases[idx].name, 700)
    }

    private fun beep(type: Int, ms: Int) {
        if (!sound) return
        try { tone?.startTone(type, ms) } catch (_: Exception) {}
    }

    private fun vibrate(pattern: LongArray) {
        try {
            val v: Vibrator = if (Build.VERSION.SDK_INT >= 31) {
                getSystemService(VibratorManager::class.java).defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(VIBRATOR_SERVICE) as Vibrator
            }
            @Suppress("DEPRECATION")
            v.vibrate(VibrationEffect.createWaveform(pattern, -1), alarmAttrs())
        } catch (_: Exception) {}
    }

    private fun speak(text: String, delayMs: Long) {
        if (!voice || text.isBlank()) return
        handler.postDelayed({
            if (ttsReady) tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "phase")
        }, delayMs)
    }

    // ---------- wake / alarm ----------

    private fun acquireWake() {
        if (wakeLock?.isHeld == true) return
        val total = phases.sumOf { it.sec } * 1000L
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "WorkoutTimer:run")
            .apply { acquire(total + 30 * 60 * 1000L) }
    }

    private fun releaseWake() {
        try { if (wakeLock?.isHeld == true) wakeLock?.release() } catch (_: Exception) {}
        wakeLock = null
    }

    private fun tickIntent(): PendingIntent = PendingIntent.getService(
        this, 1, Intent(this, TimerService::class.java).setAction(ACTION_TICK),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun openIntent(): PendingIntent = PendingIntent.getActivity(
        this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun scheduleAlarm() {
        if (paused) return
        val am = getSystemService(AlarmManager::class.java)
        val at = System.currentTimeMillis() + (endAt - now())
        try {
            if (Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()) {
                am.setAlarmClock(AlarmManager.AlarmClockInfo(at, openIntent()), tickIntent())
            } else {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, tickIntent())
            }
        } catch (_: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, tickIntent())
        }
    }

    private fun cancelAlarm() {
        try { getSystemService(AlarmManager::class.java).cancel(tickIntent()) } catch (_: Exception) {}
    }

    // ---------- notification ----------

    private fun cmdIntent(action: String, code: Int): PendingIntent = PendingIntent.getService(
        this, 10 + code, Intent(this, TimerService::class.java).setAction(action),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun fmt(sec: Long): String = "%d:%02d".format(sec / 60, sec % 60)

    private fun buildNotification(): Notification {
        val b = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_ALARM)
            .setContentIntent(openIntent())
        if (phases.isEmpty()) return b.setContentTitle("ワークアウトタイマー").build()

        val p = phases[idx]
        val rem = remainingMs()
        val after = phases.drop(idx + 1).sumOf { it.sec } * 1000L
        val setText = if (p.set == 0) "まもなく開始" else "セット ${p.set} / $sets"
        b.setContentTitle(if (paused) "${p.name}(一時停止中)" else p.name)
            .setContentText("$setText · 残り合計 ${fmt((rem + after + 999) / 1000)}")
        if (paused) {
            b.setSubText(fmt((rem + 999) / 1000))
            b.addAction(Notification.Action.Builder(Icon.createWithResource(this, R.drawable.ic_stat), "再開", cmdIntent(ACTION_RESUME, 1)).build())
        } else {
            b.setUsesChronometer(true)
                .setChronometerCountDown(true)
                .setWhen(System.currentTimeMillis() + rem)
                .setShowWhen(true)
            b.addAction(Notification.Action.Builder(Icon.createWithResource(this, R.drawable.ic_stat), "一時停止", cmdIntent(ACTION_PAUSE, 2)).build())
        }
        b.addAction(Notification.Action.Builder(Icon.createWithResource(this, R.drawable.ic_stat), "次へ", cmdIntent(ACTION_SKIP, 3)).build())
        b.addAction(Notification.Action.Builder(Icon.createWithResource(this, R.drawable.ic_stat), "終了", cmdIntent(ACTION_STOP, 4)).build())
        return b.build()
    }

    private fun goForeground() {
        val n = buildNotification()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    private fun updateNotification() {
        if (!running) return
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification())
    }
}
