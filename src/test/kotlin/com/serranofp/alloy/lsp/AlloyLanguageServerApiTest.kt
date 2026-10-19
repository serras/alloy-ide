package com.serranofp.alloy.lsp

import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import junit.framework.TestCase
import org.eclipse.lsp4j.jsonrpc.Endpoint
import org.eclipse.lsp4j.jsonrpc.json.MessageJsonHandler
import org.eclipse.lsp4j.jsonrpc.messages.RequestMessage
import org.eclipse.lsp4j.jsonrpc.services.ServiceEndpoints
import java.util.concurrent.CompletableFuture

/** The requests must match the parameter shapes of the 6.2.0 server (a bare string or a bare array). */
class AlloyLanguageServerApiTest : TestCase() {
    private val sent = mutableListOf<RequestMessage>()

    private val server: AlloyLanguageServerApi = ServiceEndpoints.toServiceObject(
        object : Endpoint {
            override fun request(method: String, parameter: Any?): CompletableFuture<*> {
                sent += RequestMessage().apply { id = "1"; this.method = method; params = parameter }
                return CompletableFuture.completedFuture(null)
            }

            override fun notify(method: String, parameter: Any?) = error("No notifications expected")
        },
        AlloyLanguageServerApi::class.java,
    )

    fun testListAlloyCommands() {
        server.listAlloyCommands("file:///m.als")
        assertWire("ListAlloyCommands", "\"file:///m.als\"")
    }

    fun testExecuteAlloyCommand() {
        server.executeAlloyCommand(listOf("file:///m.als", 1, 3, 0))
        assertWire("ExecuteAlloyCommand", "[\"file:///m.als\",1,3,0]")
    }

    fun testStopExecution() {
        server.stopExecution()
        assertWire("StopExecution", "{}")
    }

    fun testOpenModel() {
        server.openModel("XML: /tmp/0.cnf.xml")
        assertWire("OpenModel", "\"XML: /tmp/0.cnf.xml\"")
    }

    /** Arguments as decoded from `alloy/commandsListResult` are Gson elements, and pass through unchanged. */
    fun testExecuteAlloyCommandWithDecodedArguments() {
        server.executeAlloyCommand(listOf(JsonPrimitive("file:///m.als"), JsonPrimitive(1), JsonPrimitive(3), JsonPrimitive(0)))
        assertWire("ExecuteAlloyCommand", "[\"file:///m.als\",1,3,0]")
    }

    private fun assertWire(method: String, params: String) {
        val handler = MessageJsonHandler(ServiceEndpoints.getSupportedMethods(AlloyLanguageServerApi::class.java))
        val json = JsonParser.parseString(handler.serialize(sent.single())).asJsonObject
        assertEquals(method, json["method"].asString)
        assertEquals(JsonParser.parseString(params), json["params"])
    }
}
