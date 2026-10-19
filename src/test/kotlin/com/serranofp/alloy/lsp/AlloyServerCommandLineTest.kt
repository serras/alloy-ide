package com.serranofp.alloy.lsp

import com.intellij.openapi.util.SystemInfo
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.platform.lsp.api.customization.LspCodeLensDisabled
import com.intellij.platform.lsp.api.customization.LspCompletionDisabled
import com.intellij.platform.lsp.api.customization.LspDocumentLinkDisabled
import com.intellij.platform.lsp.api.customization.LspFormattingDisabled
import com.intellij.platform.lsp.api.customization.LspCustomization
import java.nio.file.Path

class AlloyServerCommandLineTest : BasePlatformTestCase() {
    private val javaHome = Path.of("/ide/jbr")
    private val serverLib = Path.of("/plugins/alloy-ide/lib/server")

    fun testServeCommandLine() {
        val commandLine = AlloyServerCommandLine.create(
            javaHome, serverLib, mapOf("SkolemDepth3" to "2", "NoOverflow" to "n"), isMac = false,
        )
        assertEquals(javaHome.resolve("bin").resolve(if (SystemInfo.isWindows) "java.exe" else "java").toString(), commandLine.exePath)
        assertOrderedEquals(
            commandLine.parametersList.list,
            "-cp", serverLib.resolve("*").toString(),
            "-Djava.util.prefs.PreferencesFactory=com.serranofp.alloy.server.IsolatedPreferencesFactory",
            "-Dalloy.ide.pref.NoOverflow=n",
            "-Dalloy.ide.pref.SkolemDepth3=2",
            "com.serranofp.alloy.server.Main", "serve",
        )
    }

    fun testMacSetsApplicationNameAndNeverHeadless() {
        val parameters = AlloyServerCommandLine.create(javaHome, serverLib, emptyMap(), isMac = true).parametersList.list
        assertContainsElements(parameters, "-Dapple.awt.application.name=Alloy")
        assertTrue(parameters.none { it.startsWith("-Djava.awt.headless") })
    }

    fun testListSolversMode() {
        val parameters = AlloyServerCommandLine.create(javaHome, serverLib, emptyMap(), isMac = false, mode = "list-solvers").parametersList.list
        assertEquals("list-solvers", parameters.last())
    }

    fun testDescriptorRunsServerOnIdeRuntimeFromPluginLibServer() {
        val descriptor = AlloyLspClientDescriptor(project)
        val commandLine = descriptor.createCommandLine()
        val java = Path.of(System.getProperty("java.home"), "bin", if (SystemInfo.isWindows) "java.exe" else "java")
        assertEquals(java.toString(), commandLine.exePath)
        val classpath = commandLine.parametersList.list[commandLine.parametersList.list.indexOf("-cp") + 1]
        assertEquals(AlloyLspClientDescriptor.serverLib().resolve("*").toString(), classpath)
        assertTrue(classpath, Path.of(classpath).parent.endsWith(Path.of("alloy-ide", "lib", "server")))
        assertEquals(project.basePath, commandLine.workDirectory?.path)
    }

    fun testDescriptorHandlesAlloyFilesOnly() {
        val descriptor = AlloyLspClientDescriptor(project)
        assertTrue(descriptor.isSupportedFile(myFixture.configureByText("model.als", "").virtualFile))
        assertFalse(descriptor.isSupportedFile(myFixture.configureByText("notes.txt", "").virtualFile))
        assertEquals(AlloyLanguageServerApi::class.java, descriptor.lsp4jServerClass)
    }

    fun testCustomizationKeepsSemanticFeaturesAndDisablesTheRest() {
        val customization = AlloyLspClientDescriptor(project).lspCustomization
        assertSame(LspCodeLensDisabled, customization.codeLensCustomizer)
        assertSame(LspDocumentLinkDisabled, customization.documentLinkCustomizer)
        assertSame(LspCompletionDisabled, customization.completionCustomizer)
        assertSame(LspFormattingDisabled, customization.formattingCustomizer)

        val defaults = LspCustomization()
        assertEquals(defaults.diagnosticsCustomizer.javaClass, customization.diagnosticsCustomizer.javaClass)
        assertEquals(defaults.hoverCustomizer.javaClass, customization.hoverCustomizer.javaClass)
        assertEquals(defaults.goToDefinitionCustomizer.javaClass, customization.goToDefinitionCustomizer.javaClass)
        assertEquals(defaults.findReferencesCustomizer.javaClass, customization.findReferencesCustomizer.javaClass)
        assertEquals(defaults.renameCustomizer.javaClass, customization.renameCustomizer.javaClass)
        assertEquals(defaults.documentSymbolCustomizer.javaClass, customization.documentSymbolCustomizer.javaClass)
        assertEquals(defaults.workspaceSymbolCustomizer.javaClass, customization.workspaceSymbolCustomizer.javaClass)
    }
}
