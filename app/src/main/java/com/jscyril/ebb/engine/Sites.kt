package com.jscyril.ebb.engine

/**
 * Websites that share a watched app's limits, so the browser version of a blocked app is blocked too.
 * Pure (no Android) so the matching is unit-tested.
 */
object Sites {
    /** Default sites for well-known apps; used until the user edits an app's list. */
    private val KNOWN: Map<String, List<String>> = mapOf(
        "com.instagram.android" to listOf("instagram.com"),
        "com.instagram.barcelona" to listOf("threads.net", "threads.com"),
        "com.google.android.youtube" to listOf("youtube.com", "youtu.be"),
        "app.revanced.android.youtube" to listOf("youtube.com", "youtu.be"),
        "app.morphe.android.youtube" to listOf("youtube.com", "youtu.be"),
        "com.google.android.apps.youtube.music" to listOf("music.youtube.com"),
        "app.revanced.android.apps.youtube.music" to listOf("music.youtube.com"),
        "com.zhiliaoapp.musically" to listOf("tiktok.com"),
        "com.ss.android.ugc.trill" to listOf("tiktok.com"),
        "com.facebook.katana" to listOf("facebook.com", "fb.com"),
        "com.facebook.lite" to listOf("facebook.com", "fb.com"),
        "com.twitter.android" to listOf("x.com", "twitter.com"),
        "com.reddit.frontpage" to listOf("reddit.com"),
        "com.snapchat.android" to listOf("snapchat.com"),
        "com.pinterest" to listOf("pinterest.com"),
        "com.linkedin.android" to listOf("linkedin.com"),
        "com.netflix.mediaclient" to listOf("netflix.com"),
        "tv.twitch.android.app" to listOf("twitch.tv"),
        "com.discord" to listOf("discord.com"),
        "com.tumblr" to listOf("tumblr.com"),
        "com.vanced.android.youtube" to listOf("youtube.com", "youtu.be"),
    )

    /** The sites an app's limits cover: the user's list once edited, else the defaults. */
    fun of(rule: AppRule): List<String> = rule.domains ?: KNOWN[rule.pkg].orEmpty()

    /**
     * Host from what a browser's address bar shows ("instagram.com/reels", "https://m.youtube.com/watch?v=1").
     * Null for text that isn't a web address (a search query, empty bar).
     */
    fun host(text: String): String? {
        var t = text.trim().lowercase()
        if (t.isEmpty() || t.any { it.isWhitespace() }) return null
        t = t.substringAfter("://")
        t = t.takeWhile { it != '/' && it != '?' && it != '#' }
        t = t.substringAfterLast('@').substringBefore(':').trimEnd('.')
        if (!t.contains('.') || t.startsWith('.') || t.any { !(it.isLetterOrDigit() || it == '.' || it == '-') }) return null
        return t.removePrefix("www.")
    }

    /** A site the user typed into the editor, cleaned to a bare domain; null if it isn't one. */
    fun normalize(input: String): String? = host(input)

    /** The domain itself or any subdomain of it (m.instagram.com), never a lookalike (notinstagram.com). */
    fun matches(host: String, domain: String): Boolean = host == domain || host.endsWith(".$domain")

    /** The watched app whose sites cover [host]; the most specific domain wins (music.youtube.com over youtube.com). */
    fun appFor(host: String, rules: Collection<AppRule>): String? =
        rules.flatMap { r -> of(r).filter { matches(host, it) }.map { r.pkg to it.length } }.maxByOrNull { it.second }?.first
}
