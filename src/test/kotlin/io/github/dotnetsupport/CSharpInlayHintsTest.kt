package io.github.dotnetsupport

import com.intellij.openapi.application.ApplicationManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.dotnetsupport.csharp.lang.CSharpFile
import io.github.dotnetsupport.index.AssemblyDocs
import io.github.dotnetsupport.index.AssemblyIndex
import io.github.dotnetsupport.index.AssemblyIndexSet
import io.github.dotnetsupport.lang.CSharpFeature
import io.github.dotnetsupport.lang.CSharpFeatureSource
import io.github.dotnetsupport.lang.CSharpFeatures
import io.github.dotnetsupport.lang.CSharpSyntaxTrees
import io.github.dotnetsupport.lang.NativeCSharpInlayHints
import io.github.dotnetsupport.lang.NativeCSharpInlayHints.Options
import io.github.dotnetsupport.lang.semantic.CSharpSemanticEnvironment
import io.github.dotnetsupport.lsp.RoslynLanguageServerSettings
import io.github.dotnetsupport.lsp.RoslynOptions
import io.github.dotnetsupport.settings.DotNetSettings

/**
 * Inlay hints on the plugin's own semantics ([NativeCSharpInlayHints]), over the fixtures of src/test/resources/index: Roslyn's rules for
 * the names of parameters and the types of `var`, lambda parameters, `new()` and collection expressions, each option of Settings | .NET |
 * Language Server on and off. A hint is rendered into the text as `«text»` before the character it stands at.
 */
class CSharpInlayHintsTest : BasePlatformTestCase() {
    private val settings get() = RoslynLanguageServerSettings.getInstance()
    private var serverEnabled = false
    private var hideObvious = true

    override fun setUp() {
        super.setUp()
        CSharpSyntaxTrees.forceNativeTreeForTests(true)
        CSharpSemanticEnvironment.setAssembliesForTests { ASSEMBLIES }
        serverEnabled = settings.state.enabled
        // the older cases show every `var` hint; the obvious ones are tested below
        hideObvious = DotNetSettings.getInstance().hideObviousTypeHints
        DotNetSettings.getInstance().hideObviousTypeHints = false
    }

    override fun tearDown() {
        try {
            settings.state.features = mutableMapOf()
            settings.state.options = mutableMapOf()
            settings.state.enabled = serverEnabled
            DotNetSettings.getInstance().hideObviousTypeHints = hideObvious
            CSharpSemanticEnvironment.setAssembliesForTests(null)
            CSharpSyntaxTrees.forceNativeTreeForTests(null)
        } catch (e: Throwable) {
            addSuppressedException(e)
        } finally {
            super.tearDown()
        }
    }

    /** The lines of [body] (statements of a method of a class with the usings of the fixtures and [members] beside it) with the hints of [options] rendered in. */
    private fun render(body: String, members: String = "", options: Options = Options.fromSettings()): String {
        val f = myFixture.addFileToProject("hints/T${counter++}.cs", """
            using System;
            using System.Collections.Generic;
            using System.Linq;
            using Fixture;

            class Certificate { public string Name = ""; public int Days; public Certificate() {} public Certificate(string name, int days) {} }

            class Sample
            {
                readonly List<Certificate> _certificates = new List<Certificate>();
            ${members.prependIndent("    ")}
                void Body(int number, string text, List<int> numbers, Dictionary<string, int> map, Circle circle, int width)
                {
                    // <body>
            ${body.prependIndent("        ")}
                    // </body>
                }
            }
        """.trimIndent()) as CSharpFile
        val text = StringBuilder(f.text)
        for (hint in NativeCSharpInlayHints.hints(f, options).asReversed()) text.insert(hint.offset, "«${hint.text}»")
        return text.substring(text.indexOf("// <body>") + "// <body>".length, text.indexOf("// </body>")).trimIndent().trim()
    }

    private fun on(section: String) = settings.state.options.put("inlay_hints.$section", "true")
    private fun off(section: String) = settings.state.options.put("inlay_hints.$section", "false")

    // ---- parameter names

    fun testLiteralsNewAndIndexersByDefault() {
        assertEquals(
            """
            Draw(«width:»10, «title:»"t", «shape:»new Circle());
            Draw(number, text, circle);
            Draw(«width:»-1, «title:»${'$'}"{text}", «shape:»(Circle)null!);
            var «int»n = map[«key:»"k"];
            Fill(«size:»new());
            """.trimIndent(),
            render(
                """
                Draw(10, "t", new Circle());
                Draw(number, text, circle);
                Draw(-1, ${'$'}"{text}", (Circle)null!);
                var n = map["k"];
                Fill(new());
                """.trimIndent(),
                """
                void Draw(int width, string title, Circle shape) {}
                void Fill(List<int> size) {}
                """.trimIndent(),
            ),
        )
    }

