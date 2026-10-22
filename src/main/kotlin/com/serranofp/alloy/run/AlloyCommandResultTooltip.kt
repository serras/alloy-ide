package com.serranofp.alloy.run

import com.intellij.openapi.util.text.StringUtil
import com.serranofp.alloy.AlloyBundle

/**
 * The tooltip of a result hint. The declarative inlay API takes it as a single string, and hint tooltips
 * render HTML, so lines are joined with `<br>` and the analyzer's bold messages are kept.
 */
object AlloyCommandResultTooltip {
    /** Long logs (many warnings, for example) keep their end, where the result is. */
    const val MAX_LOG_LINES = 40

    val FILE_CHANGED: String get() = AlloyBundle.message("run.tooltip.file.changed")
    val SETTINGS_CHANGED: String get() = AlloyBundle.message("run.tooltip.settings.changed")
    val VACUITY_CHECK: String get() = AlloyBundle.message("run.tooltip.vacuity.check")
    private const val STARTING = "Starting the solver..."

    fun html(result: CommandResult, outdated: Outdated?): String {
        val lines = mutableListOf<String>()
        when (outdated) {
            Outdated.FileChanged -> lines += "<i>$FILE_CHANGED</i>"
            Outdated.SettingsChanged -> lines += "<i>$SETTINGS_CHANGED</i>"
            null -> {}
        }
        lines += lines(result, MAX_LOG_LINES, text = ::escape, heading = { "<b>$it</b>" }, log = ::html)
        return lines.joinToString("<br>", prefix = "<html>", postfix = "</html>")
    }

    /** The details of [result] in plain text, as the test tree shows them. */
    fun text(result: CommandResult): String = lines(result, Int.MAX_VALUE, text = { it }, heading = { it }, log = { it.text }).joinToString("\n")

    /**
     * The warnings, the analyzer's log, the failure and the vacuity check of [result], one string per line,
     * with at most [maxLogLines] of each log. The tooltip and the test tree only differ in how they [log] the
     * analyzer's lines, write their own [text], and mark a [heading].
     */
    private fun lines(
        result: CommandResult,
        maxLogLines: Int,
        text: (String) -> String,
        heading: (String) -> String,
        log: (AlloyRunLog.Line) -> String,
    ): List<String> {
        val lines = mutableListOf<String>()
        result.details.warnings.flatMap { it.lines() }.mapTo(lines, text)
        val analyzerLog = result.details.log
        if (analyzerLog.size > maxLogLines) lines += "…"
        analyzerLog.takeLast(maxLogLines).mapTo(lines, log)
        result.details.failure?.lines()?.mapTo(lines, text)
        result.vacuity?.let { vacuity ->
            lines += ""
            lines += heading(VACUITY_CHECK)
            vacuity(vacuity).mapTo(lines, text)
            // The analyzer's output of the queries, without the blank lines between them
            result.details.vacuityLog.filter { it.text.isNotBlank() && it.text != STARTING }
                .takeLast(maxLogLines).mapTo(lines, log)
        }
        return lines
    }

    /** The conclusion of a vacuity check, and what was solved to reach it. */
    fun vacuity(vacuity: Vacuity): List<String> = when (vacuity) {
        Vacuity.Checking -> listOf(AlloyBundle.message("run.vacuity.checking"))
        is Vacuity.NotVacuous -> if (vacuity.premiseText != null) {
            listOf(
                AlloyBundle.message("run.vacuity.not.vacuous.premise", vacuity.premiseText),
                AlloyBundle.message("run.vacuity.solved.premise.instance", vacuity.millis.toString()),
            )
        } else {
            listOf(AlloyBundle.message("run.vacuity.not.vacuous"), AlloyBundle.message("run.vacuity.solved.facts.instance", vacuity.millis.toString()))
        }
        is Vacuity.VacuousFacts -> listOf(
            AlloyBundle.message("run.vacuity.vacuous.facts"),
            AlloyBundle.message(
                if (vacuity.premiseText != null) "run.vacuity.solved.premise.then.facts.none" else "run.vacuity.solved.facts.none",
                vacuity.millis.toString(), core(vacuity.coreSize),
            ),
        )
        is Vacuity.VacuousPremise -> listOf(
            AlloyBundle.message("run.vacuity.vacuous.premise", vacuity.premiseText),
            AlloyBundle.message("run.vacuity.solved.premise.none", vacuity.millis.toString(), core(vacuity.coreSize)),
        )
        is Vacuity.Unknown -> listOf(AlloyBundle.message("run.vacuity.unknown", vacuity.reason.trimEnd('.')))
    }

    private fun core(size: Int): String = if (size > 0) AlloyBundle.message("run.vacuity.core", size.toString()) else ""

    private fun html(line: AlloyRunLog.Line): String =
        line.spans.joinToString("") { span -> if (span.bold) "<b>${escape(span.text)}</b>" else escape(span.text) }

    /** The analyzer indents its details with spaces, which HTML would collapse. */
    private fun escape(text: String): String {
        val indent = text.length - text.trimStart(' ').length
        return "&nbsp;".repeat(indent) + StringUtil.escapeXmlEntities(text.substring(indent))
    }
}
