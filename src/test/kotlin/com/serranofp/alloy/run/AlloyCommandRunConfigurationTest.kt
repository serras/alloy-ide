package com.serranofp.alloy.run

import com.intellij.execution.configurations.RuntimeConfigurationError
import com.intellij.openapi.util.io.FileUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.jdom.Element

class AlloyCommandRunConfigurationTest : BasePlatformTestCase() {
    private fun newConfiguration(): AlloyCommandRunConfiguration =
        getAlloyCommandConfigurationType().factory.createTemplateConfiguration(project) as AlloyCommandRunConfiguration

    fun testSurvivesWriteAndReadExternal() {
        val original = newConfiguration().apply {
            name = "check noCycles"
            filePath = "/models/graph.als"
            command = AlloyCommandRef("noCycles", 1)
        }
        val element = Element("configuration")
        original.writeExternal(element)

        val restored = newConfiguration()
        restored.readExternal(element)
        assertEquals("/models/graph.als", restored.filePath)
        assertEquals(AlloyCommandRef("noCycles", 1), restored.command)
    }

    fun testUnlabelledCommandSurvivesByPosition() {
        val original = newConfiguration().apply {
            filePath = "/models/graph.als"
            command = AlloyCommandRef(null, 2)
        }
        val element = Element("configuration")
        original.writeExternal(element)
        val restored = newConfiguration().apply { readExternal(element) }
        assertEquals(AlloyCommandRef(null, 2), restored.command)
    }

    fun testMissingFileFailsCheck() {
        val configuration = newConfiguration().apply {
            filePath = FileUtil.getTempDirectory() + "/does-not-exist.als"
            command = AlloyCommandRef("show", 0)
        }
        val error = runCatching { configuration.checkConfiguration() }.exceptionOrNull()
        assertInstanceOf(error, RuntimeConfigurationError::class.java)
        assertTrue(error!!.localizedMessage, error.localizedMessage.contains("does-not-exist.als"))
    }

    fun testUnsetFileFailsCheck() {
        assertThrows(RuntimeConfigurationError::class.java) { newConfiguration().checkConfiguration() }
    }

    fun testExistingFilePassesCheck() {
        val file = FileUtil.createTempFile("model", ".als", true)
        newConfiguration().apply {
            filePath = file.path
            command = AlloyCommandRef("show", 0)
        }.checkConfiguration()
    }
}
