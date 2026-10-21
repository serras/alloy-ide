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
    const val VACUITY_CHECK = "Vacuity check"
    private const val STARTING = "Starting the solver..."

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
        log.takeLast(MAX_LOG_LINES).mapTo(lines, ::html)
        result.details.failure?.lines()?.mapTo(lines, ::escape)
        result.vacuity?.let { vacuity ->
            lines += ""
            lines += "<b>$VACUITY_CHECK</b>"
            vacuity(vacuity).mapTo(lines, ::escape)
            // The analyzer's output of the queries, without the blank lines between them
            result.details.vacuityLog.filter { it.text.isNotBlank() && it.text != STARTING }
                .takeLast(MAX_LOG_LINES).mapTo(lines, ::html)
        }
        return lines.joinToString("<br>", prefix = "<html>", postfix = "</html>")
    }

    /** The conclusion of a vacuity check, and what was solved to reach it. */
    fun vacuity(vacuity: Vacuity): List<String> = when (vacuity) {
        Vacuity.Checking -> listOf("Checking whether the check passed vacuously…")
        is Vacuity.NotVacuous -> if (vacuity.premiseText != null) {
            listOf(
                "The facts and the premise ${vacuity.premiseText} have an instance, so the check did not pass vacuously.",
                "Solved the facts and the premise: instance found · ${vacuity.millis} ms",
            )
        } else {
            listOf("The facts have an instance, so the check did not pass vacuously.", "Solved the facts: instance found · ${vacuity.millis} ms")
        }
        is Vacuity.VacuousFacts -> listOf(
            "The facts have no instance within the scope, so the check passed vacuously.",
            (if (vacuity.premiseText != null) "Solved the facts and the premise, then the facts" else "Solved the facts") +
                ": no instance found · ${vacuity.millis} ms${core(vacuity.coreSize)}",
        )
        is Vacuity.VacuousPremise -> listOf(
            "The assertion's premise ${vacuity.premiseText} has no instance within the scope, so the check passed vacuously.",
            "Solved the facts and the premise: no instance found · ${vacuity.millis} ms${core(vacuity.coreSize)}; the facts alone have an instance",
        )
        is Vacuity.Unknown -> listOf("Vacuity was not determined. ${vacuity.reason.trimEnd('.')}.")
    }

    private fun core(size: Int): String = if (size > 0) ", unsat core of $size top-level formulas" else ""

    private fun html(line: AlloyRunLog.Line): String =
        line.spans.joinToString("") { span -> if (span.bold) "<b>${escape(span.text)}</b>" else escape(span.text) }

    /** The analyzer indents its details with spaces, which HTML would collapse. */
    private fun escape(text: String): String {
        val indent = text.length - text.trimStart(' ').length
        return "&nbsp;".repeat(indent) + StringUtil.escapeXmlEntities(text.substring(indent))
    }
}
