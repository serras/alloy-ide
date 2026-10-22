package com.serranofp.alloy.run.tests

import com.intellij.execution.RunManager
import com.intellij.execution.actions.ConfigurationContext
import com.intellij.execution.actions.RunConfigurationProducer
import com.intellij.execution.configurations.RuntimeConfigurationError
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.serranofp.alloy.run.AlloyCommandRef
import org.jdom.Element

class AlloyCommandsConfigurationTest : BasePlatformTestCase() {
    private val producer get() = RunConfigurationProducer.getInstance(AlloyCommandsConfigurationProducer::class.java)

    private fun context(text: String = "run show", name: String = "a.als"): ConfigurationContext {
        myFixture.configureByText(name, "pred show {}\n$text\n")
        return ConfigurationContext(myFixture.file)
    }

    fun testProducerCreatesAConfigurationForTheFile() {
        val created = producer.createConfigurationFromContext(context())!!
        val configuration = created.configuration as AlloyCommandsRunConfiguration
        assertEquals("All commands in a.als", configuration.name)
        assertEquals(myFixture.file.virtualFile.url, configuration.fileUrl)
        assertEquals(myFixture.file.virtualFile, configuration.file())
        assertEmpty(configuration.commands)
        assertSame(myFixture.file, created.sourceElement)
        configuration.checkConfiguration()
    }

    fun testProducerFromAnElementInTheEditor() {
        myFixture.configureByText("a.als", "pred show {}\nrun show\n")
        val created = producer.createConfigurationFromContext(ConfigurationContext(myFixture.file.findElementAt(5)!!))!!
        assertEquals("All commands in a.als", created.configuration.name)
    }

    fun testProducerIgnoresOtherFiles() {
        myFixture.configureByText("notes.txt", "run show")
        assertNull(producer.createConfigurationFromContext(ConfigurationContext(myFixture.file)))
    }

    fun testIsConfigurationFromContext() {
        val context = context()
        val configuration = producer.createConfigurationFromContext(context)!!.configuration as AlloyCommandsRunConfiguration
        assertTrue(producer.isConfigurationFromContext(configuration, context))

        val other = configuration.clone() as AlloyCommandsRunConfiguration
        other.fileUrl = "temp:///src/b.als"
        assertFalse(producer.isConfigurationFromContext(other, context))

        // A rerun of failed commands is not the configuration of the whole file
        val restricted = configuration.clone() as AlloyCommandsRunConfiguration
        restricted.commands = listOf(AlloyCommandRef("show", 0))
        assertFalse(producer.isConfigurationFromContext(restricted, context))
    }

    fun testRoundTripThroughExternalization() {
        val factory = AlloyCommandsConfigurationType.getInstance().factory
        val configuration = AlloyCommandsRunConfiguration(project, factory, "All commands in a.als").apply {
            fileUrl = "file:///models/a.als"
            commands = listOf(AlloyCommandRef("show", 0), AlloyCommandRef(null, 2), AlloyCommandRef("this/safety", 3), AlloyCommandRef("show", 4, occurrence = 1))
        }
        val element = Element("configuration")
        configuration.writeExternal(element)

        val read = AlloyCommandsRunConfiguration(project, factory, "")
        read.readExternal(element)
        assertEquals("file:///models/a.als", read.fileUrl)
        assertEquals(configuration.commands, read.commands)
        assertEquals(listOf(0, 0, 0, 1), read.commands.map { it.occurrence })
    }

    fun testMalformedCommandsAreSkippedWhenRead() {
        val element = Element("configuration")
        element.addContent(Element("command").setAttribute("index", "x").setAttribute("label", "show"))
        element.addContent(Element("command").setAttribute("label", "other"))
        element.addContent(Element("command").setAttribute("index", "2").setAttribute("label", "safety").setAttribute("occurrence", "?"))
        val read = AlloyCommandsRunConfiguration(project, AlloyCommandsConfigurationType.getInstance().factory, "")
        read.readExternal(element)
        assertEquals("", read.fileUrl)
        assertEquals(listOf(AlloyCommandRef("safety", 2)), read.commands)
        assertEquals(0, read.commands.single().occurrence)
    }

    private fun configurationError(configuration: AlloyCommandsRunConfiguration): String? =
        try {
            configuration.checkConfiguration()
            null
        } catch (e: RuntimeConfigurationError) {
            e.localizedMessage
        }

    fun testConfigurationOfAMissingFileIsAnError() {
        val configuration = producer.createConfigurationFromContext(context())!!.configuration as AlloyCommandsRunConfiguration
        configuration.fileUrl = "temp:///missing.als"
        assertEquals("The Alloy file is not found", configurationError(configuration))
        configuration.fileUrl = ""
        assertEquals("The Alloy file is not found", configurationError(configuration))
    }

    fun testConfigurationOfAnotherFileIsAnError() {
        val configuration = producer.createConfigurationFromContext(context())!!.configuration as AlloyCommandsRunConfiguration
        myFixture.configureByText("notes.txt", "run show")
        configuration.fileUrl = myFixture.file.virtualFile.url
        assertEquals("notes.txt is not an Alloy file", configurationError(configuration))
    }

    fun testSettingsEditorKeepsTheFile() {
        val configuration = producer.createConfigurationFromContext(context())!!.configuration as AlloyCommandsRunConfiguration
        val editor = AlloyCommandsSettingsEditor(project)
        try {
            editor.component
            editor.resetFrom(configuration)
            assertEquals(myFixture.file.virtualFile.path, editor.file.text)
            val copy = configuration.clone() as AlloyCommandsRunConfiguration
            editor.applyTo(copy)
            assertEquals(configuration.fileUrl, copy.fileUrl)
            editor.file.text = "/models/b.als"
            editor.applyTo(copy)
            assertEquals("file:///models/b.als", copy.fileUrl)
        } finally {
            editor.dispose()
        }
    }

    fun testConfigurationTypeIsRegistered() {
        val type = AlloyCommandsConfigurationType.getInstance()
        assertEquals("Alloy Commands", type.displayName)
        assertNotNull(RunManager.getInstance(project).createConfiguration("x", type.factory))
    }
}
