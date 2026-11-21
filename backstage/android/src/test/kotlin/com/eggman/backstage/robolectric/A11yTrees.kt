package com.eggman.backstage.robolectric

import android.graphics.Rect
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import java.util.concurrent.CopyOnWriteArrayList

/** Builds real (Robolectric-shadowed) AccessibilityNodeInfo / AccessibilityWindowInfo trees. */
object A11yTrees {
    fun node(
        text: String? = null,
        cls: String = "android.widget.TextView",
        bounds: Rect,
        pkg: String = "com.example.mail",
        id: String? = null,
        desc: String? = null,
        hint: String? = null,
        clickable: Boolean = false,
        editable: Boolean = false,
        scrollable: Boolean = false,
        focused: Boolean = false,
        visible: Boolean = true,
        children: List<AccessibilityNodeInfo> = emptyList(),
    ): AccessibilityNodeInfo = AccessibilityNodeInfo().apply {
        this.text = text
        className = cls
        setBoundsInScreen(bounds)
        packageName = pkg
        viewIdResourceName = id
        contentDescription = desc
        hintText = hint
        isClickable = clickable
        isEditable = editable
        isScrollable = scrollable
        isFocusable = clickable || editable
        isFocused = focused
        isEnabled = true
        isVisibleToUser = visible
        children.forEach { shadowOf(this).addChild(it) }
        // getChild/getParent/getRoot hand out copies (as on a device); the listener object is shared by every
        // copy, so actions performed on any copy are recorded against this node.
        val log = CopyOnWriteArrayList<Pair<Int, Bundle?>>()
        synchronized(logs) { logs[this] = log }
        shadowOf(this).setOnPerformActionListener { action, args -> log += action to args?.let(::Bundle); true }
    }

    private val logs = java.util.IdentityHashMap<AccessibilityNodeInfo, CopyOnWriteArrayList<Pair<Int, Bundle?>>>()

    /** Actions performed on [n] (or any copy of it), with their arguments. */
    fun actionsWithArgs(n: AccessibilityNodeInfo): List<Pair<Int, Bundle?>> = synchronized(logs) { logs[n].orEmpty().toList() }

    fun actions(n: AccessibilityNodeInfo): List<Int> = actionsWithArgs(n).map { it.first }

    fun window(
        id: Int,
        root: AccessibilityNodeInfo?,
        bounds: Rect,
        type: Int = AccessibilityWindowInfo.TYPE_APPLICATION,
        layer: Int = 1,
        displayId: Int = 0,
        title: String = "",
    ): AccessibilityWindowInfo = AccessibilityWindowInfo.obtain().also { w ->
        shadowOf(w).apply {
            setId(id)
            setType(type)
            setLayer(layer)
            setBoundsInScreen(bounds)
            setDisplayId(displayId)
            setTitle(title)
            setActive(type == AccessibilityWindowInfo.TYPE_APPLICATION)
            setFocused(type == AccessibilityWindowInfo.TYPE_APPLICATION)
            if (root != null) setRoot(root)
        }
    }

    /**
     * Creates the host accessibility service (MirrorService's stand-in) and connects it the way the system does, which
     * hands it to the agent ([com.eggman.backstage.a11y.AgentAccessibility.instance]).
   */
    fun connectService(): ServiceController<HostAccessibilityService> {
        val c = Robolectric.buildService(HostAccessibilityService::class.java).create()
        HostAccessibilityService::class.java.getDeclaredMethod("onServiceConnected").apply { isAccessible = true }.invoke(c.get())
        return c
    }

    /** The agent's view of the connected host service. */
    fun agent(): com.eggman.backstage.a11y.AgentAccessibility = com.eggman.backstage.a11y.AgentAccessibility.instance!!

    /** A mail app: heading, a clickable row whose label is a child, a search field, a list and a FAB (above a keyboard's area). */
    class MailScreen(pkg: String = "com.example.mail", displayId: Int = 0, width: Int = 1080, height: Int = 1920) {
        val heading = node("Inbox", bounds = Rect(0, 60, width, 160), pkg = pkg)
        val rowLabel = node("Anna: Lunch tomorrow?", bounds = Rect(40, 420, width - 40, 500), pkg = pkg)
        val row = node(cls = "android.widget.LinearLayout", bounds = Rect(0, 400, width, 520), pkg = pkg, clickable = true, children = listOf(rowLabel))
        val list = node(cls = "androidx.recyclerview.widget.RecyclerView", bounds = Rect(0, 380, width, height - 300), pkg = pkg,
            scrollable = true, id = "$pkg:id/list", children = listOf(row))
        val search = node(cls = "android.widget.EditText", bounds = Rect(40, 200, width - 40, 320), pkg = pkg, editable = true,
            hint = "Search mail", id = "$pkg:id/search", focused = true)
        val fab = node("Compose", cls = "android.widget.Button", bounds = Rect(width - 300, height - 760, width - 40, height - 620), pkg = pkg,
            clickable = true, id = "$pkg:id/fab")
        val footer = node("Archive", bounds = Rect(0, height - 200, width / 2, height - 100), pkg = pkg, clickable = true)
        val hidden = node("Secret draft", bounds = Rect(0, 1000, 500, 1100), pkg = pkg, visible = false)
        val root = node(cls = "android.widget.FrameLayout", bounds = Rect(0, 0, width, height), pkg = pkg,
            children = listOf(heading, search, list, fab, footer, hidden))
        val window = window(10, root, Rect(0, 0, width, height), displayId = displayId, title = "Mail")
    }
}