    fun testEverythingElseWhenAsked() {
        on("dotnet_enable_inlay_hints_for_other_parameters")
        assertEquals(
            """
            Draw(«width:»number, «title:»text, «shape:»circle);
            Draw(width, «title:»text, «shape:»circle);
            Draw(this.Width, «title:»text, «shape:»circle);
            Draw(Width, Title, «shape:»circle);
            var «Certificate»first = _certificates.Where(«predicate:»«Certificate»certificate => certificate.Days > 0).First();
            """.trimIndent(),
            render(
                """
                Draw(number, text, circle);
                Draw(width, text, circle);
                Draw(this.Width, text, circle);
                Draw(Width, Title, circle);
                var first = _certificates.Where(certificate => certificate.Days > 0).First();
                """.trimIndent(),
                """
                int Width;
                string Title = "";
                void Draw(int width, string title, Circle shape) {}
                """.trimIndent(),
            ),
        )
    }

    fun testTheArgumentNamedAsTheParameterIsNotSuppressedWhenAsked() {
        on("dotnet_enable_inlay_hints_for_other_parameters")
        off("dotnet_suppress_inlay_hints_for_parameters_that_match_argument_name")
        assertEquals(
            "Draw(«width:»width, «title:»text, «shape:»circle);",
            render("Draw(width, text, circle);", "void Draw(int width, string title, Circle shape) {}"),
        )
    }

    fun testNamedArgumentsAndParamsGetNone() {
        assertEquals(
            """
            Draw(width: 1, «title:»"t", shape: circle);
            Log(«format:»"x", 1, 2);
            Log(«format:»"x", new[] { 1 });
            """.trimIndent(),
            render(
                """
                Draw(width: 1, "t", shape: circle);
                Log("x", 1, 2);
                Log("x", new[] { 1 });
                """.trimIndent(),
                """
                void Draw(int width, string title, Circle shape) {}
                void Log(string format, params object[] args) {}
                """.trimIndent(),
            ),
        )
    }

    fun testNamesDifferingOnlyBySuffix() {
        val members = """
            void Pair(int arg1, int arg2) {}
            void Both(int valueA, int valueB) {}
            void Mixed(int first, int arg2) {}
        """.trimIndent()
        val body = """
            Pair(1, 2);
            Both(1, 2);
            Mixed(1, 2);
        """.trimIndent()
        assertEquals("Pair(1, 2);\nBoth(1, 2);\nMixed(«first:»1, «arg2:»2);", render(body, members))
        off("dotnet_suppress_inlay_hints_for_parameters_that_differ_only_by_suffix")
        assertEquals("Pair(«arg1:»1, «arg2:»2);\nBoth(«valueA:»1, «valueB:»2);\nMixed(«first:»1, «arg2:»2);", render(body, members))
        assertTrue(NativeCSharpInlayHints.differOnlyBySuffix(listOf("x1", "x2", "x10")))
        assertFalse("one parameter is never a set", NativeCSharpInlayHints.differOnlyBySuffix(listOf("arg1")))
        assertFalse("digits alone are no prefix", NativeCSharpInlayHints.differOnlyBySuffix(listOf("1", "2")))
    }

    fun testNamesMatchingTheIntentOfTheMethod() {
        val members = """
            void SetColor(string color) {}
            void SetSize(int width) {}
            void EnableLogging(bool on) {}
            void DisableCache(int level) {}
            void Set(int value) {}
        """.trimIndent()
        val body = """
            SetColor("red");
            SetSize(1);
            EnableLogging(true);
            DisableCache(1);
            Set(1);
        """.trimIndent()
        assertEquals("SetColor(\"red\");\nSetSize(«width:»1);\nEnableLogging(true);\nDisableCache(«level:»1);\nSet(«value:»1);", render(body, members))
        off("dotnet_suppress_inlay_hints_for_parameters_that_match_method_intent")
        assertEquals("SetColor(«color:»\"red\");\nSetSize(«width:»1);\nEnableLogging(«on:»true);\nDisableCache(«level:»1);\nSet(«value:»1);", render(body, members))
        assertFalse("a constructor has no intent", NativeCSharpInlayHints.matchesMethodIntent("Color", "color") { false })
        assertTrue(NativeCSharpInlayHints.matchesMethodIntent("SetColor", "color") { false })
        assertFalse(NativeCSharpInlayHints.matchesMethodIntent("SetColor", "colour") { false })
    }

