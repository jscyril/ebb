package com.jscyril.ebb.engine

import java.util.concurrent.ConcurrentHashMap

/** Saved names of watched apps, for showing an app that is not installed right now. Filled as rules load. */
object AppNames {
    private val names = ConcurrentHashMap<String, String>()
    fun remember(rules: Collection<AppRule>) = rules.forEach { r -> r.name?.let { names[r.pkg] = it } }
    operator fun get(pkg: String): String? = names[pkg]
}
