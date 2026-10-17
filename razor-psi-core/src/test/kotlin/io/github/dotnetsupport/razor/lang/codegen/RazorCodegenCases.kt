package io.github.dotnetsupport.razor.lang.codegen

import io.github.dotnetsupport.razor.RazorTestUtil
import io.github.dotnetsupport.razor.lang.codegen.mvc.RazorExtensions
import io.github.dotnetsupport.razor.lang.syntax.RazorFileKind
import io.github.dotnetsupport.razor.lang.syntax.RazorLanguageVersion
import io.github.dotnetsupport.razor.lang.syntax.RazorSourceDocument
import io.github.dotnetsupport.razor.lang.syntax.TagHelperCollection
import io.github.dotnetsupport.razor.lang.syntax.TagHelperDescriptorJson
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.name

/**
 * The cases of the Razor compiler's code generation integration tests and how each test builds its engine — the Kotlin of razordump's
 * `cases-codegen` (tools/razor/razordump/Codegen.cs, README.md there) on our engine: every `<Name>.cshtml` / `<Name>.razor` with a
 * `<Name>.codegen.cs` beside it under [TEST_FILE_ROOTS], configured as `IntegrationTestBase` (`CreateProjectItemFromFile`,
 * `CreateProjectEngine`, `NormalizedDefaultImportFeature`) and the test class / method (read from `test/IntegrationTests/<Class>.cs`) do.
 * The generator engine of the corpus gate (`razordump codegen`) is [createGeneratorEngine].
 */
object RazorCodegenCases {
    const val CRLF = "\r\n"
    const val SUPPRESS_UNIQUE_IDS = "__UniqueIdSuppressedForTesting__"

    /** The integration test folders of the two test projects, under the compiler (`.corpus/razor/razor-compiler/src/Razor/src/Compiler`). */
    val TEST_FILE_ROOTS = listOf(
        "Microsoft.AspNetCore.Razor.Language/test/TestFiles/IntegrationTests",
        "Microsoft.AspNetCore.Mvc.Razor.Extensions/test/TestFiles/IntegrationTests",
    )

    /**
     * The tag helpers a case is processed with. The tests discover them from the test's C# (`AddCSharpSyntaxTree`) and the references
     * (MVC's own `UrlResolutionTagHelper`, `HeadTagHelper`, `BodyTagHelper` of the default imports) through a Roslyn compilation, which
     * the port has not: by default the descriptors razordump discovered for the case (`cases-codegen --tag-helpers-out`), read from
     * [tagHelpersFixture] when it exists, none otherwise.
     */
    var tagHelpers: (Case) -> TagHelperCollection = { case ->
        tagHelpersFixture(case)?.let { TagHelperDescriptorJson.read(RazorTestUtil.readText(it)) } ?: TagHelperCollection.EMPTY
    }

    /**
     * `testData/codegen-tagHelpers/<Project>/<Class>/<Name>.taghelpers.json` of a case (its [Case.relative] without the extension), else the
     * shared `references.taghelpers.json` — the descriptors every case discovers from the references alone (the 87 identical files razordump
     * wrote are kept once); null without either.
     */
    fun tagHelpersFixture(case: Case): Path? {
        val root = Path.of(RazorTestUtil.testDataPath("codegen-tagHelpers"))
        return root.resolve(case.relative.substringBeforeLast('.') + ".taghelpers.json").takeIf { Files.isRegularFile(it) }
            ?: root.resolve("references.taghelpers.json").takeIf { Files.isRegularFile(it) }
    }

    /** One case: the input, its test's file name (`TestFiles/IntegrationTests/<Class>/<Name>.cshtml`) and what its test method does. */
    class Case(val inputPath: Path, val relative: String, val testFileName: String, val name: String, val className: String) {
        var skipReason: String? = null
        var fileKind: RazorFileKind? = null
        var configuration: RazorConfiguration = RazorConfiguration.DEFAULT
        var registerMvc: Boolean = false

        /** The C# language version of the test class as a major number; null for `Preview` / `Latest` / `Default`. */
        var csharpMajor: Int? = null

        /** The imports the test adds (`AddProjectItemFromText`): the file name relative to the test's folder, the text. */
        val imports = ArrayList<Pair<String, String>>()