    fun testTheParameterOptionsOneByOne() {
        val members = """
            void Draw(int width, string title, Circle shape) {}
            void Fill(List<int> size) {}
        """.trimIndent()
        val body = """
            Draw(10, text, new Circle());
            Fill(new());
            var n = map["k"];
        """.trimIndent()
        off("dotnet_enable_inlay_hints_for_literal_parameters")
        assertEquals("Draw(10, text, «shape:»new Circle());\nFill(«size:»new());\nvar «int»n = map[\"k\"];", render(body, members))
        off("dotnet_enable_inlay_hints_for_object_creation_parameters")
        on("dotnet_enable_inlay_hints_for_literal_parameters")
        assertEquals("Draw(«width:»10, text, new Circle());\nFill(new());\nvar «int»n = map[«key:»\"k\"];", render(body, members))
        on("dotnet_enable_inlay_hints_for_object_creation_parameters")
        off("dotnet_enable_inlay_hints_for_indexer_parameters")
        assertEquals("Draw(«width:»10, text, «shape:»new Circle());\nFill(«size:»new());\nvar «int»n = map[\"k\"];", render(body, members))
        on("dotnet_enable_inlay_hints_for_indexer_parameters")
        off("dotnet_enable_inlay_hints_for_parameters")
        assertEquals("the master switch", "Draw(10, text, new Circle());\nFill(new());\nvar «int»n = map[\"k\"];", render(body, members))
    }

    fun testConstructorsAndInitializers() {
        assertEquals(
            """
            var «Certificate»s = new Certificate(«name:»"s", «days:»3);
            Certificate t = new(«name:»"t", «days:»4);
            """.trimIndent(),
            render(
                """
                var s = new Certificate("s", 3);
                Certificate t = new("t", 4);
                """.trimIndent(),
            ),
        )
        val f = myFixture.addFileToProject("hints/Ctor.cs", """
            class Base { public Base(int size, string label) {} }
            class Derived : Base
            {
                public Derived() : base(1, "x") {}
            }
            class Primary(int size) : Base(size, "y");
        """.trimIndent()) as CSharpFile
        val hints = NativeCSharpInlayHints.hints(f, Options()).map { it.toString() }
        val text = f.text
        assertEquals(listOf("${text.indexOf("1, \"x\"")}:size:", "${text.indexOf("\"x\"")}:label:", "${text.indexOf("\"y\"")}:label:"), hints)
    }

    // ---- types

    fun testTypesOfVar() {
        assertEquals(
            """
            var «List<int>»list = new List<int>();
            var «Certificate»one = _certificates[«index:»0];
            foreach (var «int»item in list) { }
            foreach (var «KeyValuePair<string, int>»pair in map) { }
            if (map.TryGetValue(«key:»"k", out var «int»found)) { }
            var («int»a, «string»b) = (1, "x");
            var «Dictionary<string, int>»m = map;
            using var «List<int>.Enumerator»e = list.GetEnumerator();
            for (var «int»i = 0; i < 1; i++) { }
            var u = Unknown();
            int plain = 1;
            var x = 1, y = 2;
            """.trimIndent(),
            render(
                """
                var list = new List<int>();
                var one = _certificates[0];
                foreach (var item in list) { }
                foreach (var pair in map) { }
                if (map.TryGetValue("k", out var found)) { }
                var (a, b) = (1, "x");
                var m = map;
                using var e = list.GetEnumerator();
                for (var i = 0; i < 1; i++) { }
                var u = Unknown();
                int plain = 1;
                var x = 1, y = 2;
                """.trimIndent(),
            ),
        )
    }

    /** The report that started this: `var resp = _context.Certificates.Where(certificate => …).Order();` showed no type at all without the server. */
    fun testTheLinqChainAndItsLambdas() {
        assertEquals(
            """
            var «IOrderedEnumerable<Certificate>»resp = _certificates.Where(«Certificate»certificate => certificate.Days > 0).OrderBy(«Certificate»c => c.Name);
            Func<int, int, int> add = («int»x, «int»y) => x + y;
            Func<int, int> typed = (int x) => x;
            var «List<string>»names = _certificates.Select(«Certificate»c => c.Name).ToList();
            """.trimIndent(),
            render(
                """
                var resp = _certificates.Where(certificate => certificate.Days > 0).OrderBy(c => c.Name);
                Func<int, int, int> add = (x, y) => x + y;
                Func<int, int> typed = (int x) => x;
                var names = _certificates.Select(c => c.Name).ToList();
                """.trimIndent(),
            ),
        )
    }

