package com.serranofp.alloy

import com.intellij.testFramework.fixtures.BasePlatformTestCase

class AlloyFileTypeTest : BasePlatformTestCase() {
    fun testAlsFileIsAlloy() {
        val file = myFixture.configureByText("model.als", "sig A {}")
        assertEquals(AlloyFileType, file.virtualFile.fileType)
        assertEquals(AlloyLanguage, file.language)
        assertSame(AlloyIcons.FILE, file.virtualFile.fileType.icon)
    }

    fun testMarkdownFileIsNotAlloy() {
        val file = myFixture.configureByText("model.md", "```alloy\nsig A {}\n```")
        assertNotSame(AlloyFileType, file.virtualFile.fileType)
        assertNotSame(AlloyLanguage, file.language)
    }
}
