package com.serranofp.alloy

import com.intellij.DynamicBundle
import org.jetbrains.annotations.Nls
import org.jetbrains.annotations.NonNls
import org.jetbrains.annotations.PropertyKey

private const val BUNDLE = "messages.AlloyBundle"

/** The plugin's user-visible strings, in `messages/AlloyBundle.properties`. */
object AlloyBundle : DynamicBundle(AlloyBundle::class.java,BUNDLE) {
    @Nls
    @JvmStatic
    fun message(@NonNls @PropertyKey(resourceBundle = BUNDLE) key: String, vararg params: Any): String =
        getMessage(key, *params)
}
