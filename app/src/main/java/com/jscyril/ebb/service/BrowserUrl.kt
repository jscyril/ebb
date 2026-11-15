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

    /**
     * Host in [root]'s address bar, or null when it can't be read or the user is typing in it
     * (typed text is not where they are yet). Recycles every node it obtains.
     */
    fun host(root: AccessibilityNodeInfo, pkg: String): String? {
        for (id in BARS[pkg].orEmpty()) {
            val nodes = runCatching { root.findAccessibilityNodeInfosByViewId(id) }.getOrNull().orEmpty()
            val bar = nodes.firstOrNull()
            val text = bar?.takeUnless { it.isFocused }?.text?.toString()
            @Suppress("DEPRECATION") nodes.forEach { it.recycle() }
            if (bar != null) return text?.let(Sites::host)
        }
        return null
    }
}
