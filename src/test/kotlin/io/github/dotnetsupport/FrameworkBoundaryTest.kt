package io.github.dotnetsupport

import junit.framework.TestCase
import java.io.File

/**
 * The border between the core of C# and the framework modules (RAZOR_PLAN.md, "Модульность"): the core never refers to a framework, the
 * semantics names none in its code, and every package under `frameworks/` is a content module of its own.
 */
class FrameworkBoundaryTest : TestCase() {
    private val main = File("src/main/kotlin/io/github/dotnetsupport")
    private val frameworks = File(main, "frameworks")

    fun testTheCoreDoesNotReferToTheFrameworks() {
        val offenders = (main.walkTopDown() + File(".").listFiles { f -> f.isDirectory && f.name.startsWith("csharp-psi-") }.orEmpty().asSequence().flatMap { File(it, "src/main").walkTopDown() })
            .filter { it.isFile && it.extension == "kt" && !it.startsWith(frameworks) }
            .filter { "io.github.dotnetsupport.frameworks" in it.readText() }
            .map { it.path }.toList()
        assertEquals(emptyList<String>(), offenders)
    }

    fun testTheSemanticsNamesNoFramework() {
        val names = Regex("""(?i)razor|blazor|cshtml|xaml|avalonia|windowsdesktop|\bwpf\b|maui|\buno\b|grpc|protobuf|"proto"|\bunity""")
        val found = File(main, "lang/semantic").walkTopDown().filter { it.isFile && it.extension == "kt" }.flatMap { file ->
            file.readLines().mapIndexedNotNull { i, line ->
                val code = line.trimStart().takeUnless { it.startsWith("//") || it.startsWith("*") || it.startsWith("/*") }?.substringBefore(" // ") ?: return@mapIndexedNotNull null
                names.find(code)?.let { "${file.name}:${i + 1}: ${it.value}" }
            }
        }.toList()
        assertEquals(emptyList<String>(), found)
    }

    fun testEveryFrameworkIsAContentModule() {
        val plugin = File("src/main/resources/META-INF/plugin.xml").readText()
        val packages = frameworks.listFiles { f -> f.isDirectory }.orEmpty().map { it.name }.sorted()
        assertTrue(packages.isNotEmpty())
        for (name in packages) {
            val module = "io.github.dotnetsupport.frameworks.$name"
            val descriptor = File("src/main/resources/$module.xml")
            assertTrue("$descriptor", descriptor.isFile)
            assertTrue("package of $descriptor", "package=\"$module\"" in descriptor.readText())
            assertTrue("<module name=\"$module\"/> in plugin.xml", "<module name=\"$module\"/>" in plugin)
            File(frameworks, name).walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { file ->
                val declared = Regex("""(?m)^package\s+([\w.]+)""").find(file.readText())?.groupValues?.get(1)
                assertTrue("$file: package $declared", declared == module || declared?.startsWith("$module.") == true)
            }
        }
    }
}