        /** The C# the test adds to its compilation (`AddCSharpSyntaxTree`): where its tag helpers come from. */
        val csharpTrees = ArrayList<String>()

        override fun toString(): String = relative
    }

    /** Every case under the compiler's test folders, ordinal order of [Case.relative] (`<project>/<Class>/<Name>.cshtml`). */
    fun cases(compiler: Path): List<Case> {
        val result = ArrayList<Case>()
        for (rootName in TEST_FILE_ROOTS) {
            val root = compiler.resolve(rootName)
            if (!Files.isDirectory(root)) continue
            val project = rootName.substringBefore('/').removePrefix("Microsoft.AspNetCore.")
            val inputs = Files.walk(root).use { s ->
                s.filter { Files.isRegularFile(it) && (it.name.endsWith(".cshtml", ignoreCase = true) || it.name.endsWith(".razor", ignoreCase = true)) }
                    .filter { Files.isRegularFile(it.resolveSibling(it.name.substringBeforeLast('.') + ".codegen.cs")) }
                    .toList()
            }
            for (input in inputs) {
                val full = input.toAbsolutePath().toString().replace('\\', '/')
                val at = full.lastIndexOf("/TestFiles/")
                val testFileName = if (at >= 0) full.substring(at + 1) else input.name
                val className = input.parent.name
                val case = Case(input, project + "/" + root.relativize(input).toString().replace('\\', '/'), testFileName, input.name.substringBeforeLast('.'), className)
                val testClass = (if (at >= 0) TestClass.of(Path.of(full.substring(0, at + "/TestFiles".length)), className) else null) ?: TestClass.FALLBACK
                testClass.describe(case)
                result += case
            }
        }
        return result.sortedBy { it.relative }
    }

    /**
     * Processes a case as its test does: the project item of `CreateProjectItemFromFile` (CRLF text, the paths of the test's file name), the
     * imports the test adds, the engine of [createTestEngine], `Process(source, kind, imports, tagHelpers)` with [tagHelpers].
     */
    fun process(case: Case): RazorCodeDocument {
        // The tests ran on Windows: CRLF; TestFile.ReadAllText drops the BOM (the project item puts one back for the checksum).
        val text = RazorTestUtil.readText(case.inputPath).removePrefix("﻿").replace("\r\n", "\n").replace("\n", CRLF)
        val workingDirectory = case.testFileName.substringBeforeLast('.').substringBeforeLast('/')
        val item = TestProjectItem(
            basePath = workingDirectory.replace('/', '\\'), filePath = "/" + case.testFileName, physicalPath = case.testFileName,
            relativePhysicalPath = case.testFileName, kind = case.fileKind, content = text,
        )
        val fileSystem = VirtualRazorProjectFileSystem()
        for ((fileName, importText) in case.imports) {
            // CreateProjectItemFromText: the path relative to the test's folder in Windows form, base path "", CRLF.
            val physicalPath = "$workingDirectory/$fileName".replace('/', '\\')
            val filePath = "/" + physicalPath.replace('\\', '/').trimStart('/')
            fileSystem.add(TestProjectItem(basePath = "", filePath = filePath, physicalPath = physicalPath, relativePhysicalPath = physicalPath, kind = null, content = normalizeNewLines(importText, CRLF)))
        }
        val engine = createTestEngine(case.configuration, fileSystem, case.registerMvc, case.csharpMajor)
        val imports = RazorProjectEngine.getImportSourceDocuments(engine.getImports(item))
        return engine.process(item.getSource(), item.fileKind, imports, tagHelpers(case))
    }

