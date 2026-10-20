package com.serranofp.alloy.run

import com.intellij.openapi.util.text.StringUtil

/**
 * The tooltip of a result hint. The declarative inlay API takes it as a single string, and hint tooltips
 * render HTML, so lines are joined with `<br>` and the analyzer's bold messages are kept.
 */
object AlloyCommandResultTooltip {
    /** Long logs (many warnings, for example) keep their end, where the result is. */
    const val MAX_LOG_LINES = 40

    const val FILE_CHANGED = "Outdated: the file changed after this analysis"
    const val SETTINGS_CHANGED = "Outdated: the analyzer settings changed after this analysis"

    fun html(result: CommandResult, outdated: Outdated?): String {
        val lines = mutableListOf<String>()
        when (outdated) {
            Outdated.FileChanged -> lines += "<i>$FILE_CHANGED</i>"
            Outdated.SettingsChanged -> lines += "<i>$SETTINGS_CHANGED</i>"
            null -> {}
        }
        result.details.warnings.flatMap { it.lines() }.mapTo(lines, ::escape)
        val log = result.details.log
        if (log.size > MAX_LOG_LINES) lines += "…"
        log.takeLast(MAX_LOG_LINES).mapTo(lines) { line ->
            line.spans.joinToString("") { span -> if (span.bold) "<b>${escape(span.text)}</b>" else escape(span.text) }
        }
        result.details.failure?.lines()?.mapTo(lines, ::escape)
        return lines.joinToString("<br>", prefix = "<html>", postfix = "</html>")
    }

    /** The analyzer indents its details with spaces, which HTML would collapse. */
    private fun escape(text: String): String {
        val indent = text.length - text.trimStart(' ').length
        return "&nbsp;".repeat(indent) + StringUtil.escapeXmlEntities(text.substring(indent))
    }
}
