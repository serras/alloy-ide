package com.serranofp.alloy.run

import com.intellij.execution.actions.ConfigurationContext
import com.intellij.execution.lineMarker.RunLineMarkerContributor
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.serranofp.alloy.AlloyLanguage
import com.serranofp.alloy.psi.AlloyCommandDecl

class AlloyRunMarkersAndProducerTest : BasePlatformTestCase() {
    private val model = """
        sig Node { next: lone Node }
        pred show { some next }
        run show for 3
        fact { no n: Node | n in n.^next }
        check noCycles { no iden & ^next } for 5
        run { some Node } for 2
        run first => check second
    """.trimIndent()

    fun testMarkersOnlyOnCommandKeywords() {
        myFixture.configureByText("model.als", model)
        val contributor = RunLineMarkerContributor.EXTENSION.allForLanguage(AlloyLanguage)
            .filterIsInstance<AlloyRunLineMarkerContributor>().single()
        val marked = PsiTreeUtil.collectElements(myFixture.file) { it.firstChild == null }
            .filter { contributor.getInfo(it) != null }
        val document = myFixture.editor.document
        assertEquals(listOf(2, 4, 5, 6), marked.map { document.getLineNumber(it.textOffset) })
        assertTrue(marked.all { it.text == "run" || it.text == "check" })
        assertEquals("run", marked.last().text)
    }

    fun testProducerCreatesConfigurationForLabelledCommand() {
        myFixture.configureByText("model.als", model)
        val configuration = produce(commandAt("check noCycles"))
        assertEquals(myFixture.file.virtualFile.path, configuration.filePath)
        assertEquals(AlloyCommandRef("noCycles", 1), configuration.command)
        assertEquals("check noCycles", configuration.name)
    }

    fun testProducerCreatesConfigurationForUnlabelledCommandByPosition() {
        myFixture.configureByText("model.als", model)
        val configuration = produce(commandAt("run { some Node }"))
        assertEquals(AlloyCommandRef(null, 2), configuration.command)
        assertEquals("run command 3", configuration.name)
    }

    fun testProducerRecognizesItsConfiguration() {
        myFixture.configureByText("model.als", model)
        val producer = AlloyCommandRunConfigurationProducer()
        val show = commandAt("run show")
        val configuration = produce(show)
        assertTrue(producer.isConfigurationFromContext(configuration, ConfigurationContext(show.firstChild)))
        assertFalse(producer.isConfigurationFromContext(configuration, ConfigurationContext(commandAt("check noCycles").firstChild)))
    }

    fun testNoConfigurationOutsideCommands() {
        myFixture.configureByText("model.als", model)
        val inPred = PsiTreeUtil.findElementOfClassAtOffset(myFixture.file, model.indexOf("some next"), PsiElement::class.java, false)!!
        assertNull(AlloyCommandRunConfigurationProducer().createConfigurationFromContext(ConfigurationContext(inPred)))
    }

    private fun commandAt(prefix: String): AlloyCommandDecl =
        PsiTreeUtil.getParentOfType(myFixture.file.findElementAt(model.indexOf(prefix)), AlloyCommandDecl::class.java)!!

    private fun produce(command: AlloyCommandDecl): AlloyCommandRunConfiguration =
        AlloyCommandRunConfigurationProducer().createConfigurationFromContext(ConfigurationContext(command.firstChild))!!
            .configuration as AlloyCommandRunConfiguration
}
