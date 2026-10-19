package com.serranofp.alloy.editor

import com.intellij.testFramework.fixtures.BasePlatformTestCase

class AlloyFoldingTest : BasePlatformTestCase() {
    override fun getTestDataPath(): String = "src/test/testData/folding"

    fun testFolding() = myFixture.testFolding("$testDataPath/Folding.als")
}