    /**
     * `IntegrationTestBase.CreateProjectEngine`: CRLF, unique ids suppressed, the markup split on (the tests assert the split output, as the
     * source generator opts in), `RegisterExtensions()` of the configuration's name, the class's MVC registration (`RazorExtensions.Register`),
     * its C# version, the Roslyn tokenizer, every import feature wrapped in [NormalizedDefaultImportFeature].
     */
    fun createTestEngine(
        configuration: RazorConfiguration, fileSystem: RazorProjectFileSystem, registerMvc: Boolean, csharpMajor: Int?, enableMarkupSplit: Boolean = true,
    ): RazorProjectEngine = RazorProjectEngine.create(configuration, fileSystem) { b ->
        b.configureCodeGenerationOptions { o ->
            o.newLine = CRLF
            o.suppressUniqueIds = SUPPRESS_UNIQUE_IDS
            o.enableMarkupSplit = enableMarkupSplit
        }
        b.registerExtensions()
        if (registerMvc) RazorExtensions.register(b)
        // (the test's CompilationTagHelperFeature: the tag helpers are given to Process instead, see `tagHelpers`)
        b.setCSharpLanguageVersion(csharpMajor)
        b.configureParserOptions { o -> o.useRoslynTokenizer = true }
        for (feature in b.features.filterIsInstance<ImportProjectFeature>()) {
            b.features[b.features.indexOf(feature)] = NormalizedDefaultImportFeature(feature, CRLF)
        }
    }

    /**
     * The engine of the SDK's source generator as razordump's `codegen` builds it (`Codegen.CreateGeneratorEngine`): consolidated MVC views,
     * `AddComponentParameter` available, no `RazorSourceChecksumAttribute`, the Roslyn tokenizer, C# preview, the MVC extension, [newLine].
     * `CompilerFeatures.Register` adds the tag helper producers of the discovery only, which the gate runs without (`--no-tag-helpers`).
     */
    fun createGeneratorEngine(fileSystem: RazorProjectFileSystem, version: RazorLanguageVersion, rootNamespace: String, newLine: String): RazorProjectEngine =
        RazorGeneratorEngine.create(fileSystem, version, rootNamespace, newLine)

    /**
     * The source document of a project item as `RazorSourceDocument.ReadFrom(projectItem)`: the physical path (else the relative one, else
     * the file path) and the relative path (else the file path); [bom] — the item's stream starts with the UTF-8 BOM, which the checksum hashes.
     */
    fun sourceDocument(text: String, physicalPath: String?, relativePhysicalPath: String?, filePath: String?, bom: Boolean): RazorSourceDocument {
        val path = physicalPath?.takeIf { it.isNotEmpty() } ?: relativePhysicalPath?.takeIf { it.isNotEmpty() } ?: filePath
        return RazorSourceDocument(text, path, relativePhysicalPath ?: filePath, hasBom = bom)
    }

    /** `NormalizeNewLines`: every `\n` not after a `\r` becomes [lineEnding]. */
    fun normalizeNewLines(content: String, lineEnding: String): String = content.replace(Regex("(?<!\r)\n"), Regex.escapeReplacement(lineEnding))

    /** `TestRazorProjectItem`: a text with the paths a test gives it; read as a file with a UTF-8 BOM. */
    class TestProjectItem(
        override val basePath: String?, override val filePath: String?, override val physicalPath: String?, override val relativePhysicalPath: String?,
        private val kind: RazorFileKind?, private val content: String, private val bom: Boolean = true,
    ) : RazorProjectItem() {
        override val exists: Boolean get() = true
        override val fileKind: RazorFileKind get() = kind ?: super.fileKind
        override fun read(): String = content
        override fun getSource(): RazorSourceDocument = sourceDocument(content, physicalPath, relativePhysicalPath, filePath, bom)
    }

    /** The tests' `NormalizedDefaultImportFeature`: every existing import read back trimmed, its line breaks [lineEnding]. */
    class NormalizedDefaultImportFeature(private val inner: ImportProjectFeature, private val lineEnding: String) : RazorProjectEngineFeatureBase(), ImportProjectFeature {
        override fun onInitialized() {
            inner.initialize(projectEngine)
        }

        override fun collectImports(projectItem: RazorProjectItem, imports: MutableList<RazorProjectItem>) {
            val innerImports = ArrayList<RazorProjectItem>()
            inner.collectImports(projectItem, innerImports)
            for (import in innerImports) {
                if (!import.exists) continue
                val text = import.read().trim()
                imports += TestProjectItem(import.basePath, import.filePath, import.physicalPath, import.relativePhysicalPath, null, normalizeNewLines(text, lineEnding))
            }
        }
    }

