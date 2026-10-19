package com.serranofp.alloy.lsp

import com.intellij.openapi.project.Project
import com.intellij.util.messages.Topic
import org.eclipse.lsp4j.Command

/** `alloy/commandsListResult`: one item per command of the file, in source order. */
data class CommandsListResult(
    val commands: List<CommandsListResultItem> = emptyList(),
)

/** [command] carries the `ExecuteAlloyCommand` arguments `[uri, index, line, character]`. */
data class CommandsListResultItem(
    val title: String = "",
    val command: Command? = null,
)

/** Declaration order matters: the server's lsp4j 0.4.1 sends `messageType` as the enum ordinal. */
enum class AlloyMessageType { RunStarted, RunInProgress, RunResult, RunCompleted, Warning }

/** `alloy/showExecutionOutput`: one piece of analyzer output. Messages carry no run id. */
data class AlloyExecutionMessage(
    /** Ordinal of [AlloyMessageType]; see [type]. */
    val messageType: Int? = null,
    val message: String? = null,
    val link: String? = null,
    val bold: Boolean = false,
    val replaceLast: Boolean = false,
    val lineBreak: Boolean = true,
) {
    val type: AlloyMessageType?
        get() = messageType?.let { AlloyMessageType.entries.getOrNull(it) }
}

/** Project-level delivery of the server's custom notifications. */
interface AlloyServerNotifications {
    fun commandsListed(result: CommandsListResult) {}

    fun executionOutput(message: AlloyExecutionMessage) {}

    companion object {
        @JvmField
        @Topic.ProjectLevel
        val TOPIC: Topic<AlloyServerNotifications> = Topic(AlloyServerNotifications::class.java)

        fun publisher(project: Project): AlloyServerNotifications =
            project.messageBus.syncPublisher(TOPIC)
    }
}
