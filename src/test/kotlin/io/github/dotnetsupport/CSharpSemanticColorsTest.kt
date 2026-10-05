package io.github.dotnetsupport

import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.markup.EffectType
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFileFilter
import com.intellij.psi.impl.PsiManagerEx
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.dotnetsupport.lang.CSharpColorSettingsPage
import io.github.dotnetsupport.lang.CSharpColors
import io.github.dotnetsupport.lang.CSharpFeature
import io.github.dotnetsupport.lang.CSharpFeatureSource
import io.github.dotnetsupport.lang.CSharpFile
import io.github.dotnetsupport.lang.CSharpSyntaxTrees
import io.github.dotnetsupport.lang.LocalSymbolKind
import io.github.dotnetsupport.lang.NativeCSharpScopes
import io.github.dotnetsupport.lang.NativeCSharpSemanticColors
import io.github.dotnetsupport.lsp.RoslynLanguageServerSettings

/**
 * `SEMANTIC_COLORS` NATIVE (CSHARP_PSI_MIGRATION.md, task A4): the palette of [CSharpColors] from the file's tree ([NativeCSharpScopes]) and the
 * stubs of the solution. A color is written `name:KEY` with the key's external name without `CSHARP_` and `_IDENTIFIER`.
 */
class CSharpSemanticColorsTest : BasePlatformTestCase() {
    private val settings get() = RoslynLanguageServerSettings.getInstance()

    override fun setUp() {
        super.setUp()
        CSharpSyntaxTrees.forceNativeTreeForTests(true)
        settings.setSource(CSharpFeature.SEMANTIC_COLORS, CSharpFeatureSource.NATIVE)
    }

    override fun tearDown() {
        try {
            settings.state.features = mutableMapOf()
            settings.state.enabled = RoslynLanguageServerSettings.ENABLED_BY_DEFAULT
            CSharpSyntaxTrees.forceNativeTreeForTests(null)
        } catch (e: Throwable) {
            addSuppressedException(e)
        } finally {
            super.tearDown()
        }
    }

    private fun short(key: TextAttributesKey): String = key.externalName.removePrefix("CSHARP_").removeSuffix("_IDENTIFIER")

    private fun colors(path: String, text: String): List<String> {
        val file = myFixture.addFileToProject(path, text.trimIndent()) as CSharpFile
        return NativeCSharpSemanticColors.colors(file).map { (range, key) -> "${range.substring(file.text)}:${short(key)}" }
    }

    fun testDeclarationsByKind() {
        assertEquals(
            listOf(
                "SemDecl:NAMESPACE", "Inner:NAMESPACE",
                "SemBox:CLASS", "T:TYPE_PARAMETER", "seed:PRIMARY_CONSTRUCTOR_PARAMETER",
                "Max:CONSTANT", "_shared:STATIC_FIELD", "_items:FIELD", "_a:FIELD", "_b:FIELD",
                "Name:PROPERTY", "SemBox:CLASS", "T:TYPE_PARAMETER", "Default:STATIC_PROPERTY", "SemBox:CLASS",
                "Action:TYPE", "Changed:EVENT", "Action:TYPE", "Moved:EVENT",
                "SemBox:CLASS", "Put:METHOD_DECLARATION", "U:TYPE_PARAMETER", "U:TYPE_PARAMETER", "value:PARAMETER", "seed:PRIMARY_CONSTRUCTOR_PARAMETER",
                "SemBox:CLASS", "Create:STATIC_METHOD_DECLARATION",
                "SemTools:STATIC_CLASS", "Twice:EXTENSION_METHOD_DECLARATION", "x:PARAMETER", "x:PARAMETER",
                "SemPoint:RECORD", "X:PRIMARY_CONSTRUCTOR_PARAMETER", "Y:PRIMARY_CONSTRUCTOR_PARAMETER", "Sum:PROPERTY", "X:PROPERTY", "Y:PROPERTY",
                "SemSize:RECORD_STRUCT", "W:PRIMARY_CONSTRUCTOR_PARAMETER",
                "SemPair:STRUCT", "SemShape:INTERFACE", "Area:METHOD_DECLARATION",
                "SemColor:ENUM", "Red:CONSTANT", "Green:CONSTANT",
                "SemHandler:DELEGATE", "sender:PARAMETER",
            ),
            colors(
                "SemDecl/Decl.cs",
                """
                namespace SemDecl.Inner;

                public class SemBox<T>(int seed)
                {
                    private const int Max = 3;
                    private static int _shared;
                    private readonly int _items;
                    int _a, _b;
                    public string Name { get; set; }
                    public static SemBox<T> Default { get; }
                    public SemBox() : this(0) { }
                    public event System.Action Changed;
                    public event System.Action Moved { add { } remove { } }
                    ~SemBox() { }
                    public void Put<U>(U value) => System.Console.Write(seed);
                    public static SemBox<int> Create() => null;
                }

                public static class SemTools { public static int Twice(this int x) => x * 2; }
                public record SemPoint(int X, int Y) { public int Sum => X + Y; }
                public record struct SemSize(int W);
                public struct SemPair { }
                public interface SemShape { double Area(); }
                public enum SemColor { Red, Green }
                public delegate void SemHandler(object sender);
                """,
            ),
        )
    }

