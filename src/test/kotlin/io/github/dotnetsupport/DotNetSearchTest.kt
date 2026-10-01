package io.github.dotnetsupport

import com.intellij.openapi.util.io.FileUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.dotnetsupport.cli.DotNetSearch
import java.io.File

/** Finding a `dotnet` host in folders the user points at — without a real installation. */
class DotNetSearchTest : BasePlatformTestCase() {
    fun testTheEnvironmentVariableIsSplitLikeAPath() {
        assertEquals(listOf("/opt/dotnet", "/usr/share/dotnet-sdk-8.8.403"),
            DotNetSearch.envSearchPaths("/opt/dotnet:/usr/share/dotnet-sdk-8.8.403: ", separator = ':'))
        assertEquals(listOf("C:\\dotnet", "D:\\sdk"), DotNetSearch.envSearchPaths("C:\\dotnet;D:\\sdk", separator = ';'))
        assertEquals(emptyList<String>(), DotNetSearch.envSearchPaths(null))
        assertEquals(emptyList<String>(), DotNetSearch.envSearchPaths("   "))
    }

    fun testTheHostIsFoundInAFolderAndItsDotnetSubfolders() {
        val root = FileUtil.createTempDirectory("dotnet-search", null, true)
        try {
            fun host(path: String) = File(root, path).apply { parentFile.mkdirs(); writeText("") }
            val old = host("dotnet-sdk-8.8.403/dotnet")
            val new = host("dotnet-sdk-10.0.100/dotnet")
            host("unrelated/dotnet") // not a dotnet* folder: ignored when scanning the parent

            // pointing at the parent finds the newest versioned subfolder
            assertEquals(new, DotNetSearch.findIn(listOf(root), "dotnet"))
            // pointing straight at a versioned folder finds its host
            assertEquals(old, DotNetSearch.findIn(listOf(File(root, "dotnet-sdk-8.8.403")), "dotnet"))
            // a host directly in a given folder counts too
            val direct = host("plain/dotnet")
            assertEquals(direct, DotNetSearch.findIn(listOf(File(root, "plain")), "dotnet"))
            // the executable name must match, and nothing is found where there is no host
            assertNull(DotNetSearch.findIn(listOf(root), "dotnet.exe"))
            assertNull(DotNetSearch.findIn(listOf(File(root, "nope")), "dotnet"))
        } finally {
            root.deleteRecursively()
        }
    }

    fun testTheExecutableNameIsOsSpecific() {
        assertEquals("dotnet.exe", DotNetSearch.executableName(windows = true))
        assertEquals("dotnet", DotNetSearch.executableName(windows = false))
    }
}
