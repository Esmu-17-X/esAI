package com.esmu.ai

import android.animation.ObjectAnimator
import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.text.InputType
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.inputmethod.EditorInfo
import android.widget.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import kotlin.concurrent.thread

class OverlayService : Service() {

    private lateinit var wm: WindowManager
    private lateinit var character: ImageView
    private lateinit var charParams: WindowManager.LayoutParams
    private var panel: View? = null
    private lateinit var log: TextView
    private lateinit var scroll: ScrollView
    private lateinit var input: EditText
    private val ui = Handler(Looper.getMainLooper())
    private val history = JSONArray()
    private var tts: TextToSpeech? = null
    private var voiceOn = true

    // 3D tilt
    private var sensorMgr: SensorManager? = null
    private val tilt = object : SensorEventListener {
        override fun onSensorChanged(e: SensorEvent) {
            character.rotationY = (-e.values[0] * 4f).coerceIn(-25f, 25f)
            character.rotationX = ((e.values[1] - 6f) * 3f).coerceIn(-20f, 20f)
        }
        override fun onAccuracyChanged(s: Sensor?, a: Int) {}
    }

    // Talking animation (optional esmu_talk.png)
    private val idleRes = R.drawable.esmu
    private var talkRes = 0
    private var talking = false
    private val flap = object : Runnable {
        var open = false
        override fun run() {
            if (!talking) { character.setImageResource(idleRes); return }
            open = !open
            character.setImageResource(if (open && talkRes != 0) talkRes else idleRes)
            ui.postDelayed(this, 180)
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForegroundNotification()
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        talkRes = resources.getIdentifier("esmu_talk", "drawable", packageName)

        tts = TextToSpeech(this) {
            if (it == TextToSpeech.SUCCESS) {
                tts?.language = Locale.US
                tts?.setPitch(1.3f)
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(id: String?) {
                        ui.post { talking = true; ui.removeCallbacks(flap); ui.post(flap) }
                    }
                    override fun onDone(id: String?) { ui.post { talking = false } }
                    @Deprecated("Deprecated in Java")
                    override fun onError(id: String?) { ui.post { talking = false } }
                })
            }
        }
        addCharacter()
    }

    override fun onDestroy() {
        sensorMgr?.unregisterListener(tilt)
        ui.removeCallbacksAndMessages(null)
        runCatching { wm.removeView(character) }
        hidePanel()
        tts?.shutdown()
        super.onDestroy()
    }

    private fun startForegroundNotification() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel("esmu", "ESMU", NotificationManager.IMPORTANCE_LOW)
        )
        val n = Notification.Builder(this, "esmu")
            .setContentTitle("ESMU is active")
            .setContentText("Tap her on screen to chat")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .build()
        startForeground(1, n)
    }

    private fun overlayParams(flags: Int, w: Int, h: Int) = WindowManager.LayoutParams(
        w, h, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, flags, PixelFormat.TRANSLUCENT
    )

    // ---------- Floating character ----------
    private fun addCharacter() {
        character = ImageView(this).apply {
            setImageResource(idleRes)
            scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(dp(6), dp(6), dp(6), dp(6))
            cameraDistance = resources.displayMetrics.density * 9000f
        }
        charParams = overlayParams(
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, dp(170), dp(170)
        ).apply { gravity = Gravity.TOP or Gravity.START; x = dp(16); y = dp(200) }

        // Idle breathing
        listOf("scaleX", "scaleY").forEach { prop ->
            ObjectAnimator.ofFloat(character, prop, 1f, 1.05f).apply {
                duration = 1200
                repeatMode = ObjectAnimator.REVERSE
                repeatCount = ObjectAnimator.INFINITE
                interpolator = AccelerateDecelerateInterpolator()
                start()
            }
        }

        var startX = 0; var startY = 0; var touchX = 0f; var touchY = 0f; var moved = false
        character.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = charParams.x; startY = charParams.y
                    touchX = e.rawX; touchY = e.rawY; moved = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (e.rawX - touchX).toInt(); val dy = (e.rawY - touchY).toInt()
                    if (Math.abs(dx) > 10 || Math.abs(dy) > 10) moved = true
                    charParams.x = startX + dx; charParams.y = startY + dy
                    wm.updateViewLayout(character, charParams)
                }
                MotionEvent.ACTION_UP -> if (!moved) { bounce(); togglePanel() }
            }
            true
        }

        sensorMgr = getSystemService(SENSOR_SERVICE) as SensorManager
        sensorMgr?.registerListener(
            tilt, sensorMgr?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER),
            SensorManager.SENSOR_DELAY_UI
        )
        wm.addView(character, charParams)
    }

    private fun bounce() {
        character.animate().translationY(-dp(12).toFloat()).setDuration(120)
            .withEndAction { character.animate().translationY(0f).setDuration(160).start() }
            .start()
    }

    // ---------- Chat panel ----------
    private fun togglePanel() { if (panel == null) showPanel() else hidePanel() }

    private fun hidePanel() {
        panel?.let { runCatching { wm.removeView(it) } }
        panel = null
    }

    private fun showPanel() {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#F01E1B2E")); cornerRadius = dp(22).toFloat()
            }
        }

        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        header.addView(TextView(this).apply {
            text = "✦ ESMU"; setTextColor(Color.parseColor("#A29BFE")); textSize = 16f
        }, LinearLayout.LayoutParams(0, -2, 1f))

        header.addView(TextView(this).apply {
            text = if (voiceOn) "🔊" else "🔇"; textSize = 18f
            setPadding(dp(10), 0, dp(10), 0)
            setOnClickListener {
                voiceOn = !voiceOn
                text = if (voiceOn) "🔊" else "🔇"
                if (!voiceOn) { tts?.stop(); talking = false }
            }
        })
        header.addView(TextView(this).apply {
            text = "🗑"; textSize = 18f; setPadding(dp(10), 0, dp(10), 0)
            setOnClickListener {
                while (history.length() > 0) history.remove(0)
                log.text = "ESMU: Memory cleared! ✨\n"
            }
        })
        header.addView(TextView(this).apply {
            text = "✕"; setTextColor(Color.WHITE); textSize = 18f
            setPadding(dp(10), 0, dp(4), 0); setOnClickListener { hidePanel() }
        })
        box.addView(header)

        log = TextView(this).apply {
            setTextColor(Color.WHITE); textSize = 15f
            text = "ESMU: Hi! I'm ESMU ✨ Ask me anything!\n"
        }
        scroll = ScrollView(this).apply { addView(log) }
        box.addView(scroll, LinearLayout.LayoutParams(-1, dp(240)))

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        input = EditText(this).apply {
            hint = "Ask ESMU..."; setHintTextColor(Color.GRAY); setTextColor(Color.WHITE)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            imeOptions = EditorInfo.IME_ACTION_SEND
            setOnEditorActionListener { _, id, _ ->
                if (id == EditorInfo.IME_ACTION_SEND) { send(); true } else false
            }
        }
        row.addView(input, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(Button(this).apply { text = "Send"; setOnClickListener { send() } })
        box.addView(row)

        val p = overlayParams(
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            (resources.displayMetrics.widthPixels * 0.92).toInt(), -2
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = dp(24)
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        }
        wm.addView(box, p)
        panel = box
    }

    private fun append(text: String) = ui.post {
        log.append(text)
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun send() {
        val q = input.text.toString().trim()
        if (q.isEmpty()) return
        input.setText("")
        append("\nYou: $q\n")

        val key = ApiClient.resolveKey(this)
        if (key.isBlank()) {
            append("ESMU: No API key found. Save one in the app first.\n"); return
        }

        while (history.length() > 20) { history.remove(0); history.remove(0) }
        history.put(JSONObject().put("role", "user").put("content", q))
        append("ESMU: …\n")

        thread {
            try {
                val reply = ApiClient.chat(key, history)
                history.put(JSONObject().put("role", "assistant").put("content", reply))
                append("\nESMU: $reply\n")
                ui.post {
                    bounce()
                    if (voiceOn) tts?.speak(reply, TextToSpeech.QUEUE_FLUSH, null, "esmu")
                }
            } catch (e: Exception) {
                history.remove(history.length() - 1)
                append("\nESMU: Oops, something went wrong.\n${e.message}\n")
            }
        }
    }
}
