package com.margie.clearvoice

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.os.Bundle

/** Setup screen: three permissions, your settings, and the on/off button. */
class MainActivity : Activity() {

    private lateinit var micStatus: TextView
    private lateinit var overlayStatus: TextView
    private lateinit var a11yStatus: TextView
    private lateinit var toggle: Button
    private lateinit var key: EditText
    private lateinit var model: EditText
    private lateinit var dict: EditText
    private lateinit var lang: EditText
    private lateinit var styleGroup: RadioGroup

    private val prefs by lazy { getSharedPreferences("cv", MODE_PRIVATE) }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun label(t: String, bold: Boolean = false, size: Float = 18f) = TextView(this).apply {
        text = t
        textSize = size
        if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
        setPadding(0, dp(10), 0, dp(4))
    }

    private fun button(t: String, onClick: () -> Unit) = Button(this).apply {
        text = t
        textSize = 17f
        isAllCaps = false
        setOnClickListener { onClick() }
    }

    private fun field(hint: String, value: String, secret: Boolean = false) = EditText(this).apply {
        this.hint = hint
        setText(value)
        textSize = 17f
        setSingleLine(true)
        if (secret) inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(24), dp(18), dp(40))
        }

        col.addView(label("🎙️ Clear Voice", true, 28f))
        col.addView(label("Touch a text box, tap the purple ball, and talk. Your words are cleaned up and typed for you."))

        col.addView(label("Step 1: Microphone", true))
        micStatus = label("")
        col.addView(micStatus)
        col.addView(button("Allow microphone") {
            val perms = if (Build.VERSION.SDK_INT >= 33)
                arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)
            else arrayOf(Manifest.permission.RECORD_AUDIO)
            requestPermissions(perms, 1)
        })

        col.addView(label("Step 2: Show the ball over other apps", true))
        overlayStatus = label("")
        col.addView(overlayStatus)
        col.addView(button("Open that setting") {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        })

        col.addView(label("Step 3: Let it type for you", true))
        a11yStatus = label("")
        col.addView(a11yStatus)
        col.addView(button("Open Accessibility settings") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        })
        col.addView(label(
            "Find Clear Voice in the list and turn it on. If it's greyed out: Settings → Apps → Clear Voice → three dots at top right → Allow restricted settings, then try again.",
            false, 15f
        ))

        col.addView(label("Your settings", true))
        col.addView(label("Gemini API key (free at aistudio.google.com). Makes cleanup much smarter.", false, 15f))
        key = field("Paste key here", prefs.getString("key", "") ?: "", true)
        col.addView(key)
        col.addView(label("Names and words to spell right (separate with commas)", false, 15f))
        dict = field("Margie, Dream Big and Happy World", prefs.getString("dict", "") ?: "")
        col.addView(dict)
        col.addView(label("Style", false, 15f))
        styleGroup = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
        listOf("text" to "Text message", "email" to "Email", "exact" to "Keep my exact words").forEach { (v, t) ->
            styleGroup.addView(RadioButton(this).apply { text = t; tag = v; textSize = 17f; id = View.generateViewId() })
        }
        col.addView(styleGroup)
        val cur = prefs.getString("style", "text")
        (0 until styleGroup.childCount).map { styleGroup.getChildAt(it) }
            .firstOrNull { it.tag == cur }?.let { (it as RadioButton).isChecked = true }
        col.addView(label("Language code", false, 15f))
        lang = field("en-US", prefs.getString("lang", "en-US") ?: "en-US")
        col.addView(lang)
        model = field("gemini-2.5-flash", prefs.getString("model", "gemini-2.5-flash") ?: "gemini-2.5-flash")
        col.addView(label("AI model", false, 15f))
        col.addView(model)

        col.addView(label("Turn it on", true))
        toggle = button("") { toggleBall() }
        col.addView(toggle)

        setContentView(ScrollView(this).apply { addView(col) })
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onPause() {
        saveSettings()
        super.onPause()
    }

    private fun saveSettings() {
        val style = (0 until styleGroup.childCount).map { styleGroup.getChildAt(it) }
            .firstOrNull { (it as RadioButton).isChecked }?.tag as? String ?: "text"
        prefs.edit()
            .putString("key", key.text.toString().trim())
            .putString("model", model.text.toString().trim().ifEmpty { "gemini-2.5-flash" })
            .putString("dict", dict.text.toString().trim())
            .putString("lang", lang.text.toString().trim().ifEmpty { "en-US" })
            .putString("style", style)
            .apply()
    }

    private fun micOk() = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun refresh() {
        micStatus.text = if (micOk()) "✅ Microphone allowed" else "⬜ Not allowed yet"
        overlayStatus.text = if (Settings.canDrawOverlays(this)) "✅ Allowed" else "⬜ Not allowed yet"
        a11yStatus.text = if (InputService.instance != null) "✅ On" else "⬜ Not on yet"
        toggle.text = if (DictationService.running) "Turn off the floating ball" else "Turn on the floating ball"
    }

    private fun toggleBall() {
        saveSettings()
        if (DictationService.running) {
            startService(Intent(this, DictationService::class.java).setAction(DictationService.ACTION_STOP))
            toggle.postDelayed({ refresh() }, 400)
            return
        }
        if (!micOk() || !Settings.canDrawOverlays(this) || InputService.instance == null) {
            a11yStatus.text = if (InputService.instance != null) "✅ On" else "⬜ Finish steps 1 to 3 first"
            return
        }
        startForegroundService(Intent(this, DictationService::class.java))
        toggle.postDelayed({ refresh() }, 400)
    }

    override fun onRequestPermissionsResult(code: Int, p: Array<out String>, r: IntArray) {
        super.onRequestPermissionsResult(code, p, r)
        refresh()
    }
}
