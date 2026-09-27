package dev.hermitm0nk.flowbubble

import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

/** Separate test-APK editor for cross-package accessibility overlay checks. */
class HostActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            isFocusableInTouchMode = true
            setPadding(30, 100, 30, 30)
        }
        root.addView(TextView(this).apply { text = "External editor test host"; textSize = 23f })
        val editor = EditText(this).apply { hint = "Write a message"; setSingleLine(false) }
        root.addView(editor)
        setContentView(root)
        if (intent.getBooleanExtra("focus", false)) {
            editor.requestFocus()
            editor.postDelayed({
                (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                    .showSoftInput(editor, InputMethodManager.SHOW_IMPLICIT)
            }, 300)
        } else root.requestFocus()
    }
}