    fun testImplicitNewAndCollectionExpressionsOnlyWhenAsked() {
        val body = """
            List<int> xs = new();
            List<int> ys = [1, 2];
            Fill([3]);
        """.trimIndent()
        val members = "void Fill(List<int> size) {}"
        // a collection expression is no literal to Roslyn's GetKind: with "everything else" off its argument gets no name
        assertEquals("off by default, as the server's", "List<int> xs = new();\nList<int> ys = [1, 2];\nFill([3]);", render(body, members))
        on("csharp_enable_inlay_hints_for_implicit_object_creation")
        assertEquals("List<int> xs = new«List<int>»();\nList<int> ys = [1, 2];\nFill([3]);", render(body, members))
        on("csharp_enable_inlay_hints_for_collection_expressions")
        assertEquals("List<int> xs = new«List<int>»();\nList<int> ys = «List<int>»[1, 2];\nFill(«List<int>»[3]);", render(body, members))
        on("dotnet_enable_inlay_hints_for_other_parameters")
        assertEquals("List<int> xs = new«List<int>»();\nList<int> ys = «List<int>»[1, 2];\nFill(«size:»«List<int>»[3]);", render(body, members))
    }

    fun testObviousInitializersGetNoTypeHint() {
        val members = "List<Certificate> _orders = new(); enum Color { Red, Green } Color Pick() => Color.Red; Color Shade = Color.Red;"
        val body = """
            var when = new DateTime(2026, 9, 21);
            var color = Color.Green;
            var text = "multiline";
            var count = 42;
            var ok = true;
            var c = 'x';
            var d = default(DateTime);
            var t = typeof(string);
            var cast = (object)text;
            var asText = cast as string;
            var name = nameof(text);
            var first = _orders.First();
            var picked = Pick();
            var fromMember = Shade;
            var implicitNew = new Certificate();
        """.trimIndent()
        val hidden = render(body, members, Options(parameters = false, hideObvious = true))
        assertEquals(
            """
            var when = new DateTime(2026, 9, 21);
            var color = Color.Green;
            var text = "multiline";
            var count = 42;
            var ok = true;
            var c = 'x';
            var d = default(DateTime);
            var t = typeof(string);
            var cast = (object)text;
            var asText = cast as string;
            var name = nameof(text);
            var «Certificate»first = _orders.First();
            var «Color»picked = Pick();
            var «Color»fromMember = Shade;
            var implicitNew = new Certificate();
            """.trimIndent(), hidden,
        )
        val all = render(body, members, Options(parameters = false, hideObvious = false))
        assertTrue(all, "var «DateTime»when = new DateTime(2026, 9, 21);" in all && "var «Color»color = Color.Green;" in all && "var «Certificate»first = _orders.First();" in all)
    }

    fun testTheTypeOptionsOneByOne() {
        val body = """
            var list = new List<int>();
            var first = list.First(n => n > 0);
        """.trimIndent()
        off("csharp_enable_inlay_hints_for_lambda_parameter_types")
        assertEquals("var «List<int>»list = new List<int>();\nvar «int»first = list.First(n => n > 0);", render(body))
        on("csharp_enable_inlay_hints_for_lambda_parameter_types")
        off("csharp_enable_inlay_hints_for_implicit_variable_types")
        assertEquals("var list = new List<int>();\nvar first = list.First(«int»n => n > 0);", render(body))
        on("csharp_enable_inlay_hints_for_implicit_variable_types")
        off("csharp_enable_inlay_hints_for_types")
        assertEquals("the master switch", body, render(body))
    }

    fun testTypeHintsLeadToTheType() {
        val f = myFixture.addFileToProject("hints/Targets.cs", """
            using System.Collections.Generic;
            class Own {}
            class Sample
            {
                void M()
                {
                    var own = new Own();
                    var list = new List<Own>();
                }
            }
        """.trimIndent()) as CSharpFile
        val hints = NativeCSharpInlayHints.hints(f, Options(hideObvious = false))
        assertEquals(listOf("Own", "List<Own>"), hints.map { it.text })
        val own = hints[0].target as NativeCSharpInlayHints.Target.Source
        assertEquals("class Own {}", own.element.text)
        val list = hints[1].target as NativeCSharpInlayHints.Target.Library
        assertEquals("System.Collections.Generic.List`1", list.fullName)
    }

