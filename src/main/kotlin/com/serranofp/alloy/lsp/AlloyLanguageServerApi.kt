@file:Suppress("FunctionName")

package com.serranofp.alloy.lsp

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import org.eclipse.lsp4j.jsonrpc.services.JsonRequest
import org.eclipse.lsp4j.services.LanguageServer
import java.util.concurrent.CompletableFuture

/**
 * Custom requests of the Alloy 6.2.0 language server. They are declared without a JSON segment upstream,
 * so the wire names are the method names. Each one returns immediately; results arrive as notifications
 * (see [AlloyLsp4jClient]).
 *
 * The server only accepts a bare string or array as `params`. The IDE's lsp4j wraps a single `String` or
 * `List` parameter in an extra array, but writes Gson elements as they are, hence the parameter types.
 * Callers use the typed helpers below.
 */
interface AlloyLanguageServerApi : LanguageServer {
    @JsonRequest
    fun ListAlloyCommands(documentUri: JsonPrimitive): CompletableFuture<Void?>

    @JsonRequest
    fun ExecuteAlloyCommand(arguments: JsonArray): CompletableFuture<Void?>

    @JsonRequest
    fun StopExecution(params: JsonObject): CompletableFuture<Void?>

    @JsonRequest
    fun OpenModel(link: JsonPrimitive): CompletableFuture<Void?>
}

/** Replies with an `alloy/commandsListResult` notification for the document. */
fun AlloyLanguageServerApi.listAlloyCommands(documentUri: String): CompletableFuture<Void?> =
    ListAlloyCommands(JsonPrimitive(documentUri))

/**
 * Starts a command asynchronously. [arguments] are `[uri, index, line, character]` exactly as the server
 * lists them in `alloy/commandsListResult`. The server does nothing if an analysis is already running.
 */
fun AlloyLanguageServerApi.executeAlloyCommand(arguments: List<Any?>): CompletableFuture<Void?> =
    ExecuteAlloyCommand(JsonArray().apply { arguments.forEach { add(it.toJsonElement()) } })

/** Aborts the running analysis; the server then sends `RunCompleted "Stopped"`. */
fun AlloyLanguageServerApi.stopExecution(): CompletableFuture<Void?> = StopExecution(JsonObject())

/** Opens an `XML: ` instance link in the visualizer, or publishes the diagnostics of a `CORE: ` link. */
fun AlloyLanguageServerApi.openModel(link: String): CompletableFuture<Void?> = OpenModel(JsonPrimitive(link))

private fun Any?.toJsonElement(): JsonElement = when (this) {
    is JsonElement -> this
    is Number -> JsonPrimitive(this)
    is String -> JsonPrimitive(this)
    else -> throw IllegalArgumentException("Unexpected command argument: $this")
}
