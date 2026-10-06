package io.github.dotnetsupport

import com.google.gson.JsonParser
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.psi.PsiManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.dotnetsupport.cli.HelperException
import io.github.dotnetsupport.lang.CSharpFileType
import io.github.dotnetsupport.lang.CSharpLanguage
import io.github.dotnetsupport.lsp.RoslynLanguageServerSettings
import io.github.dotnetsupport.sourcelink.DeclarationFinder
import io.github.dotnetsupport.sourcelink.DocumentChecksums
import io.github.dotnetsupport.sourcelink.HelperSourceLocator
import io.github.dotnetsupport.sourcelink.LibrarySourceBanner
import io.github.dotnetsupport.sourcelink.LibrarySourceFile
import io.github.dotnetsupport.sourcelink.LibrarySourceFileSystem
import io.github.dotnetsupport.sourcelink.LibrarySourceFiles
import io.github.dotnetsupport.sourcelink.LibrarySourceLocator
import io.github.dotnetsupport.sourcelink.LibrarySourceTabTitle
import io.github.dotnetsupport.sourcelink.LibrarySourceTarget
import io.github.dotnetsupport.sourcelink.LibrarySources
import io.github.dotnetsupport.sourcelink.SourceLinkUrls
import io.github.dotnetsupport.sourcelink.SourceLinkMap
import io.github.dotnetsupport.sourcelink.SourceLocation
import java.io.File
import java.security.MessageDigest

/**
 * Navigation to the original sources of a library (Source Link and embedded sources of its PDB, `sourcelink/`): the mapping of the Source
 * Link JSON, the hash of a document, the declaration above a sequence point, and the file in the editor — the answer of DotNetHelper is a
 * recorded one (`src/test/resources/sourcelink`, `sourceLocation` on Grpc.Net.Common 2.83.0, the path made neutral) and the network a fake.
 */
class SourceLinkTest : BasePlatformTestCase() {
    private lateinit var cache: File
    private lateinit var work: File

    override fun setUp() {
        super.setUp()
        cache = FileUtil.createTempDirectory("sources", null)
        work = FileUtil.createTempDirectory("assemblies", null)
        LibrarySourceFiles.diskRoot = cache
        LibrarySourceFiles.clearMemory()
        // no DNS in tests: the URL check has its own test
        LibrarySources.getInstance(project).apply { forget(); urlRefusal = { null } }
    }

    override fun tearDown() {
        try {
            FileEditorManager.getInstance(project).openFiles.forEach { FileEditorManager.getInstance(project).closeFile(it) }
            RoslynLanguageServerSettings.getInstance().setValue(LibrarySources.OPTION, LibrarySources.OPTION.default)
            LibrarySources.getInstance(project).apply { locator = HelperSourceLocator(); urlRefusal = { SourceLinkUrls.refusal(it) }; forget() }
            LibrarySourceFiles.clearMemory()
            LibrarySourceFiles.diskRoot = null
        } finally {
            super.tearDown()
        }
    }

    // ------------------------------------------------------------------------------------------------ pure parts

    fun testTheSourceLinkMapping() {
        val map = SourceLinkMap.parse("""{"documents": {
            "/_/*": "https://raw.githubusercontent.com/grpc/grpc-dotnet/4301104/*",
            "C:\\src\\lib\\*": "https://example.com/lib/*",
            "C:\\src\\lib\\deep\\*": "https://example.com/deep/*",
            "C:\\src\\lib\\exact.cs": "https://example.com/exact",
            "bad*key\\*": "https://example.com/*",
            "D:\\two\\*": "https://example.com/*/*"
        }}""")!!
        assertEquals("https://raw.githubusercontent.com/grpc/grpc-dotnet/4301104/src/Grpc.Net.Common/Compression/GzipCompressionProvider.cs",
            map.url("/_/src/Grpc.Net.Common/Compression/GzipCompressionProvider.cs"))
        // the case of the path does not matter, the slashes in the URL are forward, the longest prefix wins, an exact key beats the wildcard
        assertEquals("https://example.com/lib/A/B.cs", map.url("c:\\SRC\\Lib\\A\\B.cs"))
        assertEquals("https://example.com/deep/C.cs", map.url("C:\\src\\lib\\deep\\C.cs"))
        assertEquals("https://example.com/exact", map.url("C:\\src\\lib\\Exact.cs"))
        assertNull(map.url("E:\\elsewhere\\X.cs"))
        assertNull("a star in the middle of a key, or two in a URL, is not a mapping", map.url("bad*key\\X.cs"))
        assertNull(map.url("D:\\two\\X.cs"))

        assertTrue(SourceLinkMap.parse("""{"documents": {}}""")!!.isEmpty)
        assertTrue(SourceLinkMap.parse("""{}""")!!.isEmpty)
        assertNull(SourceLinkMap.parse("not json"))
        assertNull(SourceLinkMap.parse("[1]"))
    }

