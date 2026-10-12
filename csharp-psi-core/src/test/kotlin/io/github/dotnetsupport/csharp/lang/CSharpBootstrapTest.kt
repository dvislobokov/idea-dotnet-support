package io.github.dotnetsupport.csharp.lang

import com.intellij.testFramework.fixtures.BasePlatformTestCase

class CSharpBootstrapTest : BasePlatformTestCase() {
    fun testCsFileGetsTheLanguage() {
        val file = myFixture.configureByText("A.cs", "class A { }")
        assertInstanceOf(file, CSharpFile::class.java)
        assertEquals(CSharpLanguage, file.language)
        assertEquals("class A { }", file.text)
    }
}
