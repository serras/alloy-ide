package com.serranofp.alloy.run.tests

import com.serranofp.alloy.run.AlloyCommandResultTooltip
import com.serranofp.alloy.run.CommandResult
import com.serranofp.alloy.run.CommandState
import com.serranofp.alloy.run.Expectation
import com.serranofp.alloy.run.Verdict
import com.serranofp.alloy.run.verdict

/** How a command's result reads in the test tree: its output, and why it failed. */
object AlloyCommandTestResult {
    /** The analyzer output of the result, as its hint's tooltip shows it, in plain text. */
    fun output(result: CommandResult): String = AlloyCommandResultTooltip.text(result)

    /**
     * The message and details of a failed test, or null when it passed: an execution that failed, a vacuous
     * pass, or an outcome contrary to the command's `expect`.
     */
    fun failure(result: CommandResult): Pair<String, String?>? {
        val outcome = result.summary.substringBefore(" · ")
        return when {
            result.state == CommandState.Failed -> outcome to result.details.failure
            verdict(result) == Verdict.Vacuous -> "$outcome. ${AlloyCommandResultTooltip.vacuity(result.vacuity!!).first()}" to null
            result.expectation == Expectation.Failed -> outcome to null
            else -> null
        }
    }
}
