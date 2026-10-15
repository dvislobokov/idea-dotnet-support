package io.github.dotnetsupport

import io.github.dotnetsupport.index.ProjectAssemblies
import io.github.dotnetsupport.msbuild.ProjectLayout
import junit.framework.TestCase
import java.io.File

/**
 * `UseArtifactsOutput` (0.1.199): the intermediate and output folders of a project are `obj/` and `bin/` beside it, or `artifacts/obj/<name>/`
 * and `artifacts/bin/<name>/` at the root of the repository with the pivot `debug` / `debug_net8.0` — eShop's Catalog.API had no assets to
 * the IDE, so its references were incomplete and a missing name was no error.
 */
class ProjectLayoutTest : TestCase() {
    private lateinit var root: File

    override fun setUp() {
        super.setUp()
        root = com.intellij.openapi.util.io.FileUtil.createTempDirectory("layout", null)
    }

    override fun tearDown() {
        try {
            root.deleteRecursively()
        } finally {
            super.tearDown()
        }
    }

    private fun file(path: String, text: String = ""): File = File(root, path).apply { parentFile.mkdirs(); writeText(text) }

    fun testClassicLayout() {
        val project = file("src/Lib/Lib.csproj", "<Project Sdk=\"Microsoft.NET.Sdk\"/>")
        file("src/Lib/obj/project.assets.json", "{}")
        file("src/Lib/obj/Debug/net9.0/ref/Lib.dll")
        file("src/Lib/bin/Debug/net9.0/Lib.dll")
        assertEquals(File(root, "src/Lib/obj"), ProjectLayout.intermediate(project))
        assertEquals(File(root, "src/Lib/bin"), ProjectLayout.output(project))
        assertEquals(File(root, "src/Lib/obj/project.assets.json"), ProjectLayout.assets(project))
        assertEquals(File(root, "src/Lib/obj/Debug/net9.0/ref/Lib.dll"), ProjectAssemblies.builtFor(project, "Lib.dll", "net9.0"))
    }

    fun testArtifactsLayout() {
        val project = file("src/Catalog.API/Catalog.API.csproj", "<Project Sdk=\"Microsoft.NET.Sdk.Web\"/>")
        file("Directory.Build.props", "<Project><PropertyGroup><UseArtifactsOutput>true</UseArtifactsOutput></PropertyGroup></Project>")
        file("artifacts/obj/Catalog.API/project.assets.json", "{}")
        file("artifacts/obj/Catalog.API/debug/ref/Catalog.API.dll")
        file("artifacts/bin/Catalog.API/debug/Catalog.API.dll")
        assertEquals(File(root, "artifacts/obj/Catalog.API"), ProjectLayout.intermediate(project))
        assertEquals(File(root, "artifacts/bin/Catalog.API"), ProjectLayout.output(project))
        assertEquals(File(root, "artifacts/obj/Catalog.API/project.assets.json"), ProjectLayout.assets(project))
        assertEquals(File(root, "artifacts/obj/Catalog.API/debug/ref/Catalog.API.dll"), ProjectAssemblies.builtFor(project, "Catalog.API.dll", "net10.0"))
        // several frameworks: the pivot carries the framework
        file("artifacts/obj/Catalog.API/debug_net8.0/ref/Catalog.API.dll")
        assertTrue(ProjectLayout.pivotMatches("debug_net8.0", "Debug", "net8.0"))
        assertFalse(ProjectLayout.pivotMatches("debug_net8.0", "Debug", "net10.0"))
        assertTrue(ProjectLayout.pivotMatches("release", "Release", "net10.0"))
        assertFalse(ProjectLayout.pivotMatches("release", "Debug", null))
        assertEquals("debug/Pages/Index.g.cs", ProjectLayout.underArtifactsIntermediate("C:/repo/artifacts/obj/Web/debug/Pages/Index.g.cs"))
        assertNull(ProjectLayout.underArtifactsIntermediate("C:/repo/src/Web/obj/Debug/net10.0/Pages/Index.g.cs"))
    }

    fun testNotRestored() {
        val project = file("src/Lib/Lib.csproj", "<Project Sdk=\"Microsoft.NET.Sdk\"/>")
        assertNull(ProjectLayout.assets(project))
        assertNull(ProjectLayout.intermediate(project))
        assertNull(ProjectAssemblies.builtFor(project, "Lib.dll", "net9.0"))
    }
}