    fun testLocalsParametersLabelsAndMutability() {
        assertEquals(
            listOf(
                "SemLocals:CLASS", "Run:METHOD_DECLARATION", "items:PARAMETER",
                "fixedOne:LOCAL_VARIABLE", "counter:MUTABLE_LOCAL_VARIABLE", "fixedOne:LOCAL_VARIABLE", "Limit:CONSTANT",
                "Step:LOCAL_FUNCTION", "counter:MUTABLE_LOCAL_VARIABLE",
                "i:MUTABLE_LOCAL_VARIABLE", "i:MUTABLE_LOCAL_VARIABLE", "Limit:CONSTANT", "i:MUTABLE_LOCAL_VARIABLE",
                "item:LOCAL_VARIABLE", "items:PARAMETER", "counter:MUTABLE_LOCAL_VARIABLE", "item:LOCAL_VARIABLE",
                "parsed:MUTABLE_LOCAL_VARIABLE", "Fill:LOCAL_FUNCTION", "parsed:MUTABLE_LOCAL_VARIABLE", "parsed:MUTABLE_LOCAL_VARIABLE",
                "text:LOCAL_VARIABLE", "text:LOCAL_VARIABLE", "s:LOCAL_VARIABLE", "s:LOCAL_VARIABLE",
                "a:MUTABLE_LOCAL_VARIABLE", "b:MUTABLE_LOCAL_VARIABLE", "a:MUTABLE_LOCAL_VARIABLE", "b:MUTABLE_LOCAL_VARIABLE", "b:MUTABLE_LOCAL_VARIABLE", "a:MUTABLE_LOCAL_VARIABLE",
                // a name where only a type can stand: `System.Func`, `System.Exception` are of no file of the solution
                "Func:TYPE", "square:LOCAL_VARIABLE", "n:PARAMETER", "n:PARAMETER", "n:PARAMETER",
                "Exception:TYPE", "e:LOCAL_VARIABLE", "e:LOCAL_VARIABLE",
                "again:LABEL", "counter:MUTABLE_LOCAL_VARIABLE", "again:LABEL",
                "Step:LOCAL_FUNCTION", "counter:MUTABLE_LOCAL_VARIABLE",
                "Fill:LOCAL_FUNCTION", "value:PARAMETER", "value:PARAMETER",
            ),
            colors(
                "SemLocals/Locals.cs",
                """
                class SemLocals
                {
                    void Run(int[] items)
                    {
                        var fixedOne = 1;
                        int counter = fixedOne;
                        const int Limit = 10;
                        Step();
                        counter += 2;
                        for (int i = 0; i < Limit; i++) { }
                        foreach (var item in items) counter = item;
                        int parsed;
                        Fill(out parsed);
                        parsed.ToString();
                        object text = "";
                        if (text is string s) s.Trim();
                        var (a, b) = (1, 2);
                        (a, b) = (b, a);
                        System.Func<int, int> square = n => n * n;
                        try { } catch (System.Exception e) { e.ToString(); }
                    again:
                        if (counter++ < 0) goto again;

                        void Step() => counter.ToString();
                        void Fill(out int value) => value = 1;
                    }
                }
                """,
            ),
        )
    }

