package io.github.dotnetsupport

import com.intellij.openapi.util.io.FileUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.dotnetsupport.frameworks.unity.UnityConsole
import io.github.dotnetsupport.frameworks.unity.UnityConsole.Event
import io.github.dotnetsupport.frameworks.unity.UnityConsole.Severity
import io.github.dotnetsupport.frameworks.unity.UnityConsoleService
import io.github.dotnetsupport.frameworks.unity.UnityEditorPackage
import java.io.File

/** UNITY_PLAN.md, step 2: the console of the Unity editor as the package writes it (`ConsoleLog.cs`) and as the window "Unity" takes it. */
class UnityConsoleTest : BasePlatformTestCase() {
    // the lines as ConsoleLog.cs writes them
    private val start = """{"kind":"start","id":"a1","pid":4242,"t":1760000000000}"""
    private val log = """{"kind":"log","t":1760000001000,"type":"Log","play":true,"text":"Spawned 3 \"enemies\"\nsecond line","stack":"UnityEngine.Debug:Log (object)\nSpawner:Update () (at Assets/Scripts/Spawner.cs:21)\n"}"""
    private val exception = """{"kind":"log","t":1760000002000,"type":"Exception","play":true,"text":"NullReferenceException: Object reference not set to an instance of an object","stack":"Player.Jump () (at Assets/Scripts/Player.cs:40)\nPlayer.Update () (at Assets/Scripts/Player.cs:30)\n"}"""
    private val compiler = """{"kind":"compiler","t":1760000003000,"type":"error","code":"CS1002","text":"; expected","file":"Assets/Scripts/Player.cs","line":27,"column":40}"""

    fun testTheLinesOfThePackage() {
        assertEquals(Event.Start("a1", 4242), UnityConsole.parse(start))
        val message = (UnityConsole.parse(log) as Event.Message).entry
        assertEquals(Severity.MESSAGE, message.severity)
        assertEquals("Spawned 3 \"enemies\"", message.summary)
        assertTrue(message.playMode)
        assertEquals(Severity.ERROR, (UnityConsole.parse(exception) as Event.Message).entry.severity)
        val error = (UnityConsole.parse(compiler) as Event.Message).entry
        assertEquals("Assets/Scripts/Player.cs(27,40): error CS1002: ; expected", error.summary)
        assertEquals(Event.CompileStarted, UnityConsole.parse("""{"kind":"compile-started","t":1}"""))
        assertEquals(Event.PlayStarted, UnityConsole.parse("""{"kind":"play-started","t":1}"""))
        assertNull(UnityConsole.parse("not json"))
        // the package writes what the window reads: the kinds of ConsoleLog.cs are the ones parsed here
        val sources = String(UnityEditorPackage.bundled("Editor/ConsoleLog.cs"))
        for (kind in listOf("start", "log", "compiler", "compile-started", "play-started")) assertTrue(kind, "\\\"kind\\\":\\\"$kind\\\"" in sources)
    }

    fun testTheReaderGoesOnAndBeginsAgainWithANewFile() {
        val file = File(FileUtil.createTempDirectory("unityConsole", null), "console.jsonl")
        val reader = UnityConsole.Reader(file)
        assertEquals(emptyList<Event>(), reader.read())
        file.writeText("$start\n$log\n${exception.take(20)}")
        assertEquals("the line not ended waits", listOf(Event.Start::class, Event.Message::class), reader.read().map { it::class })
        file.appendText(exception.drop(20) + "\n")
        assertEquals(Severity.ERROR, (reader.read().single() as Event.Message).entry.severity)
        assertEquals(emptyList<Event>(), reader.read())
        // begun again (grown too big, or another editor): from its start, even when it is longer than what was read
        file.writeText(start.replace("a1", "b2") + "\n$compiler\n$log\n$log\n$log\n")
        val again = reader.read()
        assertEquals(Event.Start("b2", 4242), again.first())
        assertEquals(5, again.size)
    }

