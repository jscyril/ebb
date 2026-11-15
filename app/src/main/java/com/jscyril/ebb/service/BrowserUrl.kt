package com.jscyril.ebb.service

import android.view.accessibility.AccessibilityNodeInfo
import com.jscyril.ebb.engine.Sites

/** Reads the host shown in a browser's address bar. Only these browsers are watched for sites. */
object BrowserUrl {
    /** Browser package to the view ids of its address bar (first found wins). */
    private val BARS: Map<String, List<String>> = buildMap {
        for (p in listOf("com.android.chrome", "com.chrome.beta", "com.chrome.dev", "com.chrome.canary", "com.brave.browser",
            "com.microsoft.emmx", "com.vivaldi.browser", "com.kiwibrowser.browser", "org.chromium.chrome")) put(p, listOf("$p:id/url_bar"))
        for (p in listOf("org.mozilla.firefox", "org.mozilla.firefox_beta", "org.mozilla.fenix", "io.github.forkmaintainers.iceraven"))
            put(p, listOf("$p:id/mozac_browser_toolbar_url_view"))
        put("org.mozilla.focus", listOf("org.mozilla.focus:id/display_url"))
        put("com.sec.android.app.sbrowser", listOf("com.sec.android.app.sbrowser:id/location_bar_edit_text"))
        put("com.opera.browser", listOf("com.opera.browser:id/url_field"))
        put("com.opera.mini.native", listOf("com.opera.mini.native:id/url_field"))
        put("com.duckduckgo.mobile.android", listOf("com.duckduckgo.mobile.android:id/omnibarTextInput"))
    }

    /** Allocation-free check for the hot event path. */
    fun isBrowser(pkg: CharSequence): Boolean = BARS.keys.any { it.contentEquals(pkg) }

    /** Address-bar ids, also matched by suffix, for browsers whose bar isn't a classic View (Firefox 160+ is Compose). */
    private val BAR_NAMES = setOf(
        "ADDRESSBAR_URL_BOX", "url_bar", "mozac_browser_toolbar_url_view", "location_bar_edit_text",
        "url_field", "omnibarTextInput", "display_url",
    )

    /**
     * Host in [root]'s address bar, or null when it can't be read or the user is typing in it
     * (typed text is not where they are yet). Recycles every node it obtains.
     */
    fun host(root: AccessibilityNodeInfo, pkg: String): String? {
        // Fast path: classic View ids resolve directly.
        for (id in BARS[pkg].orEmpty()) {
            val nodes = runCatching { root.findAccessibilityNodeInfosByViewId(id) }.getOrNull().orEmpty()
            val bar = nodes.firstOrNull()
            val h = bar?.let(::read)
            @Suppress("DEPRECATION") nodes.forEach { it.recycle() }
            if (bar != null) return h
        }
        // Compose toolbars: walk the tree (not into the page) for a node tagged as the address bar.
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        for (i in 0 until root.childCount) root.getChild(i)?.let(queue::add)
        var seen = 0
        var found: String? = null
        var hit = false
        while (queue.isNotEmpty()) {
            val n = queue.removeFirst()
            seen++
            val id = n.viewIdResourceName
            if (!hit && id != null && BAR_NAMES.any { id == it || id.endsWith("/$it") }) { hit = true; found = read(n) }
            if (!hit && seen < MAX_NODES && n.className?.toString() != "android.webkit.WebView") {
                for (i in 0 until n.childCount) n.getChild(i)?.let(queue::add)
            }
            @Suppress("DEPRECATION") n.recycle()
        }
        return found
    }

    /** The bar's text, or its description (" instagram.com. Search or enter address"): the first word is the site. */
    private fun read(bar: AccessibilityNodeInfo): String? {
        if (bar.isFocused) return null
        val raw = bar.text?.toString()?.takeIf { it.isNotBlank() } ?: bar.contentDescription?.toString() ?: return null
        val first = raw.trim().split(Regex("\\s+")).firstOrNull()?.trimEnd('.') ?: return null
        return Sites.host(first)
    }

    private const val MAX_NODES = 200
}