    fun testShadowingAndPrimaryConstructorParameters() {
        assertEquals(
            listOf(
                "SemShadow:CLASS", "name:PRIMARY_CONSTRUCTOR_PARAMETER", "Label:PRIMARY_CONSTRUCTOR_PARAMETER",
                "count:FIELD", "Label:PROPERTY", "Label:PROPERTY",
                "Use:METHOD_DECLARATION",
                "count:LOCAL_VARIABLE", "count:FIELD", "count:LOCAL_VARIABLE", "name:PRIMARY_CONSTRUCTOR_PARAMETER", "Label:PROPERTY",
                "Func:TYPE", "f:LOCAL_VARIABLE", "count:PARAMETER", "count:PARAMETER",
                // the parameter of the outer type's primary constructor is not visible in a nested type
                "SemNested:CLASS", "Peek:METHOD_DECLARATION",
            ),
            colors(
                "SemShadow/Shadow.cs",
                """
                class SemShadow(string name, string Label)
                {
                    int count;
                    public string Label { get; } = Label;

                    void Use()
                    {
                        var count = this.count + count;
                        System.Console.Write(name + Label);
                        System.Func<int, int> f = count => count;
                    }

                    class SemNested { string Peek() => name; }
                }
                """,
            ),
        )
    }

    /** The members of the other part of a partial class, of a base class in another file, types of the solution by kind — all from stubs. */
    fun testMembersAndTypesOfTheSolutionFromStubs() {
        myFixture.addFileToProject(
            "SemSolution/Other.cs",
            """
            using System;
            namespace SemSolution.Model
            {
                public static partial class SemRegistry { public static int Count; public static void Reset() { } public static event Action Changed; }
                public abstract class SemBase { protected int _ticks; protected static string Prefix => ""; public void Tick() { } }
                public struct SemMoney { public static readonly SemMoney Zero; public decimal Amount { get; init; } }
                public enum SemState { Open, Closed }
                public interface SemRepository { }
                public record SemLine(int Qty);
                public class SemAttribute : Attribute { }
            }
            """.trimIndent(),
        )
        val current = myFixture.addFileToProject(
            "SemSolution/Use.cs",
            """
            using SemSolution.Model;
            using static SemSolution.Model.SemRegistry;

            namespace SemSolution.Model
            {
                public static partial class SemRegistry { static void Touch() { Reset(); Count++; Changed?.Invoke(); } }
            }

            namespace SemSolution
            {
                [Sem]
                class SemOrder : SemBase, SemRepository
                {
                    SemState state = SemState.Open;
                    SemMoney Total() => new SemMoney { Amount = 1 };
                    void Go() { _ticks++; Tick(); Prefix.Trim(); SemRegistry.Reset(); var line = new SemLine(1); }
                }
            }
            """.trimIndent(),
        ).virtualFile
        // the other file is read from its stubs: its AST is never loaded
        PsiManagerEx.getInstanceEx(project).dropPsiCaches()
        val noAst = Disposer.newDisposable(testRootDisposable)
        PsiManagerEx.getInstanceEx(project).setAssertOnFileLoadingFilter(VirtualFileFilter { it != current }, noAst)
        val file = psiManager.findFile(current) as CSharpFile
        val colors = NativeCSharpSemanticColors.colors(file).map { (range, key) -> "${range.substring(file.text)}:${short(key)}" }
        Disposer.dispose(noAst)
        assertEquals(
            listOf(
                "SemSolution:NAMESPACE", "Model:NAMESPACE",
                // `using static A.B.C`: `A.B` a namespace by the name resolution of layer 11a (0.1.57), `C` the type
                "SemSolution:NAMESPACE", "Model:NAMESPACE", "SemRegistry:STATIC_CLASS",
                "SemSolution:NAMESPACE", "Model:NAMESPACE",
                "SemRegistry:STATIC_CLASS", "Touch:STATIC_METHOD_DECLARATION", "Reset:STATIC_METHOD_CALL", "Count:STATIC_FIELD", "Changed:EVENT",
                "SemSolution:NAMESPACE",
                "Sem:ATTRIBUTE",
                "SemOrder:CLASS", "SemBase:CLASS", "SemRepository:INTERFACE",
                "SemState:ENUM", "state:FIELD", "SemState:ENUM", "Open:CONSTANT",
                "SemMoney:STRUCT", "Total:METHOD_DECLARATION", "SemMoney:STRUCT", "Amount:PROPERTY",
                "Go:METHOD_DECLARATION", "_ticks:FIELD", "Tick:METHOD_CALL", "Prefix:STATIC_PROPERTY", "SemRegistry:STATIC_CLASS", "Reset:STATIC_METHOD_CALL",
                "line:LOCAL_VARIABLE", "SemLine:RECORD",
            ),
            colors,
        )
    }