    fun testThePlacesOfAStackAndWhereADoubleClickGoes() {
        val entry = (UnityConsole.parse(log) as Event.Message).entry
        assertEquals(listOf("Assets/Scripts/Spawner.cs" to 21), UnityConsole.places(entry.stack).map { it.path to it.line })
        val place = UnityConsole.places(entry.stack).single()
        assertEquals("Assets/Scripts/Spawner.cs:21", entry.stack.substring(place.start, place.end))
        val root = FileUtil.createTempDirectory("unityTarget", null)
        val player = File(root, "Assets/Scripts/Player.cs").apply { parentFile.mkdirs(); writeText("") }
        assertEquals(40 to player, UnityConsole.target((UnityConsole.parse(exception) as Event.Message).entry, root)?.let { it.first.line to it.second })
        assertEquals(27 to player, UnityConsole.target((UnityConsole.parse(compiler) as Event.Message).entry, root)?.let { it.first.line to it.second })
        // frames of the engine have no place in the project; a file of another project (a copy whose Library names the original) neither
        val other = File(FileUtil.createTempDirectory("unityOriginal", null), "Assets/Scripts/Player.cs").apply { parentFile.mkdirs(); writeText("") }
        assertNull(UnityConsole.target(entry.copy(stack = "UnityEngine.Debug:Log (object)\nFoo:Bar () (at /home/bokken/build/output/Runtime/Foo.cs:5)\n" +
            "Player:Update () (at ${other.path.replace('\\', '/')}:7)\n"), root))
        assertEquals(30, UnityConsole.target(entry.copy(stack = "Player:Update () (at ${other.path.replace('\\', '/')}:7)\nPlayer:Run () (at Assets/Scripts/Player.cs:30)\n"), root)?.first?.line)
    }

    fun testAPathOfAPackageIsInThePackageCache() {
        val root = FileUtil.createTempDirectory("unityResolve", null)
        val script = File(root, "Assets/Scripts/Player.cs").apply { parentFile.mkdirs(); writeText("") }
        val cached = File(root, "Library/PackageCache/com.unity.inputsystem@7a4e1a2a8194/InputSystem/Actions/InputAction.cs").apply { parentFile.mkdirs(); writeText("") }
        val embedded = File(root, "Packages/io.github.dotnetsupport.unity/Editor/IdeConnection.cs").apply { parentFile.mkdirs(); writeText("") }
        assertEquals(script, UnityConsole.resolve(root, "Assets/Scripts/Player.cs"))
        assertEquals(cached, UnityConsole.resolve(root, "Packages/com.unity.inputsystem/InputSystem/Actions/InputAction.cs"))
        assertEquals(embedded, UnityConsole.resolve(root, "Packages/io.github.dotnetsupport.unity/Editor/IdeConnection.cs"))
        assertNull(UnityConsole.resolve(root, "Assets/Missing.cs"))
    }

    fun testCollapseAndTheFilters() {
        val entry = (UnityConsole.parse(log) as Event.Message).entry
        val error = (UnityConsole.parse(exception) as Event.Message).entry
        val entries = listOf(entry, error, entry.copy(time = 5), entry)
        assertEquals(listOf(1, 1, 1, 1), UnityConsole.rows(entries, false) { true }.map { it.count })
        assertEquals(listOf(3, 1), UnityConsole.rows(entries, true) { true }.map { it.count })
        assertEquals(listOf(error), UnityConsole.rows(entries, true) { it.severity == Severity.ERROR }.map { it.entry })
    }

    fun testTheServiceClearsAsTheConsoleDoes() {
        val service = UnityConsoleService(project)
        val clearOnPlay = service.clearOnPlay
        try {
            fun events(vararg lines: String) = lines.mapNotNull(UnityConsole::parse)
            service.clearOnPlay = true
            assertTrue(service.apply(events(start, log, compiler, exception)))
            assertEquals(3, service.entries().size)
            // a compilation clears the messages of the last one only; Play mode the rest; Clear keeps the errors of the compiler
            service.apply(events("""{"kind":"compile-started","t":1}"""))
            assertEquals(2, service.entries().size)
            service.apply(events(compiler, """{"kind":"play-started","t":1}"""))
            assertEquals(listOf(true), service.entries().map { it.compiler })
            service.apply(events(log))
            service.clear()
            assertEquals(1, service.count(Severity.ERROR))
            // the file begun again by the same editor keeps the messages; by another editor it does not
            service.apply(events(start.replace("a1", "c3"), log))
            assertEquals(2, service.entries().size)
            service.apply(events(start.replace("4242", "5353")))
            assertEquals(emptyList<Any>(), service.entries())
            service.clearOnPlay = false
            service.apply(events(log, """{"kind":"play-started","t":1}"""))
            assertEquals(1, service.entries().size)
        } finally {
            service.clearOnPlay = clearOnPlay
            com.intellij.openapi.util.Disposer.dispose(service)
        }
    }
}
