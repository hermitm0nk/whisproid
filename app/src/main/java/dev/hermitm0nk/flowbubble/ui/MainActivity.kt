package dev.hermitm0nk.flowbubble.ui

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import dev.hermitm0nk.flowbubble.core.MicrophoneService
import dev.hermitm0nk.flowbubble.data.HistoryStore
import dev.hermitm0nk.flowbubble.data.SettingsStore
import java.text.DateFormat
import java.util.Date

/** Main setup screen and local settings/transcript browser. Dictation itself runs in the overlay service. */
class MainActivity : Activity() {
    private lateinit var settings: SettingsStore
    private lateinit var history: HistoryStore
    private lateinit var root: LinearLayout
    private val ink get() = if (settings.darkMode) Color.WHITE else Color.rgb(29, 31, 38)
    private val surface get() = if (settings.darkMode) Color.rgb(28, 29, 34) else Color.WHITE
    private val backdrop get() = if (settings.darkMode) Color.rgb(16, 17, 20) else Color.rgb(246, 247, 250)
    private val accent = Color.rgb(137, 93, 226)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = SettingsStore(this)
        history = HistoryStore(this)
        showHome()
    }

    private fun base(title: String): LinearLayout {
        window.statusBarColor = backdrop
        window.navigationBarColor = backdrop
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = if (settings.darkMode) 0 else
            View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(18), dp(22), dp(22))
            setBackgroundColor(backdrop)
        }
        val scroller = ScrollView(this).apply { isFillViewport = true; addView(root) }
        setContentView(scroller)
        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        if (title != "Whisproid") TextView(this).apply {
            text = "‹"
            textSize = 32f
            gravity = Gravity.CENTER
            contentDescription = "Back to home"
            isClickable = true
            isFocusable = true
            minimumWidth = dp(48)
            minimumHeight = dp(48)
            background = rounded(surface, 14)
            setTextColor(ink)
            setOnClickListener { showHome() }
        }.also { header.addView(it, LinearLayout.LayoutParams(dp(48), dp(48)).apply { marginEnd = dp(12) }) }
        text(title, 25, true).also { header.addView(it, LinearLayout.LayoutParams(0, -2, 1f)) }
        root.addView(header)
        return root
    }

    private fun showHome() {
        base("Whisproid")
        root.addView(text("Private, on-device controls. Audio is sent directly from this device to Google AI Studio when you dictate. Enable Whisproid in Accessibility settings to detect focused text fields and display its accessibility overlay.", 15, false), spaced())
        val configured = settings.apiKey.isNotBlank()
        val card = card()
        card.addView(text(if (configured) "Ready to dictate" else "Finish setup", 19, true))
        card.addView(text(if (configured) "Your API key is saved on this device." else "Add a Google AI Studio API key, grant microphone access and enable the accessibility service.", 14, false), spaced())
        root.addView(card, spaced())
        addButton("Enable dictation", true) { enableDictation() }
        addButton("Disable dictation", false) {
            startService(Intent(this, MicrophoneService::class.java).setAction(MicrophoneService.ACTION_STOP))
            Toast.makeText(this, "Dictation disabled", Toast.LENGTH_SHORT).show()
        }
        addButton("Enable accessibility bubble", false) { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        addButton("API key and bubble appearance", false) { showSettings() }
        addButton("Transcript history (${history.all().size})", false) { showHistory() }
        val mode = Switch(this).apply {
            text = "Dark mode"
            setTextColor(ink)
            isChecked = settings.darkMode
            setOnCheckedChangeListener { _, checked -> settings.darkMode = checked; showHome() }
        }
        root.addView(mode, spaced())
        root.addView(text("Hold the floating bubble to dictate. Tap it for cancel/submit controls. Transcripts are stored locally until you delete them.", 13, false), spaced())
    }

    private fun enableDictation() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 21)
            return
        }
        startMicrophoneService()
    }

    private fun startMicrophoneService() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return
        val intent = Intent(this, MicrophoneService::class.java).setAction(MicrophoneService.ACTION_START)
        ContextCompat.startForegroundService(this, intent)
        Toast.makeText(this, "Dictation enabled. The bubble appears when a text field is focused and Whisproid accessibility is enabled.", Toast.LENGTH_LONG).show()
    }

    @Deprecated("Deprecated by Android; retained for runtime permission callback compatibility")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 21 && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) startMicrophoneService()
    }

    private fun showSettings() {
        base("Settings")
        root.addView(text("Google AI Studio API key", 16, true), spaced())
        val key = EditText(this).apply {
            hint = "Paste API key"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setText(settings.apiKey)
            setSingleLine(true)
            setTextColor(ink); setHintTextColor(if (settings.darkMode) 0xffaaaaaa.toInt() else 0xff777777.toInt())
            background = rounded(surface, 14)
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }
        root.addView(key, spaced())
        addButton("Save API key", true) { settings.apiKey = key.text.toString().trim(); Toast.makeText(this, "Saved on this device", Toast.LENGTH_SHORT).show() }
        root.addView(text("The key is used by this app to connect directly to Google's Gemini Live API. Never share screenshots or backups containing credentials.", 13, false), spaced())
        root.addView(text("Floating button", 20, true), spaced())
        root.addView(text("Size: ${settings.bubbleSizeDp} dp", 14, false), spaced())
        val size = SeekBar(this).apply { max = 40; progress = (settings.bubbleSizeDp - 48).coerceIn(0, 40); setOnSeekBarChangeListener(seek { settings.bubbleSizeDp = 48 + it }) }
        root.addView(size)
        root.addView(text("Opacity: ${(settings.bubbleAlpha * 100).toInt()}%", 14, false), spaced())
        val opacity = SeekBar(this).apply { max = 75; progress = ((settings.bubbleAlpha - .25f) * 100).toInt().coerceIn(0, 75); setOnSeekBarChangeListener(seek { settings.bubbleAlpha = .25f + it / 100f }) }
        root.addView(opacity)
        root.addView(text("Style", 14, true), spaced())
        val styles = listOf("orb" to "Orb", "pill" to "Pill", "square" to "Soft square")
        val radioGroup = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        val radioTint = ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
            intArrayOf(accent, if (settings.darkMode) 0xffb9b2ca.toInt() else 0xff696571.toInt())
        )
        styles.forEach { (value, label) ->
            val radio = RadioButton(this).apply { text = label; setTextColor(ink); buttonTintList = radioTint; isChecked = settings.bubbleStyle == value; setOnClickListener { settings.bubbleStyle = value } }
            radioGroup.addView(radio)
        }
        root.addView(radioGroup)
        addButton("View transcript history", false) { showHistory() }
    }

    private fun showHistory() {
        base("Transcript history")
        val entries = history.all().sortedByDescending { it.createdAt }
        if (entries.isEmpty()) root.addView(text("No transcripts yet. Completed dictations appear here.", 15, false), spaced())
        entries.forEach { entry ->
            val item = card()
            item.addView(text(DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(entry.createdAt)), 12, false))
            item.addView(text(entry.text, 16, false), spaced())
            val actions = LinearLayout(this).apply { gravity = Gravity.END }
            val copy = smallButton("Copy") { (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Transcript", entry.text)); Toast.makeText(this@MainActivity, "Copied", Toast.LENGTH_SHORT).show() }
            val delete = smallButton("Delete") { history.delete(entry.id); showHistory() }
            actions.addView(copy); actions.addView(delete); item.addView(actions)
            root.addView(item, spaced())
        }
        if (entries.isNotEmpty()) addButton("Delete all transcripts", false) { history.clear(); showHistory() }
    }

    private fun seek(onValue: (Int) -> Unit) = object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) = onValue(progress)
        override fun onStartTrackingTouch(seekBar: SeekBar?) {}
        override fun onStopTrackingTouch(seekBar: SeekBar?) {}
    }

    private fun text(value: String, size: Int, bold: Boolean) = TextView(this).apply {
        text = value; textSize = size.toFloat(); setTextColor(ink)
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }
    private fun card() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(17), dp(16), dp(17), dp(14)); background = rounded(surface, 18) }
    private fun addButton(label: String, primary: Boolean, action: () -> Unit): Button = Button(this).apply {
        text = label; isAllCaps = false; setTextColor(if (primary) Color.WHITE else ink)
        background = rounded(if (primary) accent else surface, 16)
        setOnClickListener { action() }
    }.also { root.addView(it, spaced()) }
    private fun smallButton(label: String, action: () -> Unit) = Button(this).apply { text = label; isAllCaps = false; setOnClickListener { action() } }
    private fun rounded(color: Int, radius: Int) = GradientDrawable().apply { setColor(color); cornerRadius = dp(radius).toFloat() }
    private fun spaced() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