    /** What syntax and stubs cannot tell keeps no color; a name where only a type can stand gets the coarse color of types. */
    fun testUnresolvedNamesKeepNoColor() {
        assertEquals(
            listOf("SemPlain:CLASS", "Main:STATIC_METHOD_DECLARATION", "args:PARAMETER", "List:TYPE", "list:LOCAL_VARIABLE", "List:TYPE", "list:LOCAL_VARIABLE", "args:PARAMETER", "StringBuilder:TYPE"),
            colors(
                "SemPlain/Plain.cs",
                """
                class SemPlain
                {
                    static void Main(string[] args)
                    {
                        List<int> list = new List<int>();
                        Console.WriteLine(list.Count + args.Length);
                        var b = default(System.Text.StringBuilder);
                    }
                }
                """,
            ).filter { !it.startsWith("b:") },
        )
    }

    fun testBrokenCodeColorsWhatItCan() {
        val colors = colors(
            "SemBroken/Broken.cs",
            """
            class SemBroken
            {
                void M(int a)
                {
                    var x = a +
                    if (x > ) { x = ; }
                    Undefined(
                }
                int Field
            """,
        )
        assertTrue(colors.toString(), colors.containsAll(listOf("SemBroken:CLASS", "M:METHOD_DECLARATION", "a:PARAMETER", "x:MUTABLE_LOCAL_VARIABLE")))
    }

    fun testScopesForReuse() {
        val file = myFixture.addFileToProject("SemScopes/Scopes.cs", "class SemScopes { void M(int p) { int v = p; v++; } }") as CSharpFile
        val scopes = NativeCSharpScopes.of(file)
        assertEquals(listOf("p:PARAMETER:1", "v:LOCAL:1"), scopes.symbols.map { "${it.name}:${it.kind}:${it.references.size}" })
        assertTrue(scopes.symbols.single { it.kind == LocalSymbolKind.LOCAL }.isWritten)
        assertSame("cached until the file changes", scopes, NativeCSharpScopes.of(file))
    }

    /** The scenario of the playground says what the Built-in colors are: they are. */
    fun testPlaygroundScenario() {
        val root = java.io.File("debug-playground/Console/Editor")
        myFixture.addFileToProject("SemPlayground/SemanticColorsPart.cs", root.resolve("SemanticColorsPart.cs").readText())
        val colors = colors("SemPlayground/SemanticColors.cs", root.resolve("SemanticColors.cs").readText()).toSet()
        val expected = listOf(
            "ColorOrder:CLASS", "customer:PRIMARY_CONSTRUCTOR_PARAMETER", "ColorBase:CLASS", "IColorShape:INTERFACE", "MaxLines:CONSTANT",
            "_created:STATIC_FIELD", "_lines:FIELD", "Title:PROPERTY", "Empty:STATIC_PROPERTY", "Changed:EVENT", "Area:METHOD_DECLARATION",
            "Create:STATIC_METHOD_DECLARATION", "Describe:METHOD_DECLARATION", "ColorKind:ENUM", "ColorPoint:RECORD_STRUCT", "ColorHandler:DELEGATE",
            "ColorLine:RECORD", "Small:CONSTANT", "Large:CONSTANT",
            "builder:LOCAL_VARIABLE", "total:MUTABLE_LOCAL_VARIABLE", "repeat:PARAMETER", "format:PARAMETER", "TFormat:TYPE_PARAMETER",
            "Indent:LOCAL_FUNCTION", "line:LOCAL_VARIABLE", "again:LABEL",
            "Tick:METHOD_CALL", "_ticks:FIELD", "Reset:STATIC_METHOD_CALL", "Count:STATIC_FIELD", "count:PARAMETER", "twice:LOCAL_VARIABLE", "title:LOCAL_VARIABLE",
        )
        assertEquals(emptyList<String>(), expected.filter { it !in colors })
        // what only the server knows stays plain
        assertTrue(colors.none { it.startsWith("Console:") || it.startsWith("WriteLine:") || it.startsWith("total:LOCAL_VARIABLE") || it.startsWith("title:PROPERTY") })
    }

