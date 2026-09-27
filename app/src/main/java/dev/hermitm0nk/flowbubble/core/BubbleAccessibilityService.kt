package dev.hermitm0nk.flowbubble.core

import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import dev.hermitm0nk.flowbubble.data.HistoryStore
import dev.hermitm0nk.flowbubble.data.SettingsStore
import dev.hermitm0nk.flowbubble.ui.MainActivity
import kotlin.math.abs

/** Nonfocusable accessibility overlay: only a focused, ordinary editable node may show it. */
class BubbleAccessibilityService : AccessibilityService(), MicrophoneService.Listener {
    private class BubbleButton(context: Context) : TextView(context) {
        override fun performClick(): Boolean { super.performClick(); return true }
    }
    private lateinit var settings: SettingsStore
    private lateinit var wm: WindowManager
    private val main = Handler(Looper.getMainLooper())
    private var bubble: LinearLayout? = null
    private var params: WindowManager.LayoutParams? = null
    private var homeX = 0
    private var target: AccessibilityNodeInfo? = null
    private var targetWindow = -1
    private var targetInvalidated = false
    private var state = "ready"
    private var recordingMode = "tap"
    private var holdGestureActive = false
    private var lastError: String? = null
    private val refresh = Runnable { updateVisibility() }

    override fun onServiceConnected() {
        settings = SettingsStore(this)
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        updateVisibility()
    }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!::wm.isInitialized) return
        main.removeCallbacks(refresh)
        main.postDelayed(refresh, 120)
    }
    override fun onInterrupt() { cancelAndHide() }
    override fun onDestroy() {
        cancelAndHide()
        if (MicrophoneService.instance?.listener === this) MicrophoneService.instance?.listener = null
        super.onDestroy()
    }
    private fun focusedEditor(): AccessibilityNodeInfo? {
        val focused = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return null
        if (!focused.isFocused || !focused.isEditable || focused.isPassword) return null
        if (focused.packageName?.toString() == packageName) return null
        val type = focused.inputType
        val clazz = type and InputType.TYPE_MASK_CLASS
        if (clazz != InputType.TYPE_CLASS_TEXT && clazz != 0) return null // Some custom editors omit inputType.
        val variation = type and InputType.TYPE_MASK_VARIATION
        if (variation == InputType.TYPE_TEXT_VARIATION_PASSWORD || variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
            variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD) return null
        return focused
    }
    private fun updateVisibility() {
        val focused = focusedEditor()
        if (focused == null) {
            if (state == "recording") MicrophoneService.instance?.cancel()
            if (state == "transcribing") targetInvalidated = true
            target = if (state == "transcribing") target else null
            hide(); return
        }
        if (state == "recording" || state == "transcribing") {
            val original = target
            if (original == null || original != focused || !original.refresh() || !original.isFocused) {
                targetInvalidated = true
                if (state == "recording") { MicrophoneService.instance?.cancel(); state = "ready" }
            }
        }
        if (state == "ready" || target == null) { target = focused; targetWindow = focused.windowId }
        if (bubble == null) show()
        if (MicrophoneService.instance?.listener !== this) MicrophoneService.instance?.listener = this
    }
    private fun show() {
        val size = dp(settings.bubbleSizeDp)
        val idleWidth = if (settings.bubbleStyle == "pill") size * 2 else size
        val display = resources.displayMetrics
        val x = if (settings.bubbleX < 0) display.widthPixels - idleWidth - dp(22) else settings.bubbleX
        val y = if (settings.bubbleY < 0) dp(105) else settings.bubbleY
        params = WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.START
            this.x = x.coerceIn(0, (display.widthPixels - idleWidth).coerceAtLeast(0))
            this.y = y.coerceIn(0, display.heightPixels - size)
        }
        homeX = params!!.x
        bubble = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        render()
        try { wm.addView(bubble, params) } catch (_: Exception) { bubble = null; params = null }
    }
    private fun hide() {
        bubble?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        bubble = null; params = null
    }
    private fun cancelAndHide() { MicrophoneService.instance?.cancel(); target = null; hide() }
    private fun button(symbol: String, label: String, background: Int, size: Int, radius: Float): TextView = BubbleButton(this).apply {
        text = symbol; textSize = 23f; gravity = Gravity.CENTER
        setTextColor(if (settings.darkMode) Color.BLACK else Color.WHITE)
        contentDescription = label
        setPadding(dp(9), dp(4), dp(9), dp(4))
        this.background = GradientDrawable().apply { setColor(background); cornerRadius = radius }
        layoutParams = LinearLayout.LayoutParams(dp(size), dp(settings.bubbleSizeDp)).apply { marginEnd = dp(5) }
    }
    private fun render() {
        val layout = bubble ?: return
        layout.removeAllViews()
        val color = if (settings.darkMode) 0xffaaa0b2.toInt() else 0xff4d266e.toInt()
        val size = settings.bubbleSizeDp
        val radius = when (settings.bubbleStyle) {
            "square" -> dp(17).toFloat()
            else -> dp(size / 2).toFloat()
        }
        if (state == "ready") {
            val label = button("▥", "Hold to dictate, tap to start", color,
                if (settings.bubbleStyle == "pill") size * 2 else size, radius)
            label.alpha = settings.bubbleAlpha
            label.setOnClickListener { recordingMode = "tap"; beginRecording() }
            label.setOnTouchListener(object : View.OnTouchListener {
                private var downX = 0f; private var downY = 0f
                private var originX = 0; private var originY = 0
                private var moved = false; private var holding = false
                private val hold = Runnable {
                    if (!moved) { holding = true; holdGestureActive = true; recordingMode = "hold"; beginRecording() }
                }
                override fun onTouch(v: View, event: MotionEvent): Boolean {
                    val p = params ?: return true
                    when (event.actionMasked) {
                        MotionEvent.ACTION_DOWN -> {
                            downX = event.rawX; downY = event.rawY; originX = p.x; originY = p.y
                            moved = false; holding = false; main.postDelayed(hold, 330)
                        }
                        MotionEvent.ACTION_MOVE -> {
                            val dx = event.rawX - downX; val dy = event.rawY - downY
                            if (!holding && (abs(dx) > dp(12) || abs(dy) > dp(12))) { moved = true; main.removeCallbacks(hold) }
                            if (moved) {
                                val width = if (settings.bubbleStyle == "pill") size * 2 else size
                                p.x = (originX + dx.toInt()).coerceIn(0, (resources.displayMetrics.widthPixels - dp(width)).coerceAtLeast(0))
                                p.y = (originY + dy.toInt()).coerceIn(0, resources.displayMetrics.heightPixels - dp(size))
                                wm.updateViewLayout(layout, p)
                            }
                        }
                        MotionEvent.ACTION_CANCEL -> {
                            main.removeCallbacks(hold)
                            holdGestureActive = false
                            if (holding) MicrophoneService.instance?.cancel()
                            state = "ready"; render()
                        }
                        MotionEvent.ACTION_UP -> {
                            main.removeCallbacks(hold)
                            if (moved) { homeX = p.x; settings.bubbleX = p.x; settings.bubbleY = p.y }
                            else if (holding) {
                                holdGestureActive = false
                                MicrophoneService.instance?.finish()
                                render()
                            }
                            else v.performClick()
                        }
                    }
                    return true
                }
            })
            layout.addView(label)
        } else {
            val cancel = button("×", "Cancel dictation", color, size, dp(size / 2).toFloat())
            cancel.setOnClickListener { MicrophoneService.instance?.cancel(); state = "ready"; render() }
            layout.addView(cancel)
            val meter = button(if (state == "recording") "••••••" else "…", state, color,
                if (settings.bubbleStyle == "pill") size * 2 else size + 40, dp(size / 2).toFloat())
            meter.alpha = settings.bubbleAlpha
            layout.addView(meter)
            if (recordingMode == "tap" || state == "transcribing") {
                val submit = button("✓", "Submit dictation", 0xff653783.toInt(), size, dp(size / 2).toFloat())
                submit.setOnClickListener { if (state == "recording") MicrophoneService.instance?.finish() }
                layout.addView(submit)
            }
        }
        // Keep expanded recording controls visible near either screen edge.
        val p = params
        if (p != null && layout.isAttachedToWindow) {
            layout.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
            p.x = homeX.coerceAtMost((resources.displayMetrics.widthPixels - layout.measuredWidth).coerceAtLeast(0))
            try { wm.updateViewLayout(layout, p) } catch (_: Exception) {}
        }
    }
    private fun beginRecording() {
        val mic = MicrophoneService.instance
        if (mic == null) {
            notifyUser("Open Whisproid and tap Enable dictation first")
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        }
        mic.listener = this
        targetInvalidated = false
        if (mic.begin()) { state = "recording"; if (!holdGestureActive) render() }
        else holdGestureActive = false
    }
    override fun onState(state: String) {
        main.post { this.state = state; if (bubble != null && !holdGestureActive) render() }
    }
    override fun onTranscript(text: String) {
        main.post {
            HistoryStore(this).use { it.add(text) }
            val node = target
            val stillFocused = focusedEditor()
            if (targetInvalidated || node == null || !node.refresh() || !node.isFocused || stillFocused != node || stillFocused.windowId != targetWindow) {
                notifyUser("Transcript saved in history; text field changed")
            } else if (!insert(node, text)) {
                notifyUser("Transcript saved in history; open it to copy")
            }
            state = "ready"; updateVisibility(); render()
        }
    }
    private fun insert(node: AccessibilityNodeInfo, words: String): Boolean {
        val current = node.text?.toString().orEmpty()
        val from = node.textSelectionStart.takeIf { it >= 0 } ?: current.length
        val to = node.textSelectionEnd.takeIf { it >= 0 } ?: from
        val insertion = composeInsertion(current, from, to, words)
        val arg = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, insertion.text) }
        if (node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arg)) {
            node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, Bundle().apply {
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, insertion.cursor)
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, insertion.cursor)
            })
            return true
        }
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Whisproid transcription", words))
        return node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
    }
    override fun onFailure(message: String) { main.post { lastError = message; state = "ready"; render(); notifyUser(message) } }
    private fun notifyUser(message: String) { Toast.makeText(this, message, Toast.LENGTH_LONG).show() }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