    /** What a test class configures for all its cases, and its methods' bodies (from one `public void Name(` to the next). */
    class TestClass(
        val configuration: RazorConfiguration, val registerMvc: Boolean, val csharpMajor: Int?, val methods: Map<String, String>,
    ) {
        /** What the test method of [case] does: skipped with tag helper descriptors of its own; imports, file kind, C#, configuration. */
        fun describe(case: Case) {
            case.configuration = configuration
            case.registerMvc = registerMvc
            case.csharpMajor = csharpMajor
            val body = methods[case.name] ?: return
            if ("RunTagHelpersTest" in body || "RunDiscoveredTagHelpersTest" in body) case.skipReason = "tag helpers"
            if ("fileKind: RazorFileKind.Component" in body) case.fileKind = RazorFileKind.Component
            // `_configuration = _configuration with { LanguageVersion = RazorLanguageVersion.Version_11_0, RazorWarningLevel = 11 }`
            Regex("""_configuration\s*=\s*_configuration\s+with\s*\{([^}]*)}""").find(body)?.let { m ->
                val with = m.groupValues[1]
                Regex("""LanguageVersion\s*=\s*RazorLanguageVersion\.(\w+)""").find(with)?.let { v -> razorVersion(v.groupValues[1])?.let { case.configuration = case.configuration.copy(languageVersion = it) } }
                Regex("""RazorWarningLevel\s*=\s*(\d+)""").find(with)?.let { w -> case.configuration = case.configuration.copy(razorWarningLevel = w.groupValues[1].toInt()) }
            }
            for (m in Regex("""AddProjectItemFromText\(""").findAll(body)) {
                val (literal, next) = readStringLiteral(body, m.range.last + 1)
                if (literal == null) continue
                var fileName = "_ViewImports.cshtml"
                val rest = body.substring(next).trimStart()
                if (rest.startsWith(',')) readStringLiteral(rest, 1).first?.let { fileName = it }
                case.imports += fileName to literal
            }
            for (m in Regex("""AddCSharpSyntaxTree\(""").findAll(body)) {
                readStringLiteral(body, m.range.last + 1).first?.let { case.csharpTrees += it }
            }
        }

        companion object {
            private val cache = HashMap<Path, TestClass?>()

            /** A class without a source next to its test files: the Language tests' default (`RunTest` registers MVC). */
            val FALLBACK = TestClass(RazorConfiguration.DEFAULT, registerMvc = true, csharpMajor = null, methods = emptyMap())

            /** The class of the tests of a `TestFiles` folder, null when there is no such source next to it. */
            fun of(testFilesFolder: Path, className: String): TestClass? {
                val source = testFilesFolder.parent.resolve("IntegrationTests").resolve("$className.cs")
                return cache.getOrPut(source) { if (Files.isRegularFile(source)) parse(RazorTestUtil.readText(source)) else null }
            }

            fun parse(text: String): TestClass {
                var configuration = RazorConfiguration.DEFAULT
                // `new(RazorLanguageVersion.Latest, "MVC-3.0", Extensions: [])` of the MVC test classes
                Regex("""new\(RazorLanguageVersion\.(\w+),\s*"([^"]+)"""").find(text)?.let { m ->
                    razorVersion(m.groupValues[1])?.let { configuration = RazorConfiguration(it, m.groupValues[2], emptyList()) }
                }
                // `RazorConfiguration.Default with { LanguageVersion = RazorLanguageVersion.Preview }`
                Regex("""RazorConfiguration\.Default\s+with\s*\{\s*LanguageVersion\s*=\s*RazorLanguageVersion\.(\w+)\s*}""").find(text)?.let { m ->
                    razorVersion(m.groupValues[1])?.let { configuration = RazorConfiguration.DEFAULT.copy(languageVersion = it) }
                }
                // RunTest of the Language tests (`CreateProjectEngine(RazorExtensions.Register)`) or a ConfigureProjectEngine that registers it
                val registerMvc = "CreateProjectEngine(RazorExtensions.Register)" in text ||
                    Regex("""void\s+ConfigureProjectEngine\([^)]*\)\s*=>\s*RazorExtensions\.Register\(""").containsMatchIn(text)
                // `CSharpParseOptions => base.CSharpParseOptions.WithLanguageVersion(LanguageVersion.CSharp8)` or `CSharpParseOptions { get; } = new(LanguageVersion.X…`
                val csharp = Regex("""CSharpParseOptions\s*=>\s*base\.CSharpParseOptions\.WithLanguageVersion\(LanguageVersion\.(\w+)\)""").find(text)
                    ?: Regex("""CSharpParseOptions\s*\{\s*get;\s*}\s*=\s*new\(LanguageVersion\.(\w+)""").find(text)
                val csharpMajor = csharp?.let { csharpMajor(it.groupValues[1]) }
                val methods = LinkedHashMap<String, String>()
                val starts = Regex("""public void (\w+)\(""").findAll(text).toList()
                // a body ends at the next member of the class (razordump's `to the next public void` would give the last test before the
                // private RunTagHelpersTest helper that helper's body, and skip it)
                val members = Regex("""\n {4}(?:\[|public |private |protected |internal )""").findAll(text).map { it.range.first }.toList()
                for (m in starts) {
                    val end = members.firstOrNull { it > m.range.first } ?: text.length
                    methods[m.groupValues[1]] = text.substring(m.range.first, end)
                }
                return TestClass(configuration, registerMvc, csharpMajor, methods)
            }

            /** A `RazorLanguageVersion` field name: `Version_11_0`, `Latest`, `Preview`, `Experimental`. */
            fun razorVersion(field: String): RazorLanguageVersion? = when (field) {
                "Latest" -> RazorLanguageVersion.LATEST
                "Preview" -> RazorLanguageVersion.PREVIEW
                "Experimental" -> RazorLanguageVersion.EXPERIMENTAL
                else -> Regex("""Version_(\d+)_(\d+)""").matchEntire(field)?.let { RazorLanguageVersion(it.groupValues[1].toInt(), it.groupValues[2].toInt()) }
            }

            /** A `LanguageVersion` member as a major number for `SetCSharpLanguageVersion`: `CSharp7_3` → 7; the open-ended ones → null. */
            fun csharpMajor(member: String): Int? = Regex("""CSharp(\d+)(?:_\d+)?""").matchEntire(member)?.groupValues?.get(1)?.toInt()
        }
    }

