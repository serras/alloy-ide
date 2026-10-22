package com.serranofp.alloy.settings

import java.util.prefs.AbstractPreferences
import java.util.prefs.Preferences
import java.util.prefs.PreferencesFactory

/**
 * The `java.util.prefs` of the tests (set as `java.util.prefs.PreferencesFactory` in the build): kept in memory,
 * so that no test reads or writes the developer's own preferences, such as those of the Alloy desktop application.
 */
class InMemoryPreferencesFactory : PreferencesFactory {
    override fun systemRoot(): Preferences = SYSTEM

    override fun userRoot(): Preferences = USER

    private class Node(parent: Node?, name: String) : AbstractPreferences(parent, name) {
        private val values = mutableMapOf<String, String>()
        private val children = mutableMapOf<String, Node>()

        override fun putSpi(key: String, value: String) {
            values[key] = value
        }

        override fun getSpi(key: String): String? = values[key]

        override fun removeSpi(key: String) {
            values.remove(key)
        }

        override fun removeNodeSpi() {
            (parent() as Node?)?.children?.remove(name())
        }

        override fun keysSpi(): Array<String> = values.keys.toTypedArray()

        override fun childrenNamesSpi(): Array<String> = children.keys.toTypedArray()

        override fun childSpi(name: String): AbstractPreferences = children.getOrPut(name) { Node(this, name) }

        override fun syncSpi() {}

        override fun flushSpi() {}
    }

    private companion object {
        val USER: Preferences = Node(null, "")
        val SYSTEM: Preferences = Node(null, "")
    }
}
