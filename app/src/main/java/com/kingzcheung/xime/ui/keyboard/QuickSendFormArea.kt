package com.kingzcheung.xime.ui.keyboard

import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.kingzcheung.xime.service.QuickSendFormEditTextHolder

private val QUICK_SEND_FORM_HEIGHT = 200

@Composable
fun QuickSendFormArea(
    backgroundColor: Color,
    textColor: Color,
    accentColor: Color,
    isDarkTheme: Boolean,
    isFocused: Boolean,
    initialText: String = "",
    cardBgColor: Color,
    editingItemId: Long? = null,
    onClose: (text: String) -> Unit,
    onFocusChange: (Boolean) -> Unit,
) {
    val closeButtonBg = if (isDarkTheme) Color(0xFF374151) else Color(0xFFF3F4F6)
    val title = if (editingItemId != null) "编辑快捷发送" else "添加快捷发送"

    CandidateBarOverlayPanel(
        heightDp = QUICK_SEND_FORM_HEIGHT,
        backgroundColor = backgroundColor,
        cardBgColor = cardBgColor,
        closeButtonBg = closeButtonBg,
        closeButtonColor = accentColor,
        title = title,
        titleColor = textColor,
        onCloseClick = {
            val et = QuickSendFormEditTextHolder.editText
            onClose(et?.text?.toString() ?: "")
        },
    ) {
        AndroidView(
            factory = { context ->
                android.widget.EditText(context).apply {
                    setBackgroundColor(android.graphics.Color.TRANSPARENT)
                    setTextColor(textColor.hashCode())
                    setHintTextColor((textColor.copy(alpha = 0.4f)).hashCode())
                    hint = "输入快捷发送内容"
                    textSize = 16f
                    isSingleLine = false
                    gravity = Gravity.TOP or Gravity.START
                    setPadding(12, 8, 12, 8)

                    setImeActionLabel("确定", EditorInfo.IME_ACTION_DONE)
                    imeOptions = EditorInfo.IME_FLAG_NO_ENTER_ACTION or
                        EditorInfo.IME_ACTION_DONE

                    onFocusChangeListener = View.OnFocusChangeListener { _, hasFocus ->
                        onFocusChange(hasFocus)
                    }
                    setOnClickListener {
                        onFocusChange(true)
                    }
                    QuickSendFormEditTextHolder.editText = this
                    if (isFocused) {
                        post { requestFocus() }
                    }
                }
            },
            update = { editText ->
                if (initialText.isNotEmpty() && !editText.text.toString().equals(initialText)) {
                    editText.setText(initialText)
                    editText.setSelection(initialText.length)
                }
            },
            modifier = Modifier.fillMaxSize(),
        )
    }
}
