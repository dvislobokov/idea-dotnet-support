package io.github.dotnetsupport

import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.dotnetsupport.lang.CSharpFeature
import io.github.dotnetsupport.lang.CSharpFeatureSource
import io.github.dotnetsupport.lang.CSharpSyntaxTrees
import io.github.dotnetsupport.lang.semantic.CSharpSemanticEnvironment
import io.github.dotnetsupport.lsp.RoslynLanguageServerSettings

/**
 * RAZOR_PLAN.md, R0b: the false errors of the core the Razor corpus showed (`.corpus/razor`, the harness with the generated files), each
 * class as the smallest code the compiler takes without a word — MudBlazor, nopCommerce, eShop and what the Razor generator makes.
 */
class RazorCorpusFalseErrorsTest : BasePlatformTestCase() {
    private val settings get() = RoslynLanguageServerSettings.getInstance()
    private var files = 0

    override fun setUp() {
        super.setUp()
        CSharpSyntaxTrees.forceNativeTreeForTests(true)
        CSharpSemanticEnvironment.setAssembliesForTests { CSharpUsingTypesTest.ASSEMBLIES }
        settings.setSource(CSharpFeature.DIAGNOSTICS, CSharpFeatureSource.NATIVE)
    }

    override fun tearDown() {
        try {
            settings.state.features = mutableMapOf()
            CSharpSemanticEnvironment.setAssembliesForTests(null)
            CSharpSyntaxTrees.forceNativeTreeForTests(null)
        } catch (e: Throwable) {
            addSuppressedException(e)
        } finally {
            super.tearDown()
        }
    }

    /** The errors of [text] as `text under it -> CSxxxx: message`. */
    private fun errors(text: String): List<String> {
        myFixture.configureByText("RazorCorpus${files++}.cs", text.trimIndent())
        val document = myFixture.editor.document.text
        return myFixture.doHighlighting(HighlightSeverity.ERROR).filter { it.severity == HighlightSeverity.ERROR && it.description?.startsWith("CS") == true }
            .sortedBy(HighlightInfo::getStartOffset).map { document.substring(it.startOffset, it.endOffset) + " -> " + it.description }
    }

    /** eShop's RabbitMQEventBus: a local function whose parameter is a base of the delegate's (contravariance of a method group). */
    fun testMethodGroupWithABaseParameter() {
        assertEmpty(errors("""
            using System;
            interface IProps { }
            class Props : IProps { }
            class Bus
            {
                static void Inject(Props props, string key, Action<Props, string, string> setter) => setter(props, key, key);
                void Publish()
                {
                    Inject(new Props(), "k", Set);
                    static void Set(IProps props, string key, string value) { }
                }
            }
        """))
    }

    /** MudBlazor's tests: a method returning Task given where a Func<Task> is expected. */
    fun testMethodGroupOfATaskMethod() {
        assertEmpty(errors("""
            using System;
            using System.Threading.Tasks;
            class Tests
            {
                static void Run(Func<Task> action) { }
                static void RunWith(Func<string, Task> action) { }
                Task TrackingAction() => Task.CompletedTask;
                Task NotificationAsync(string text) => Task.CompletedTask;
                void M()
                {
                    Run(TrackingAction);
                    RunWith(NotificationAsync);
                    Run(Local);
                    Task Local() => Task.CompletedTask;
                }
            }
        """))
    }

    /** MudBlazor's tests: `@Icons.Material` — a verbatim identifier is the identifier; the Razor generator writes `@Html`, `@Model`. */
    fun testVerbatimIdentifier() {
        assertEmpty(errors("""
            static class Icons { public static class Material { public const string Filled = "f"; } }
            static class Html { public static string Raw(string s) => s; }
            class C
            {
                string M(string @class, string plain, string @event) => @Icons.Material.Filled + @class + Html.Raw(@plain) + @Html.Raw(@class) + Icons.Material.Filled + @event;
            }
        """))
    }

    /** MudBlazor: `using static Outer;` brings the nested types of Outer too (`MyEnum` of a component, `GuidConverter`). */
    fun testUsingStaticBringsNestedTypes() {
        assertEmpty(errors("""
            using static Probe.Outer;
            namespace Probe
            {
                public class Outer
                {
                    public enum MyEnum { First }
                    internal sealed class Converter { }
                }
                class User
                {
                    MyEnum value = MyEnum.First;
                    Converter converter = new Converter();
                }
            }
        """))
    }

    /** nopCommerce: after `join x in … into xs` only `xs` is in scope, so a later `from x in xs` declares `x` anew. */
    fun testJoinIntoEndsTheScopeOfItsVariable() {
        assertEmpty(errors("""
            using System.Linq;
            class Product { public int Id; }
            class Localized { public int EntityId; public string Value = ""; }
            class C
            {
                object M(Product[] products, Localized[] localized) =>
                    from product in products
                    join localizedProperty in localized on product.Id equals localizedProperty.EntityId into localizedProperties
                    from localizedProperty in localizedProperties.DefaultIfEmpty()
                    select localizedProperty;
            }
        """))
    }

    /** MudBlazor.Docs: `int?` to a `decimal?` parameter is an implicit nullable conversion (§10.6.1). */
    fun testNullableNumericConversion() {
        assertEmpty(errors("""
            class C
            {
                static string Format(decimal? value) => "";
                static void Take(byte? value) { }
                string M(int? count)
                {
                    Take(4);
                    return Format(count);
                }
            }
        """))
    }
}
