package io.github.dotnetsupport

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.dotnetsupport.lang.CSharpFileType
import io.github.dotnetsupport.msbuild.MsBuildFileType
import io.github.dotnetsupport.sdk.DotNetFileTypeCheck
import io.github.dotnetsupport.sdk.DotNetFileTypeNotificationProvider

/** A user association that shadows ours (a .cs opened as text before the install): seen, bannered, claimed back. */
class DotNetFileTypeCheckTest : BasePlatformTestCase() {
    fun testAHijackedAssociationIsSeenBanneredAndClaimedBack() {
        val ftm = FileTypeManager.getInstance()
        assertEquals("nothing is off to begin with", emptyList<String>(), DotNetFileTypeCheck.wrongClaims().map { it.what })
        val cs = myFixture.addFileToProject("FileTypeCheck.cs", "class A {}\n").virtualFile
        val csproj = myFixture.addFileToProject("FileTypeCheck.csproj", "<Project Sdk=\"Microsoft.NET.Sdk\"/>\n").virtualFile
        assertFalse(DotNetFileTypeCheck.isMistyped(cs))
        assertNull(DotNetFileTypeNotificationProvider().collectNotificationData(project, cs))

        ApplicationManager.getApplication().runWriteAction {
            ftm.associateExtension(PlainTextFileType.INSTANCE, "cs")
            ftm.associateExtension(PlainTextFileType.INSTANCE, "csproj")
        }
        try {
            assertEquals(listOf("*.cs", "*.csproj"), DotNetFileTypeCheck.wrongClaims().map { it.what })
            assertTrue(DotNetFileTypeCheck.isMistyped(cs))
            assertTrue(DotNetFileTypeCheck.isMistyped(csproj))
            assertNotNull("the banner above the file", DotNetFileTypeNotificationProvider().collectNotificationData(project, cs))

            DotNetFileTypeCheck.fix(project)
            assertEquals(emptyList<String>(), DotNetFileTypeCheck.wrongClaims().map { it.what })
            assertEquals(CSharpFileType, cs.fileType)
            assertEquals(MsBuildFileType, csproj.fileType)
            assertFalse(DotNetFileTypeCheck.isMistyped(cs))
        } finally {
            // the light project and the file type manager are shared: leave them as found
            ApplicationManager.getApplication().runWriteAction {
                ftm.associateExtension(CSharpFileType, "cs")
                ftm.associateExtension(MsBuildFileType, "csproj")
            }
        }
    }
}
