package com.serranofp.alloy.run

import com.intellij.execution.filters.HyperlinkInfo
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.ui.ConsoleView
import com.intellij.execution.ui.ConsoleViewContentType
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.util.concurrency.AppExecutorUtil
import com.serranofp.alloy.lsp.AlloyExecutionMessage
import com.serranofp.alloy.lsp.AlloyMessageType
import java.awt.Font
import java.io.OutputStream
import java.util.concurrent.TimeUnit

/** Where a run prints; a [ConsoleView] in the IDE, a recorder in tests. */
interface AlloyConsoleSink {
    fun print(text: String, contentType: ConsoleViewContentType)

    fun printHyperlink(text: String, info: HyperlinkInfo)
}

class ConsoleViewSink(private val console: ConsoleView) : AlloyConsoleSink {
    override fun print(text: String, contentType: ConsoleViewContentType) = console.print(text, contentType)

    override fun printHyperlink(text: String, info: HyperlinkInfo) = console.printHyperlink(text, info)
}

/**
 * A run of an Alloy command. There is no OS process: the analysis runs inside the language server, which
 * streams `alloy/showExecutionOutput` messages that [AlloyRunService] routes here.
 */
class AlloyRunProcessHandler(
    private val service: AlloyRunService,
    private val sink: AlloyConsoleSink,
    private val stopTimeoutMillis: Long = STOP_TIMEOUT_MILLIS,
) : ProcessHandler() {
    /** Set once the server has been found; until then Stop just ends the run. */
    @Volatile
    var connection: AlloyServerConnection? = null

    private var finished = false

    /**
     * Renders one message like the upstream VS Code client: the text already contains its line breaks
     * (`lineBreak = false` only marks a message that the next one continues), and `replaceLast` replaces
     * the previous message, which the server only sets after a progress line without a trailing newline.
     */
    fun render(message: AlloyExecutionMessage) {
        val text = message.message.orEmpty()
        val type = message.type
        if (text.isNotEmpty()) {
            val prefix = if (message.replaceLast) "\r" else ""
            val link = message.link
            if (link != null) {
                if (prefix.isNotEmpty()) sink.print(prefix, ConsoleViewContentType.NORMAL_OUTPUT)
                sink.printHyperlink(text, HyperlinkInfo { connection?.openModel(link) })
            } else {
                sink.print(prefix + text, contentType(message))
            }
        }
        if (type == AlloyMessageType.RunCompleted) terminate(0)
    }

    /** Ends the run with an error that is shown in the console. */
    fun fail(message: String) {
        if (isFinished()) return
        sink.print(message + "\n", ConsoleViewContentType.ERROR_OUTPUT)
        terminate(1)
    }

    fun terminate(exitCode: Int) {
        synchronized(this) {
            if (finished) return
            finished = true
        }
        service.runFinished(this)
        notifyProcessTerminated(exitCode)
    }

    @Synchronized
    fun isFinished(): Boolean = finished

    /** Stop: the server aborts the analysis and answers `RunCompleted "Stopped"`, which ends the run. */
    override fun destroyProcessImpl() {
        val server = connection
        if (server == null) {
            sink.print("Stopped\n", BOLD_OUTPUT)
            terminate(1)
            return
        }
        server.stop()
        AppExecutorUtil.getAppScheduledExecutorService().schedule({
            if (!isFinished()) {
                sink.print("\nStopped (the Alloy language server did not confirm)\n", ConsoleViewContentType.LOG_WARNING_OUTPUT)
                terminate(1)
            }
        }, stopTimeoutMillis, TimeUnit.MILLISECONDS)
    }

    override fun detachProcessImpl() {
        destroyProcessImpl()
    }

    override fun detachIsDefault(): Boolean = false

    override fun getProcessInput(): OutputStream? = null

    private fun contentType(message: AlloyExecutionMessage): ConsoleViewContentType = when {
        message.type == AlloyMessageType.Warning -> ConsoleViewContentType.LOG_WARNING_OUTPUT
        message.bold -> BOLD_OUTPUT
        else -> ConsoleViewContentType.NORMAL_OUTPUT
    }

    companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L

        @JvmField
        val BOLD_OUTPUT = ConsoleViewContentType("ALLOY_BOLD_OUTPUT", TextAttributes(null, null, null, null, Font.BOLD))
    }
}
