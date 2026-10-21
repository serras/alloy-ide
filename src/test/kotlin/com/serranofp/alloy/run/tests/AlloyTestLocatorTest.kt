package com.serranofp.alloy.run.tests

import com.intellij.psi.search.GlobalSearchScope
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.serranofp.alloy.psi.AlloyCommandDecl
import com.serranofp.alloy.run.AlloyCommandRef

class AlloyTestLocatorTest : BasePlatformTestCase() {
    private val model = "sig Node {}\npred show {}\nassert safety {}\nrun show for 3\ncheck safety for 3\nrun { some Node } for 2\n"

    private fun locate(ref: AlloyCommandRef): AlloyCommandDecl? {
        val url = AlloyTestLocator.url(myFixture.file.virtualFile, ref)
        val protocol = url.substringBefore("://")
        val locations = AlloyTestLocator.getLocation(protocol, url.substringAfter("://"), project, GlobalSearchScope.allScope(project))
        return locations.singleOrNull()?.psiElement as AlloyCommandDecl?
    }

    override fun setUp() {
        super.setUp()
        myFixture.configureByText("a.als", model)
    }

    fun testByLabel() {
        assertEquals("check safety for 3", locate(AlloyCommandRef("safety", 1))!!.text)
        // The label wins over a stale position
        assertEquals("run show for 3", locate(AlloyCommandRef("show", 2))!!.text)
    }

    fun testUnlabelledByIndex() {
        assertEquals("run { some Node } for 2", locate(AlloyCommandRef(null, 2))!!.text)
    }

    fun testMissingCommand() {
        assertNull(locate(AlloyCommandRef("removed", 0)))
        assertEmpty(AlloyTestLocator.getLocation("file", "/a.als", project, GlobalSearchScope.allScope(project)))
    }

    fun testUrlRoundTrip() {
        val url = AlloyTestLocator.url(myFixture.file.virtualFile, AlloyCommandRef(null, 2))
        assertEquals("alloy://${myFixture.file.virtualFile.url}#2:", url)
        assertEquals(myFixture.file.virtualFile.url to AlloyCommandRef(null, 2), AlloyTestLocator.parse(url.removePrefix("alloy://")))
    }

    fun testConsolePropertiesUseTheLocator() {
        val configuration = AlloyCommandsConfigurationType.getInstance().factory.createTemplateConfiguration(project)
        val properties = AlloyCommandsConsoleProperties(configuration, com.intellij.execution.executors.DefaultRunExecutor.getRunExecutorInstance())
        assertSame(AlloyTestLocator, properties.testLocator)
    }
}