    /** NATIVE: the native annotator colors and the heuristics step aside; ROSLYN: the heuristics color with the coarse keys, as before. */
    fun testTheSwitchChoosesTheAnnotator() {
        settings.state.enabled = true // ROSLYN is the server's path: the server is off by default since 0.1.76
        myFixture.configureByText("SemSwitch.cs", "class SemSwitch { static void Main() { int n = 1; n++; } }")
        fun highlighted() = myFixture.doHighlighting().mapNotNull { info -> info.forcedTextAttributesKey?.let { "${info.text}:${short(it)}" } }
        assertEquals(listOf("SemSwitch:CLASS", "Main:STATIC_METHOD_DECLARATION", "n:MUTABLE_LOCAL_VARIABLE", "n:MUTABLE_LOCAL_VARIABLE"), highlighted())
        settings.setSource(CSharpFeature.SEMANTIC_COLORS, CSharpFeatureSource.ROSLYN)
        com.intellij.codeInsight.daemon.DaemonCodeAnalyzer.getInstance(project).restart(myFixture.file)
        assertEquals(listOf("SemSwitch:TYPE", "Main:METHOD"), highlighted())
    }

    /** Every key is on the color page, in its demo text, and the bundled schemes give the keys Rider's look. */
    fun testColorPageAndSchemes() {
        val page = CSharpColorSettingsPage()
        val described = page.attributeDescriptors.map { it.key }.toSet()
        val tagged = page.additionalHighlightingTagToDescriptorMap!!.values.toSet()
        for (key in CSharpColors.ALL) {
            assertTrue(key.externalName, key in described)
            assertTrue(key.externalName, key in tagged)
            val tag = page.additionalHighlightingTagToDescriptorMap!!.entries.first { it.value == key }.key
            assertTrue("<$tag> in the demo", page.demoText.contains("<$tag>"))
        }
        val dark = checkNotNull(EditorColorsManager.getInstance().getScheme("Darcula"))
        assertEquals(0xE1BFFF, dark.getAttributes(CSharpColors.STRUCT).foregroundColor.rgb and 0xFFFFFF)
        assertEquals(0xE1BFFF, dark.getAttributes(CSharpColors.RECORD_STRUCT).foregroundColor.rgb and 0xFFFFFF)
        assertEquals(0xC191FF, dark.getAttributes(CSharpColors.STATIC_CLASS).foregroundColor.rgb and 0xFFFFFF)
        assertEquals(0xED94C0, dark.getAttributes(CSharpColors.EVENT).foregroundColor.rgb and 0xFFFFFF)
        assertEquals(java.awt.Font.BOLD, dark.getAttributes(CSharpColors.CONSTANT).fontType)
        assertEquals(EffectType.LINE_UNDERSCORE, dark.getAttributes(CSharpColors.MUTABLE_LOCAL_VARIABLE).effectType)
        val light = checkNotNull(EditorColorsManager.getInstance().getScheme("Default"))
        assertEquals("a method call falls back to the coarse color", 0x00855F, light.getAttributes(CSharpColors.STATIC_METHOD_CALL).foregroundColor.rgb and 0xFFFFFF)
        assertEquals(0x6B2FBA, light.getAttributes(CSharpColors.NAMESPACE).foregroundColor.rgb and 0xFFFFFF)
    }
}
