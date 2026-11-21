package com.eggman.backstage.robolectric

import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowAccessibilityNodeInfo

/** Robolectric 4.16 lacks API 33 prefetch and does not simulate accepted text actions. */
@Implements(AccessibilityNodeInfo::class)
class PrefetchAccessibilityNodeInfoShadow : ShadowAccessibilityNodeInfo() {
    var pasteDelayMs: Long = 0
    private var pasteAt: Long? = null

    private fun clipboardText(): CharSequence? {
        val clipboard = RuntimeEnvironment.getApplication().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        return clipboard.primaryClip?.getItemAt(0)?.text
    }

    @Implementation
    override fun refresh(): Boolean {
        val refreshed = super.refresh()
        if (refreshed && pasteAt?.let { System.nanoTime() / 1_000_000 >= it } == true) {
            text = clipboardText()
            pasteAt = null
        }
        return refreshed
    }

    @Implementation(minSdk = 33)
    protected fun getChild(index: Int, prefetch: Int): AccessibilityNodeInfo? = super.getChild(index)

    @Implementation
    override fun performAction(action: Int, arguments: Bundle?): Boolean {
        val accepted = super.performAction(action, arguments)
        if (accepted) when (action) {
            AccessibilityNodeInfo.ACTION_SET_TEXT -> text = arguments?.getCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE)
            AccessibilityNodeInfo.ACTION_PASTE -> {
                if (pasteDelayMs == 0L) text = clipboardText()
                else pasteAt = System.nanoTime() / 1_000_000 + pasteDelayMs
            }
        }
        return accepted
    }
}
