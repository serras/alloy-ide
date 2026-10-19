package com.serranofp.alloy.run

import com.google.gson.JsonParser
import com.intellij.execution.filters.HyperlinkInfo
import com.intellij.execution.ui.ConsoleViewContentType
import com.intellij.openapi.vfs.VirtualFile
import com.serranofp.alloy.lsp.AlloyExecutionMessage
import com.serranofp.alloy.lsp.AlloyMessageType
import com.serranofp.alloy.lsp.CommandsListResult
import com.serranofp.alloy.lsp.CommandsListResultItem
import org.eclipse.lsp4j.Command
import java.nio.file.Files
import java.nio.file.Path

class RecordingSink : AlloyConsoleSink {
    data class Segment(val text: String, val type: ConsoleViewContentType?, val link: HyperlinkInfo? = null)

    val segments = mutableListOf<Segment>()

    val text: String
        get() = segments.joinToString("") { it.text }

    override fun print(text: String, contentType: ConsoleViewContentType) {
        segments += Segment(text, contentType)
    }

    override fun printHyperlink(text: String, info: HyperlinkInfo) {
        segments += Segment(text, null, info)
    }
}

/** Stands in for the language server: answers like the 6.2.0 server, or not at all. */
class FakeServerConnection(
    private val service: AlloyRunService,
    var commands: CommandsListResult? = CommandsListResult(),
    var answersStop: Boolean = true,
) : AlloyServerConnection {
    val listed = mutableListOf<String>()
    val executed = mutableListOf<List<Any?>>()
    val opened = mutableListOf<String>()
    var stops = 0

    override fun uriOf(file: VirtualFile): String = "file://${file.path}"

    override fun listCommands(uri: String) {
        listed += uri
        commands?.let(service::commandsListed)
    }

    override fun execute(arguments: List<Any?>) {
        executed += arguments
    }

    override fun stop() {
        stops++
        if (answersStop) {
            service.executionOutput(AlloyExecutionMessage(AlloyMessageType.RunCompleted.ordinal, "Stopped", bold = true))
        }
    }

    override fun openModel(link: String) {
        opened += link
    }
}

fun item(title: String, uri: String, index: Int, line: Int): CommandsListResultItem =
    CommandsListResultItem(title, Command("Execute", "ExecuteAlloyCommand", listOf(uri, index, line, 0)))

/** `alloy/showExecutionOutput` messages captured from the 6.2.0 server (see testData/lsp). */
fun capturedOutput(): List<AlloyExecutionMessage> =
    JsonParser.parseString(Files.readString(Path.of("src/test/testData/lsp/showExecutionOutput.json"))).asJsonArray.map {
        val p = it.asJsonObject["params"].asJsonObject
        AlloyExecutionMessage(
            messageType = p["messageType"].asInt,
            message = p["message"]?.asString,
            link = p["link"]?.asString,
            bold = p["bold"].asBoolean,
            replaceLast = p["replaceLast"].asBoolean,
            lineBreak = p["lineBreak"].asBoolean,
        )
    }
