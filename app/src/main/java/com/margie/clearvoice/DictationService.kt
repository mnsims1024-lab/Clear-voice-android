package com.margie.clearvoice

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.Executors
import kotlin.math.abs

/** Shows the floating purple ball and does the listening. */
class DictationService : Service() {

    private enum class State { IDLE, LISTEN, WORK }

    private var wm: WindowManager? = null
    private var bubble: TextView? = null
    private val ballBg = GradientDrawable().apply { shape = GradientDrawable.OVAL }
    private var recognizer: SpeechRecognizer? = null
    private var recIntent: Intent? = null
    private val main = Handler(Looper.getMainLooper())
    private val pool = Executors.newSingleThreadExecutor()
    private val buffer = StringBuilder()
    private var active = false
    private var finishing = false
    private val forceFinish = Runnable { finishUp() }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        startAsForeground()
        if (bubble == null) addBubble()
        return START_NOT_STICKY
    }

    private fun startAsForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Clear Voice", NotificationManager.IMPORTANCE_LOW)
        )
        val stop = PendingIntent.getService(
            this, 0,
            Intent(this, DictationService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        val n = Notification.Builder(this, CHANNEL)
            .setContentTitle("Clear Voice is on")
            .setContentText("Tap the purple ball to talk. Use Turn off to remove it.")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .addAction(android.R.drawable.ic_delete, "Turn off", stop)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(1, n)
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun addBubble() {
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val size = dp(64)
        val tv = TextView(this).apply {
            text = "🎙️"
            textSize = 26f
            gravity = Gravity.CENTER
            background = ballBg
            contentDescription = "Clear Voice. Tap to start or stop dictation."
        }
        setState(State.IDLE, tv)

        val lp = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(12)
            y = dp(320)
        }

        var downX = 0f; var downY = 0f
        var startX = 0; var startY = 0
        var moved = false
        tv.setOnTouchListener { v, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY
                    startX = lp.x; startY = lp.y
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (abs(dx) > dp(8) || abs(dy) > dp(8)) moved = true
                    if (moved) {
                        lp.x = startX + dx.toInt()
                        lp.y = startY + dy.toInt()
                        wm?.updateViewLayout(v, lp)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) onTap()
                    true
                }
                else -> false
            }
        }
        wm?.addView(tv, lp)
        bubble = tv
    }

    private fun setState(s: State, view: TextView? = bubble) {
        when (s) {
            State.IDLE -> { ballBg.setColor(Color.argb(170, 91, 61, 245)); view?.text = "🎙️" }
            State.LISTEN -> { ballBg.setColor(Color.argb(235, 229, 66, 122)); view?.text = "🔴" }
            State.WORK -> { ballBg.setColor(Color.argb(235, 245, 166, 35)); view?.text = "✨" }
        }
        view?.invalidate()
    }

    private fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_SHORT).show()

    private fun onTap() {
        if (finishing) return
        if (!active) startListening() else stopListening()
    }

    private fun startListening() {
        if (InputService.instance == null) {
            toast("Turn on Clear Voice in Accessibility settings first")
            return
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            toast("Open Clear Voice and allow the microphone")
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            toast("No speech recognizer on this phone")
            return
        }
        val lang = getSharedPreferences("cv", MODE_PRIVATE).getString("lang", "en-US") ?: "en-US"
        recIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 3000L)
        }
        buffer.setLength(0)
        active = true
        setState(State.LISTEN)
        recognizer?.destroy()
        recognizer = SpeechRecognizer.createSpeechRecognizer(this).also {
            it.setRecognitionListener(listener)
            it.startListening(recIntent)
        }
    }

    private fun restart() {
        if (!active) return
        try { recognizer?.startListening(recIntent) } catch (e: Exception) { active = false; finishUp() }
    }

    private fun stopListening() {
        active = false
        try { recognizer?.stopListening() } catch (e: Exception) {}
        main.postDelayed(forceFinish, 3000)
    }

    private fun finishUp() {
        if (finishing) return
        finishing = true
        active = false
        main.removeCallbacks(forceFinish)
        recognizer?.destroy()
        recognizer = null
        val raw = buffer.toString().trim()
        if (raw.isEmpty()) {
            setState(State.IDLE)
            finishing = false
            toast("Didn't catch that. Try again.")
            return
        }
        setState(State.WORK)
        val p = getSharedPreferences("cv", MODE_PRIVATE)
        val key = p.getString("key", "") ?: ""
        val model = p.getString("model", "gemini-2.5-flash") ?: "gemini-2.5-flash"
        val dict = p.getString("dict", "") ?: ""
        val style = p.getString("style", "text") ?: "text"
        pool.execute {
            val out = Polisher.polish(raw, key, model, dict, style)
            main.post { deliver(out) }
        }
    }

    private fun deliver(text: String) {
        val ok = InputService.instance?.insert(text) ?: false
        if (!ok) {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("Clear Voice", text))
            toast("Couldn't type it. Copied: long-press the box and tap Paste.")
        }
        setState(State.IDLE)
        finishing = false
    }

    private val listener = object : RecognitionListener {
        override fun onResults(results: Bundle?) {
            val t = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
            if (!t.isNullOrBlank()) {
                if (buffer.isNotEmpty()) buffer.append(' ')
                buffer.append(t)
            }
            if (active) restart() else finishUp()
        }

        override fun onError(error: Int) {
            if (!active) { finishUp(); return }
            when (error) {
                SpeechRecognizer.ERROR_NO_MATCH,
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> restart()
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> {
                    try { recognizer?.cancel() } catch (e: Exception) {}
                    main.postDelayed({ restart() }, 300)
                }
                else -> {
                    toast("Microphone problem (code $error)")
                    active = false
                    finishUp()
                }
            }
        }

        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onPartialResults(partialResults: Bundle?) {}
        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        try { recognizer?.destroy() } catch (e: Exception) {}
        bubble?.let { try { wm?.removeView(it) } catch (e: Exception) {} }
        bubble = null
        pool.shutdown()
        running = false
        super.onDestroy()
    }

    override fun onCreate() {
        super.onCreate()
        running = true
    }

    companion object {
        const val ACTION_STOP = "com.margie.clearvoice.STOP"
        private const val CHANNEL = "clearvoice"
        @Volatile var running = false
    }
}
