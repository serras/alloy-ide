package com.serranofp.alloy.lsp

import com.google.gson.JsonParser
import com.intellij.platform.lsp.api.LspServerNotificationsHandler
import junit.framework.TestCase
import org.eclipse.lsp4j.jsonrpc.json.MessageJsonHandler
import org.eclipse.lsp4j.jsonrpc.messages.NotificationMessage
import org.eclipse.lsp4j.jsonrpc.services.GenericEndpoint
import org.eclipse.lsp4j.jsonrpc.services.ServiceEndpoints
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.nio.file.Path

/** Notifications captured from the 6.2.0 server, decoded the way the IDE's lsp4j decodes them. */
class AlloyLsp4jClientTest : TestCase() {
    private val listed = mutableListOf<CommandsListResult>()
    private val output = mutableListOf<AlloyExecutionMessage>()

    private val client = AlloyLsp4jClient(unusedHandler()) {
        object : AlloyServerNotifications {
            override fun commandsListed(result: CommandsListResult) {
                listed += result
            }

            override fun executionOutput(message: AlloyExecutionMessage) {
                output += message
            }
        }
    }

    fun testCommandsListResult() {
        deliver("commandsListResult.json")
        val commands = listed.single().commands
        assertEquals(listOf("Run show for 3", "Check noCycles for 3", "Run big for 30 but 7 int"), commands.map { it.title })
        val check = commands[1].command!!
        assertEquals("ExecuteAlloyCommand", check.command)
        assertEquals(4, check.arguments.size)
        assertEquals("file:///tmp/lspprobe/model.als", check.arguments[0].asString())
        assertEquals(listOf(1, 3, 0), check.arguments.drop(1).map { it.asInt() })
    }

    fun testEmptyCommandsListOnParseError() {
        deliver("commandsListResult-parseError.json")
        assertTrue(listed.single().commands.isEmpty())
    }

    fun testShowExecutionOutput() {
        deliver("showExecutionOutput.json")
        assertEquals(21, output.size)

        val executing = output[1]
        assertEquals(AlloyMessageType.RunInProgress, executing.type)
        assertEquals("Executing \"Run show for 3\"\n", executing.message)
        assertTrue(executing.bold)
        assertTrue(executing.lineBreak)
        assertFalse(executing.replaceLast)
        assertNull(executing.link)

        val instance = output[5]
        assertEquals(AlloyMessageType.RunResult, instance.type)
        assertEquals("   Instance found. Predicate is consistent. ", instance.message)
        assertTrue(instance.link!!.startsWith("XML: "))

        val counterexample = output.single { it.message?.startsWith("   Counterexample found.") == true }
        assertTrue(counterexample.link!!.startsWith("XML: "))

        assertEquals(3, output.count { it.type == AlloyMessageType.RunCompleted })
        val stopped = output.last()
        assertEquals(AlloyMessageType.RunCompleted, stopped.type)
        assertEquals("Stopped", stopped.message)
        assertTrue(stopped.bold)
    }

    fun testUnknownMessageTypeIsNotMisread() {
        assertNull(AlloyExecutionMessage(messageType = 99).type)
        assertEquals(AlloyMessageType.Warning, AlloyExecutionMessage(messageType = 4).type)
    }

    private fun deliver(file: String) {
        val handler = MessageJsonHandler(ServiceEndpoints.getSupportedMethods(AlloyLsp4jClient::class.java))
        val endpoint = GenericEndpoint(client)
        val json = JsonParser.parseString(Files.readString(Path.of("src/test/testData/lsp", file)))
        val messages = if (json.isJsonArray) json.asJsonArray.toList() else listOf(json)
        for (raw in messages) {
            val message = handler.parseMessage(raw.toString()) as NotificationMessage
            endpoint.notify(message.method, message.params)
        }
    }

    private fun Any.asString(): String = (this as? com.google.gson.JsonPrimitive)?.asString ?: toString()

    private fun Any.asInt(): Int = (this as? com.google.gson.JsonPrimitive)?.asInt ?: (this as Number).toInt()

    private fun unusedHandler(): LspServerNotificationsHandler =
        Proxy.newProxyInstance(javaClass.classLoader, arrayOf(LspServerNotificationsHandler::class.java)) { _, method, _ ->
            error("Unexpected call to ${method.name}")
        } as LspServerNotificationsHandler
}
