package com.serranofp.alloy.run

import com.google.gson.JsonParser
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.serranofp.alloy.lsp.AlloyExecutionMessage
import com.serranofp.alloy.lsp.AlloyMessageType
import com.serranofp.alloy.lsp.CommandsListResult
import com.serranofp.alloy.lsp.CommandsListResultItem
import com.serranofp.alloy.settings.AlloySettings
import org.eclipse.lsp4j.Command
import java.nio.file.Files
import java.nio.file.Path

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

/** An execution of [command] in [file] that analyzes the current document and settings, as a launch does. */
fun newExecution(
    project: Project,
    file: VirtualFile,
    command: AlloyCommandRef = AlloyCommandRef("show", 0),
    stopTimeoutMillis: Long = AlloyCommandExecution.STOP_TIMEOUT_MILLIS,
): AlloyCommandExecution =
    AlloyCommandExecution(
        AlloyRunService.getInstance(project),
        AlloyCommandResults.getInstance(project),
        file,
        command,
        AlloyCommandResults.getInstance(project).stamp(FileDocumentManager.getInstance().getDocument(file)!!),
        AlloySettings.getInstance().generation,
        stopTimeoutMillis,
    )
