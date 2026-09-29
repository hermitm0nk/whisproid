package dev.hermitm0nk.flowbubble.core

import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
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
import kotlin.math.sin

/** Nonfocusable accessibility overlay: only a focused, ordinary editable node may show it. */
class BubbleAccessibilityService : AccessibilityService(), MicrophoneService.Listener {
    companion object {
        @Volatile internal var instance: BubbleAccessibilityService? = null
            private set
    }
    private class BubbleButton(context: Context) : TextView(context) {
        enum class Visual { TEXT, WAVEFORM, RECORDING, SPINNER }
        var visual = Visual.TEXT
            set(value) {
                if (field == value) return
                field = value
                invalidate()
            }
        private val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            if (visual == Visual.TEXT) return
            val unit = minOf(width, height).toFloat()
            val centerX = width / 2f
            val centerY = height / 2f
            ink.color = currentTextColor
            ink.strokeWidth = (unit * .065f).coerceAtLeast(2f)
            if (visual == Visual.SPINNER) {
                val radius = unit * .21f
                val angle = (SystemClock.uptimeMillis() % 900L) * 360f / 900f
                val oval = RectF(centerX - radius, centerY - radius, centerX + radius, centerY + radius)
                canvas.drawArc(oval, angle, 255f, false, ink)
            } else {
                val heights = floatArrayOf(.15f, .38f, .26f, .5f, .26f)
                val phase = SystemClock.uptimeMillis() / 125.0
                for (index in heights.indices) {
                    val x = centerX + (index - 2) * unit * .12f
                    val scale = if (visual == Visual.RECORDING)
                        .48f + .52f * abs(sin(phase + index * .88)).toFloat() else 1f
                    val half = unit * heights[index] * scale / 2f
                    canvas.drawLine(x, centerY - half, x, centerY + half, ink)
                }
            }
            // Only attached active indicators request frames; removal stops animation.
            if (isAttachedToWindow && (visual == Visual.RECORDING || visual == Visual.SPINNER)) postInvalidateOnAnimation()
        }
        override fun performClick(): Boolean { super.performClick(); return true }
    }
    private lateinit var settings: SettingsStore
    private lateinit var wm: WindowManager
    private val main = Handler(Looper.getMainLooper())
    private var bubble: LinearLayout? = null
    private var gestureButton: BubbleButton? = null
    private var params: WindowManager.LayoutParams? = null
    private var homeX = 0
    private var target: AccessibilityNodeInfo? = null
    private var targetWindow = -1
    private var targetInvalidated = false
    private var transcriptGeneration = 0
    private var state = "ready"
    private var recordingMode = "tap"
    private var finishing = false
    private var holdGestureActive = false
    private var lastError: String? = null
    internal var lastCancelCode = 0
        private set
    private val redraw = Runnable { renderNow() }
    internal fun diagnosticCode(): Int {
        val error = lastError
        if (error != null) return when {
            error.startsWith("Could not start microphone") -> 20
            error.startsWith("Live API connection failed") -> 21
            error.contains("final speech") -> 22
            error.contains("No speech") -> 23
            error.contains("setup", ignoreCase = true) -> 24
            else -> 29
        }
        if (MicrophoneService.instance == null) return 3
        return when (state) { "ready" -> 10; "recording" -> 11; "transcribing" -> 12; else -> 19 }
    }
    private val refresh = Runnable { updateVisibility() }

    override fun onServiceConnected() {
        instance = this
        settings = SettingsStore(this)
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        updateVisibility()
    }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!::wm.isInitialized) return
        main.removeCallbacks(refresh)
        main.postDelayed(refresh, 40)
    }
    override fun onInterrupt() { lastCancelCode = 5; cancelAndHide() }
    override fun onDestroy() {
        lastCancelCode = 6; cancelAndHide()
        if (instance === this) instance = null
        if (MicrophoneService.instance?.listener === this) MicrophoneService.instance?.listener = null
        super.onDestroy()
    }
    private fun focusedEditor(): AccessibilityNodeInfo? {
        // An accessibility overlay may briefly become the active accessibility
        // window while it is touched, though the app editor retains input focus.
        // Search application windows instead of treating that overlay as a loss
        // of editor focus and cancelling the held recording.
        val roots = mutableListOf<AccessibilityNodeInfo>()
        rootInActiveWindow?.let { roots.add(it) }
        windows.filter { it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION }
            .forEach { window -> window.root?.let { roots.add(it) } }
        for (root in roots) {
            // Compose/WebView editors may expose focus on a container or fail to
            // implement findFocus. Inspect the tree for the actual focused editor.
            val direct = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            if (direct != null && usableEditor(direct)) return direct
            findFocusedEditor(root)?.let { return it }
        }
        return null
    }
    private fun findFocusedEditor(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val nodes = ArrayDeque<AccessibilityNodeInfo>()
        nodes.add(root)
        var inspected = 0
        while (nodes.isNotEmpty() && inspected++ < 1500) {
            val node = nodes.removeFirst()
            if (usableEditor(node)) return node
            for (i in 0 until node.childCount) node.getChild(i)?.let { nodes.addLast(it) }
        }
        return null
    }
    private fun usableEditor(node: AccessibilityNodeInfo): Boolean {
        if (!node.isFocused || node.isPassword || node.packageName?.toString() == packageName) return false
        val type = node.inputType
        val clazz = type and InputType.TYPE_MASK_CLASS
        if (clazz != InputType.TYPE_CLASS_TEXT && clazz != 0) return false
        val variation = type and InputType.TYPE_MASK_VARIATION
        if (variation == InputType.TYPE_TEXT_VARIATION_PASSWORD || variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
            variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD) return false
        // Custom semantics sometimes omit isEditable while providing SET_TEXT.
        return node.isEditable || node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_SET_TEXT }
    }
    private fun updateVisibility() {
        val focused = focusedEditor()
        if (focused == null) {
            if (state == "recording") { lastCancelCode = 1; finishing = false; MicrophoneService.instance?.cancel() }
            if (state == "transcribing") targetInvalidated = true
            target = if (state == "transcribing") target else null
            hide(); return
        }
        if (state == "recording" || state == "transcribing") {
            val original = target
            if (original == null || original != focused || !original.refresh() || !original.isFocused) {
                targetInvalidated = true
                if (state == "recording") { lastCancelCode = 2; finishing = false; MicrophoneService.instance?.cancel(); state = "ready" }
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
            this.y = y.coerceIn(0, (display.heightPixels - size).coerceAtLeast(0))
        }
        homeX = params!!.x
        bubble = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        renderNow()
        try { wm.addView(bubble, params) } catch (_: Exception) { bubble = null; params = null }
    }
    private fun hide() {
        main.removeCallbacks(redraw)
        gestureButton = null
        bubble?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        bubble = null; params = null
    }
    private fun cancelAndHide() { transcriptGeneration++; finishing = false; MicrophoneService.instance?.cancel(); target = null; hide() }
    private fun button(symbol: String, label: String, background: Int, size: Int, radius: Float): BubbleButton = BubbleButton(this).apply {
        text = symbol; textSize = 23f; gravity = Gravity.CENTER
        setTextColor(if (settings.darkMode) Color.BLACK else Color.WHITE)
        contentDescription = label
        setPadding(dp(9), dp(4), dp(9), dp(4))
        this.background = GradientDrawable().apply { setColor(background); cornerRadius = radius }
        layoutParams = LinearLayout.LayoutParams(dp(size), dp(settings.bubbleSizeDp)).apply { marginEnd = dp(5) }
    }
    // Do not tear down children from inside a child's touch dispatch; Android's
    // hardware renderer can still be traversing the previous child array.
    private fun render() { main.removeCallbacks(redraw); main.post(redraw) }
    private fun renderNow() {
        val layout = bubble ?: return
        gestureButton = null
        layout.removeAllViews()
        val color = if (settings.darkMode) 0xffaaa0b2.toInt() else 0xff4d266e.toInt()
        val size = settings.bubbleSizeDp
        val radius = when (settings.bubbleStyle) {
            "square" -> dp(17).toFloat()
            else -> dp(size / 2).toFloat()
        }
        if (state == "ready") {
            val label = button("", "Hold to dictate, tap to start", color,
                if (settings.bubbleStyle == "pill") size * 2 else size, radius)
            label.visual = BubbleButton.Visual.WAVEFORM
            gestureButton = label
            label.alpha = settings.bubbleAlpha
            label.setOnClickListener { recordingMode = "tap"; beginRecording() }
            label.setOnTouchListener(object : View.OnTouchListener {
                private var downX = 0f; private var downY = 0f
                private var originX = 0; private var originY = 0
                private var moved = false; private var holding = false
                private val hold = Runnable {
                    if (!moved) {
                        holding = true; holdGestureActive = true; recordingMode = "hold"; beginRecording()
                        if (state == "recording") {
                            label.visual = BubbleButton.Visual.RECORDING
                            label.contentDescription = "Recording; release to transcribe"
                        }
                    }
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
                                p.y = (originY + dy.toInt()).coerceIn(0, (resources.displayMetrics.heightPixels - dp(size)).coerceAtLeast(0))
                                try { wm.updateViewLayout(layout, p) } catch (_: Exception) {}
                            }
                        }
                        MotionEvent.ACTION_CANCEL -> {
                            main.removeCallbacks(hold)
                            holdGestureActive = false
                            if (holding) { lastCancelCode = 3; transcriptGeneration++; finishing = false; MicrophoneService.instance?.cancel() }
                            state = "ready"; render()
                        }
                        MotionEvent.ACTION_UP -> {
                            main.removeCallbacks(hold)
                            if (moved) { homeX = p.x; settings.bubbleX = p.x; settings.bubbleY = p.y }
                            else if (holding) {
                                holdGestureActive = false
                                if (state == "recording") {
                                    finishing = true
                                    MicrophoneService.instance?.finish()
                                    state = "transcribing"
                                }
                                label.visual = BubbleButton.Visual.SPINNER
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
            val cancel = button("×", "Cancel dictation", color, size, radius)
            cancel.alpha = settings.bubbleAlpha
            cancel.setOnClickListener { lastCancelCode = 4; transcriptGeneration++; finishing = false; MicrophoneService.instance?.cancel(); state = "ready"; render() }
            layout.addView(cancel)
            val preferredMeterWidth = if (settings.bubbleStyle == "pill") size * 2 else size + 40
            // Preserve both action buttons on compact Android 11+ displays.
            val availableMeterWidth = ((resources.displayMetrics.widthPixels - dp(size * 2 + 15)) /
                resources.displayMetrics.density).toInt().coerceAtLeast(44)
            val meter = button("", if (state == "recording" && !finishing) "Recording" else "Transcribing", color,
                minOf(preferredMeterWidth, availableMeterWidth), radius)
            meter.visual = if (state == "recording" && !finishing) BubbleButton.Visual.RECORDING else BubbleButton.Visual.SPINNER
            meter.alpha = settings.bubbleAlpha
            layout.addView(meter)
            if (recordingMode == "tap" && state == "recording" && !finishing) {
                val submit = button("✓", "Submit dictation", 0xff653783.toInt(), size, radius)
                submit.alpha = settings.bubbleAlpha
                submit.setOnClickListener {
                    if (state == "recording") {
                        // Show progress at the gesture itself. Waiting for the
                        // service callback can leave the old meter visible for
                        // several frames when Gemini finalizes very quickly.
                        state = "transcribing"
                        finishing = true
                        meter.visual = BubbleButton.Visual.SPINNER
                        meter.contentDescription = "Transcribing"
                        render()
                        MicrophoneService.instance?.finish()
                    }
                }
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
        // Accessibility events are debounced. Recheck focus at the gesture itself
        // so a disappearing editor cannot briefly start microphone capture.
        val current = target
        val focused = focusedEditor()
        if (current == null || focused == null || focused != current ||
            focused.windowId != targetWindow || !current.refresh() || !current.isFocused) {
            holdGestureActive = false
            updateVisibility()
            return
        }
        val mic = MicrophoneService.instance
        if (mic == null) {
            notifyUser("Open Whisproid and tap Enable dictation first")
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        }
        mic.listener = this
        lastError = null
        lastCancelCode = 0
        finishing = false
        targetInvalidated = false
        if (mic.begin()) { state = "recording"; if (!holdGestureActive) render() }
        else holdGestureActive = false
    }
    override fun onState(state: String) {
        main.post {
            if (state == "transcribing") finishing = true
            this.state = state
            if (holdGestureActive) {
                gestureButton?.visual = if (finishing || state == "transcribing") BubbleButton.Visual.SPINNER
                    else if (state == "recording") BubbleButton.Visual.RECORDING else BubbleButton.Visual.WAVEFORM
            } else if (bubble != null) render()
        }
    }
    override fun onTranscript(text: String) {
        val generation = transcriptGeneration
        main.post {
            // A finalized transcript belongs in local history even if the field
            // changes or the overlay is interrupted before this callback runs.
            HistoryStore(this).use { it.add(text) }
            if (generation != transcriptGeneration) return@post
            val node = target
            val stillFocused = focusedEditor()
            if (targetInvalidated || node == null || !node.refresh() || !node.isFocused || stillFocused != node || stillFocused.windowId != targetWindow) {
                notifyUser("Transcript saved in history; text field changed")
            } else if (!insert(node, text)) {
                notifyUser("Transcript saved in history; open it to copy")
            }
            state = "ready"; updateVisibility(); render()
            finishing = false
        }
    }
    private fun insert(node: AccessibilityNodeInfo, words: String): Boolean {
        // WhatsApp and Telegram can report the visible placeholder as node.text
        // without marking it as a hint. Let the editor insert at its real cursor
        // rather than constructing replacement text from that ambiguous snapshot.
        if (useNativePaste(node.packageName?.toString())) return paste(node, words)
        // AccessibilityNodeInfo.text may expose an empty editor's hint as text.
        val current = editableText(node.text?.toString().orEmpty(), node.hintText?.toString(),
            node.isShowingHintText, node.textSelectionStart, node.textSelectionEnd)
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
        return paste(node, words)
    }
    private fun paste(node: AccessibilityNodeInfo, words: String): Boolean {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Whisproid transcription", words))
        return node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
    }
    override fun onFailure(message: String) { main.post { lastError = message; finishing = false; state = "ready"; render(); notifyUser(message) } }
    private fun notifyUser(message: String) { Toast.makeText(this, message, Toast.LENGTH_LONG).show() }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
