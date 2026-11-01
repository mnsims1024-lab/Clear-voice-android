package com.margie.clearvoice

import android.accessibilityservice.AccessibilityService
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/** Types the cleaned-up text into whichever text box you touched. */
class InputService : AccessibilityService() {

    private var lastEditable: AccessibilityNodeInfo? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType == AccessibilityEvent.TYPE_VIEW_FOCUSED) {
            val n = event.source
            if (n != null && n.isEditable) lastEditable = n
        }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    fun insert(text: String): Boolean {
        val node = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?: lastEditable?.also { it.refresh() }
            ?: return false
        if (!node.isEditable) return false

        val cur = if (node.isShowingHintText) "" else node.text?.toString() ?: ""
        var s = node.textSelectionStart
        var e = node.textSelectionEnd
        if (s < 0 || e < 0 || s > cur.length || e > cur.length) { s = cur.length; e = cur.length }
        if (s > e) { val t = s; s = e; e = t }

        val before = cur.substring(0, s)
        val after = cur.substring(e)
        val sep = if (before.isNotEmpty() && !before.last().isWhitespace()) " " else ""
        val head = before + sep + text
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, head + after)
        }
        val ok = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        if (ok) {
            val sel = Bundle().apply {
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, head.length)
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, head.length)
            }
            node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, sel)
        }
        return ok
    }

    companion object {
        @Volatile var instance: InputService? = null
    }
}