    /** A C# string literal at [start] (after whitespace): `"…"`, `@"…"`, `$$"""…"""` or `"""…"""` (raw, dedented); the value and the end. */
    fun readStringLiteral(text: String, start: Int): Pair<String?, Int> {
        var i = start
        while (i < text.length && text[i].isWhitespace()) i++
        while (i < text.length && text[i] == '$') i++
        if (text.startsWith("\"\"\"", i)) {
            var quotes = 0
            while (i + quotes < text.length && text[i + quotes] == '"') quotes++
            val delimiter = "\"".repeat(quotes)
            val contentStart = i + quotes
            val close = text.indexOf(delimiter, contentStart)
            if (close < 0) return null to start
            val raw = text.substring(contentStart, close).replace("\r\n", "\n")
            // Multi-line raw string: the first line break and the closing line dropped; the closing line's whitespace is the indentation.
            val lastBreak = raw.lastIndexOf('\n')
            val indentation = raw.substring(lastBreak + 1)
            val firstBreak = raw.indexOf('\n')
            if (firstBreak < 0 || lastBreak <= firstBreak) return raw.trim() to close + quotes
            val lines = raw.substring(firstBreak + 1, lastBreak).split('\n')
            return lines.joinToString("\n") { if (it.startsWith(indentation)) it.substring(indentation.length) else it.trimStart() } to close + quotes
        }
        val verbatim = i < text.length && text[i] == '@'
        if (verbatim) i++
        if (i >= text.length || text[i] != '"') return null to start
        val builder = StringBuilder()
        i++
        while (i < text.length) {
            val c = text[i]
            if (c == '"') {
                if (verbatim && i + 1 < text.length && text[i + 1] == '"') {
                    builder.append('"')
                    i += 2
                    continue
                }
                return builder.toString() to i + 1
            }
            if (!verbatim && c == '\\' && i + 1 < text.length) {
                i++
                builder.append(when (text[i]) { 'n' -> '\n'; 'r' -> '\r'; 't' -> '\t'; '0' -> '\u0000'; else -> text[i] })
                i++
                continue
            }
            builder.append(c)
            i++
        }
        return null to start
    }
}