    // ---- the settings and the switch

    fun testTheOptionsAreTheOnesOfTheSettingsPage() {
        val defaults = Options.fromSettings()
        assertTrue(defaults.parameters && defaults.literals && defaults.indexers && defaults.objectCreation && !defaults.others)
        assertTrue(defaults.suppressSuffix && defaults.suppressIntent && defaults.suppressArgumentName)
        assertTrue(defaults.types && defaults.varTypes && defaults.lambdaTypes && !defaults.implicitNew && !defaults.collections)
        assertEquals("every inlay option of the page is read", 13, RoslynOptions.ALL.count { it.section.startsWith("inlay_hints.") })
        on("dotnet_enable_inlay_hints_for_other_parameters")
        on("csharp_enable_inlay_hints_for_collection_expressions")
        off("csharp_enable_inlay_hints_for_types")
        val changed = Options.fromSettings()
        assertTrue(changed.others && changed.collections && !changed.types)
        assertFalse("no type hint can come out of these", changed.any(NativeCSharpInlayHints.Kind.TYPE))
        assertTrue(changed.any(NativeCSharpInlayHints.Kind.PARAMETER))
    }

    /**
     * Robot 0.1.117: an option toggled on the page changed nothing on screen until the next edit — the inlay passes of the platform run
     * again only when the PSI changed, so Apply forces them ([io.github.dotnetsupport.lang.NativeCSharpInlayHintsSwitch]); the same for the
     * source of the feature switched while the server runs.
     */
    fun testApplyingThePageRedrawsTheHintsOfOpenEditors() {
        myFixture.configureByText("Refresh.cs", "class A { void Draw(int width) { Draw(1); } }")
        fun shown(): Int {
            myFixture.doHighlighting()
            return myFixture.editor.inlayModel.getInlineElementsInRange(0, myFixture.editor.document.textLength).size
        }
        // a lambda, not a local fun: a local fun that captures nothing compiles to a method `test…$applied`, which JUnit 3 runs as a test
        val applied = { ApplicationManager.getApplication().messageBus.syncPublisher(RoslynLanguageServerSettings.CHANGED).settingsChanged(false) }
        assertEquals("«width:» before 1", 1, shown())
        off("dotnet_enable_inlay_hints_for_literal_parameters")
        applied()
        assertEquals("the option is off: the hint goes without an edit", 0, shown())
        on("dotnet_enable_inlay_hints_for_literal_parameters")
        applied()
        assertEquals("and comes back", 1, shown())
        // the source given to the running server: the plugin's hints go at once (the server's pass is forced the same way)
        settings.state.enabled = true
        settings.setSource(CSharpFeature.INLAY_HINTS, CSharpFeatureSource.ROSLYN)
        applied()
        assertEquals("the server answers", 0, shown())
        settings.setSource(CSharpFeature.INLAY_HINTS, CSharpFeatureSource.NATIVE)
        applied()
        assertEquals("the plugin again", 1, shown())
    }

    fun testTheSwitchGivesTheHintsToOneSource() {
        val f = myFixture.addFileToProject("hints/Switch.cs", "class A { void M() { var x = 1; } }") as CSharpFile
        assertTrue("built-in by default", CSharpFeatures.native(CSharpFeature.INLAY_HINTS, project))
        assertTrue(NativeCSharpInlayHints.serves(f))
        settings.state.enabled = true
        settings.setSource(CSharpFeature.INLAY_HINTS, CSharpFeatureSource.ROSLYN)
        assertFalse("the server's hints answer", NativeCSharpInlayHints.serves(f))
        settings.state.enabled = false
        assertTrue("without the server the plugin's answer whatever the switch", NativeCSharpInlayHints.serves(f))
    }

    private companion object {
        var counter = 0

        private fun bytes(name: String): ByteArray? = CSharpInlayHintsTest::class.java.getResourceAsStream("/index/$name")?.use { it.readBytes() }
        private fun fixture(name: String): AssemblyIndex = AssemblyIndex.read(bytes("$name.dnix")!!, bytes("$name.dnxd")?.let(AssemblyDocs::read))

        val ASSEMBLIES: AssemblyIndexSet by lazy {
            AssemblyIndexSet(listOf("IndexFixture", "System.Runtime", "System.Console", "System.Linq", "System.Collections").map(::fixture))
        }
    }
}