    /** The real download of the IDE's HTTP client, on a local server: it once failed on every URL (`redirectLimit(0)` is rejected). */
    fun testTheRealFetchReadsTheFileAndRefusesARedirect() {
        val server = com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/file.cs") { exchange ->
            val body = "class A {}\n".toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong()); exchange.responseBody.use { it.write(body) }
        }
        server.createContext("/moved.cs") { exchange ->
            exchange.responseHeaders.add("Location", "http://127.0.0.1:${server.address.port}/file.cs"); exchange.sendResponseHeaders(302, -1); exchange.close()
        }
        server.start()
        try {
            val fetch = LibrarySources(project).fetch
            val base = "http://127.0.0.1:${server.address.port}"
            assertEquals("class A {}\n", String(fetch("$base/file.cs", null)))
            val error = try { fetch("$base/moved.cs", null); null } catch (e: java.io.IOException) { e }
            assertTrue("a redirect is refused: $error", error?.message.orEmpty().contains("redirected"))
        } finally {
            server.stop(0)
        }
    }

    fun testOnlyHttpsUrlsOfPublicHostsAreDownloaded() {
        val dns = mapOf(
            "raw.githubusercontent.com" to "185.199.108.133", "intranet" to "10.0.0.5", "router" to "192.168.1.1", "me" to "127.0.0.1",
            "metadata" to "169.254.169.254", "v6local" to "fd00::1", "v6public" to "2606:50c0:8000::154",
        )
        val resolve = { host: String -> listOf(java.net.InetAddress.getByName(dns[host] ?: throw java.net.UnknownHostException(host))) }
        fun refusal(url: String) = SourceLinkUrls.refusal(url, resolve)
        assertNull(refusal("https://raw.githubusercontent.com/grpc/grpc-dotnet/abc/src/A.cs"))
        assertNull(refusal("https://v6public/a.cs"))
        assertEquals("not an HTTPS URL", refusal("http://raw.githubusercontent.com/a.cs"))
        assertEquals("not an HTTPS URL", refusal("file:///C:/a.cs"))
        for (host in listOf("intranet", "router", "me", "metadata", "v6local")) assertEquals(host, "the host is this machine or the local network", refusal("https://$host/a.cs"))
        assertEquals("the host is unknown", refusal("https://nowhere/a.cs"))
        assertEquals("not a URL", refusal("https://a b/c"))
    }

    fun testTheHashOfADocument() {
        val bytes = "class A {}\n".toByteArray()
        val sha256 = hex(MessageDigest.getInstance("SHA-256").digest(bytes))
        val sha1 = hex(MessageDigest.getInstance("SHA-1").digest(bytes))
        assertTrue(DocumentChecksums.matches(bytes, "SHA256", sha256))
        assertTrue(DocumentChecksums.matches(bytes, "sha256", sha256.uppercase()))
        assertTrue(DocumentChecksums.matches(bytes, "SHA1", sha1))
        assertFalse(DocumentChecksums.matches("class B {}\n".toByteArray(), "SHA256", sha256))
        assertFalse("an algorithm the PDB names by a GUID is not trusted", DocumentChecksums.matches(bytes, "406ea660-64cf-4c82-b6f0-42d48172a799", sha256))
        assertFalse(DocumentChecksums.isKnown("406ea660-64cf-4c82-b6f0-42d48172a799"))
    }

    fun testTheDeclarationAboveASequencePoint() {
        val text = """
            namespace Grpc.Net.Compression;

            /// <summary>Gzip, see <see cref="GzipCompressionProvider"/>.</summary>
            public sealed class GzipCompressionProvider : ICompressionProvider
            {
                private readonly CompressionLevel _level;

                public GzipCompressionProvider(CompressionLevel level)
                {
                    _level = level;
                }

                public Stream CreateCompressionStream(Stream stream,
                    CompressionLevel? compressionLevel)
                {
                    return new GZipStream(stream, compressionLevel ?? _level);
                }

                public int this[int index]
                {
                    get { return index; }
                }

                public static GzipCompressionProvider operator +(GzipCompressionProvider a, GzipCompressionProvider b) => a;
            }
        """.trimIndent()
        fun at(line: Int, column: Int, name: String?, isType: Boolean = false): String? =
            DeclarationFinder.offset(text, line, column, name, isType)?.let { text.substring(it).substringBefore('\n').take(40) }
        // the type: the first point of the constructor (its brace); the class is named above it, not the constructor and not the <see cref>
        assertEquals("GzipCompressionProvider : ICompressionPr", at(9, 1, "GzipCompressionProvider", isType = true))
        assertEquals("a type that is not declared here: the point", "    {", at(9, 1, "Other", isType = true))
        // a method whose parameters take two lines; a constructor by the name of the type; an indexer; an operator
        assertEquals("CreateCompressionStream(Stream stream,", at(16, 9, DeclarationFinder.memberName("M:Grpc.Net.Compression.GzipCompressionProvider.CreateCompressionStream(System.IO.Stream)", "GzipCompressionProvider")))
        assertEquals("GzipCompressionProvider(CompressionLevel", at(10, 9, DeclarationFinder.memberName("M:Grpc.Net.Compression.GzipCompressionProvider.#ctor(System.IO.Compression.CompressionLevel)", "GzipCompressionProvider")))
        assertEquals("this[int index]", at(21, 15, DeclarationFinder.memberName("P:Grpc.Net.Compression.GzipCompressionProvider.Item(System.Int32)", "GzipCompressionProvider")))
        assertEquals("operator +(GzipCompressionProvider a, Gz", at(24, 100, DeclarationFinder.memberName("M:Grpc.Net.Compression.GzipCompressionProvider.op_Addition(A,B)", "GzipCompressionProvider")))
        // nothing by that name above: the point itself; a line past the end: null
        assertEquals("_level = level;", at(10, 9, "Nowhere"))
        assertNull(at(99, 1, "GzipCompressionProvider"))
        assertEquals("Length", DeclarationFinder.memberName("P:Google.Protobuf.ByteString.Length", "ByteString"))
        assertEquals("Describe", DeclarationFinder.memberName("M:Fixture.Box`1.Describe``1(System.String)", "Box"))
    }

    fun testTheRecordedAnswerOfTheHelper() {
        val location = recorded()
        assertEquals("/_/src/Grpc.Net.Common/Compression/GzipCompressionProvider.cs", location.document)
        assertEquals("GzipCompressionProvider.cs", location.fileName)
        assertEquals(52, location.line)
        assertEquals("SHA256", location.hashAlgorithm)
        assertEquals("https://raw.githubusercontent.com/grpc/grpc-dotnet/4301104498e53898a452e8fb2fea6c0b1492b755/src/Grpc.Net.Common/Compression/GzipCompressionProvider.cs", location.url)
        assertNull(location.embedded)
        assertTrue(location.memberFound)
        assertNull(SourceLocation.parse(JsonParser.parseString("""{"assembly": "x"}""")))
        assertEquals("sha256-55d16c2b85831e491e53e3b96718933e8f97ee544278093c2048ef7d1c90cad7", LibrarySourceFiles.key(location.hashAlgorithm, location.hash))
    }

    // ------------------------------------------------------------------------------------------------ the file in the editor

    private class FakeLocator(private val answer: (String, String, String?) -> SourceLocation) : LibrarySourceLocator {
        var calls = 0
        override fun locate(assembly: String, typeName: String, memberId: String?): SourceLocation {
            calls++
            return answer(assembly, typeName, memberId)
        }
    }

    private fun recorded(): SourceLocation =
        SourceLocation.parse(JsonParser.parseString(File("src/test/resources/sourcelink/gzip-provider.json").readText()))!!

    private fun assembly(): File = File(work, "Grpc.Net.Common.dll").apply { writeText("not really an assembly") }

    private val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())

    /** The recorded location with the hash of [text] (of its bytes with a BOM when [withBom]: the compiler hashes the file as it is), as if the PDB had been built from it. */
    private fun located(text: String, line: Int, column: Int = 9, embedded: Boolean = false, withBom: Boolean = false): SourceLocation =
        recorded().copy(line = line, column = column, hash = hex(MessageDigest.getInstance("SHA-256").digest((if (withBom) bom else byteArrayOf()) + text.toByteArray())),
            embedded = text.takeIf { embedded })

    private val gzipSource = """
        namespace Grpc.Net.Compression;

        public sealed class GzipCompressionProvider
        {
            public GzipCompressionProvider()
            {
            }

            public Stream CreateCompressionStream(Stream stream)
            {
                return stream;
            }
        }
    """.trimIndent() + "\n"

    fun testTheSourceIsDownloadedCheckedAndOpenedReadOnly() {
        val sources = LibrarySources.getInstance(project)
        sources.locator = FakeLocator { _, _, memberId -> located(gzipSource, if (memberId == null) 6 else 10, withBom = true) }
        val downloads = ArrayList<String>()
        sources.fetch = { url, _ -> downloads += url; bom + gzipSource.toByteArray() }
        val dll = assembly()

        assertNull("nothing is known before the helper is asked", sources.cached(dll.path, "T:Grpc.Net.Compression.GzipCompressionProvider"))
        val (file, offset) = sources.source(dll, "Grpc.Net.Compression.GzipCompressionProvider", "T:Grpc.Net.Compression.GzipCompressionProvider")!!
        assertEquals(listOf("https://raw.githubusercontent.com/grpc/grpc-dotnet/4301104498e53898a452e8fb2fea6c0b1492b755/src/Grpc.Net.Common/Compression/GzipCompressionProvider.cs"), downloads)
        assertEquals("GzipCompressionProvider.cs", file.name)
        assertEquals(CSharpFileType, file.fileType)
        assertFalse(file.isWritable)
        assertEquals("the BOM is not part of the text", gzipSource, file.content.toString())
        assertEquals("GzipCompressionProvider\n{", file.content.toString().substring(offset).substringBefore("\n    public"))
        assertEquals(LibrarySourceFiles.key("SHA256", located(gzipSource, 6, withBom = true).hash) + "/GzipCompressionProvider.cs", file.path)
        assertEquals("dotnet-source://" + file.path, file.url)
        assertSame(file, VirtualFileManager.getInstance().findFileByUrl(file.url))
        assertSame(LibrarySourceFileSystem.getInstance(), file.fileSystem)
        assertFalse(FileDocumentManager.getInstance().getDocument(file)!!.isWritable)
        assertEquals(CSharpLanguage, PsiManager.getInstance(project).findFile(file)!!.language)
        assertEquals("GzipCompressionProvider.cs [Grpc.Net.Common]", LibrarySourceTabTitle().getEditorTabTitle(project, file))
        assertEquals("Navigated to source from Source Link: ${downloads[0]}. Read-only", LibrarySourceBanner.bannerText(file.origin))
        assertNotNull(LibrarySourceBanner().collectNotificationData(project, file))
        assertNull(LibrarySourceBanner().collectNotificationData(project, myFixture.addFileToProject("Plain.cs", "class Plain {}").virtualFile))

        // the same document for a member: no download again, the caret on the method
        val (same, member) = sources.source(dll, "Grpc.Net.Compression.GzipCompressionProvider", "M:Grpc.Net.Compression.GzipCompressionProvider.CreateCompressionStream(System.IO.Stream)")!!
        assertSame(file, same)
        assertEquals(1, downloads.size)
        assertEquals("CreateCompressionStream(Stream stream)", same.content.toString().substring(member).substringBefore("\n"))

        // known now: Go to Declaration gets the declaration itself, and after the memory is dropped the file comes back from the disk
        val element = sources.target(dll, "Grpc.Net.Compression.GzipCompressionProvider", "T:Grpc.Net.Compression.GzipCompressionProvider", "GzipCompressionProvider") {}!!
        assertTrue(element !is LibrarySourceTarget)
        assertEquals(file, element.containingFile.virtualFile)
        LibrarySourceFiles.clearMemory()
        val restored = LibrarySourceFiles.find(file.key)!!
        assertEquals(gzipSource, restored.content.toString())
        assertEquals(file.origin, restored.origin)
        assertNotNull(sources.cached(dll.path, "T:Grpc.Net.Compression.GzipCompressionProvider"))
    }

    fun testATargetGetsTheSourceLaterAndTheOptionTurnsItOff() {
        val sources = LibrarySources.getInstance(project)
        val locator = FakeLocator { _, _, _ -> located(gzipSource, 6) }
        sources.locator = locator
        val dll = assembly()
        val target = sources.target(dll, "Grpc.Net.Compression.GzipCompressionProvider", "T:Grpc.Net.Compression.GzipCompressionProvider", "GzipCompressionProvider") {}
        assertTrue("$target", target is LibrarySourceTarget)
        assertTrue((target as LibrarySourceTarget).canNavigate())
        assertEquals("GzipCompressionProvider", target.name)
        assertEquals("the handler does not wait for the helper", 0, locator.calls)

        RoslynLanguageServerSettings.getInstance().setValue(LibrarySources.OPTION, "false")
        assertFalse(sources.isEnabled)
        assertNull(sources.target(dll, "Grpc.Net.Compression.GzipCompressionProvider", "T:Grpc.Net.Compression.GzipCompressionProvider", "GzipCompressionProvider") {})
        assertNull("an assembly that is not there", sources.target(File(work, "Gone.dll"), "X", "T:X", "X") {})
    }

    fun testFallbackWithoutAPdbOrWithAChangedFile() {
        val sources = LibrarySources.getInstance(project)
        val locator = FakeLocator { _, _, _ -> throw HelperException("no PDB for Microsoft.Extensions.Hosting.dll (<DebugType>none</DebugType>?)") }
        sources.locator = locator
        val dll = assembly()
        assertNull(sources.source(dll, "Microsoft.Extensions.Hosting.Host", "T:Microsoft.Extensions.Hosting.Host"))
        assertNull(sources.source(dll, "Microsoft.Extensions.Hosting.HostBuilder", "T:Microsoft.Extensions.Hosting.HostBuilder"))
        assertEquals("an assembly without a PDB is not asked about again", 1, locator.calls)
        assertTrue(sources.reasonWithout(dll)!!.contains("no PDB"))
        assertNull(sources.target(dll, "Microsoft.Extensions.Hosting.Host", "T:Microsoft.Extensions.Hosting.Host", "Host") {})

        // the file on the web is not the one of the build: not shown, and nothing cached
        sources.forget()
        sources.locator = FakeLocator { _, _, _ -> located(gzipSource, 6) }
        sources.fetch = { _, _ -> "class Changed {}\n".toByteArray() }
        assertNull(sources.source(dll, "Grpc.Net.Compression.GzipCompressionProvider", "T:Grpc.Net.Compression.GzipCompressionProvider"))
        assertNull(LibrarySourceFiles.find(LibrarySourceFiles.key("SHA256", located(gzipSource, 6).hash)))
        assertNull("a network failure", run {
            sources.fetch = { _, _ -> throw java.io.IOException("no network") }
            sources.source(dll, "Grpc.Net.Compression.GzipCompressionProvider", "T:Grpc.Net.Compression.GzipCompressionProvider")
        })

        // a PDB without a Source Link that does not embed the document either
        sources.forget()
        sources.locator = FakeLocator { _, _, _ -> located(gzipSource, 6).copy(sourceLink = """{"documents":{}}""") }
        assertNull(sources.source(dll, "Grpc.Net.Compression.GzipCompressionProvider", "T:Grpc.Net.Compression.GzipCompressionProvider"))
        assertTrue(sources.reasonWithout(dll)!!.contains("no URL"))
    }

    fun testAnEmbeddedSourceNeedsNoNetwork() {
        val sources = LibrarySources.getInstance(project)
        sources.locator = FakeLocator { _, _, _ -> located(gzipSource, 6, embedded = true).copy(sourceLink = null) }
        sources.fetch = { url, _ -> throw AssertionError("downloaded $url") }
        val dll = assembly()
        val (file, offset) = sources.source(dll, "Grpc.Net.Compression.GzipCompressionProvider", "T:Grpc.Net.Compression.GzipCompressionProvider")!!
        assertTrue(file.origin.isEmbedded)
        assertEquals("Embedded source of Grpc.Net.Common: /_/src/Grpc.Net.Common/Compression/GzipCompressionProvider.cs. Read-only", LibrarySourceBanner.bannerText(file.origin))
        assertEquals("Grpc.Net.Common: /_/src/Grpc.Net.Common/Compression/GzipCompressionProvider.cs", file.presentableUrl)
        assertEquals("GzipCompressionProvider", file.content.toString().substring(offset, offset + 23))
        assertTrue(file is LibrarySourceFile)
    }

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }
}
