package io.github.dotnetsupport

import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.dotnetsupport.lang.CSharpFeature
import io.github.dotnetsupport.lang.CSharpFeatureSource
import io.github.dotnetsupport.lang.CSharpSyntaxTrees
import io.github.dotnetsupport.solution.ActiveSolution
import io.github.dotnetsupport.solution.SolutionService
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

    /** The errors of [text] as `line: text under it -> CSxxxx: message`. */
    private fun errors(text: String): List<String> {
        myFixture.configureByText("RazorCorpus${files++}.cs", text.trimIndent())
        val document = myFixture.editor.document.text
        return myFixture.doHighlighting(HighlightSeverity.ERROR).filter { it.severity == HighlightSeverity.ERROR && it.description?.startsWith("CS") == true }
            .sortedBy(HighlightInfo::getStartOffset)
            .map { "${document.substring(0, it.startOffset).count { c -> c == '\n' } + 1}: " + document.substring(it.startOffset, it.endOffset) + " -> " + it.description }
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

    /** MudBlazor's SearchService: a span indexed by a `Range` is a slice of it (§12.8.12.3), not an element. */
    fun testRangeIndexerOfASpan() {
        assertEmpty(errors("""
            using System;
            class Search
            {
                static int Score(ReadOnlySpan<char> query, ReadOnlySpan<Range> ranges)
                {
                    var total = 0;
                    foreach (var r in ranges)
                    {
                        var token = query[r];
                        if (token.IsEmpty) continue;
                        total += token.Length + query[1..].Length + "text"[..2].Length;
                    }
                    return total;
                }
            }
        """))
    }

    /** MudBlazor: the base class of a partial component is in the part the Razor generator makes (`@inherits`); this part lists interfaces. */
    fun testBaseClassFromAnotherPart() {
        assertEmpty(errors("""
            using System.Threading.Tasks;
            class ComponentBase { protected virtual Task OnAfterRenderAsync(bool firstRender) => Task.CompletedTask; }
            interface IObserver { }
            partial class Provider : IObserver
            {
                protected override async Task OnAfterRenderAsync(bool firstRender) => await base.OnAfterRenderAsync(firstRender);
            }
            partial class Provider : ComponentBase { }
        """))
    }

    /** What the Razor generator writes for `@onclick="(args) => _table?.Expand()"`: the lambda picks the `Action<T>` overload of Create. */
    fun testEventCallbackFactoryWithALambda() {
        assertEmpty(errors("""
            using System;
            using System.Threading.Tasks;
            class Table { public void Expand() { } }
            readonly struct EventCallback { }
            readonly struct EventCallback<T> { }
            sealed class EventCallbackFactory
            {
                public EventCallback<T> Create<T>(object receiver, EventCallback callback) => default;
                public EventCallback<T> Create<T>(object receiver, EventCallback<T> callback) => default;
                public EventCallback<T> Create<T>(object receiver, Action callback) => default;
                public EventCallback<T> Create<T>(object receiver, Action<T> callback) => default;
                public EventCallback<T> Create<T>(object receiver, Func<Task> callback) => default;
                public EventCallback<T> Create<T>(object receiver, Func<T, Task> callback) => default;
            }
            sealed class PlainFactory
            {
                public EventCallback<int> Create(object receiver, EventCallback callback) => default;
                public EventCallback<int> Create(object receiver, Action callback) => default;
                public EventCallback<int> Create(object receiver, Action<int> callback) => default;
                public EventCallback<int> Create(object receiver, Func<Task> callback) => default;
                public EventCallback<int> Create(object receiver, Func<int, Task> callback) => default;
            }
            class Page
            {
                Table? _table;
                object P(PlainFactory plain) => plain.Create(this, (args) => _table!.Expand());
                readonly EventCallbackFactory factory = new();
                object M() => factory.Create<int>(this, (args) => _table?.Expand());
                object N() => factory.Create<int>(this, (args) => _table!.Expand());
                object O() => factory.Create<int>(this, () => _table?.Expand());
            }
        """))
    }

    /** nopCommerce's cache: `GetAsync<T>(key, Func<Task<T>>)` over `GetAsync<T>(key, Func<T>)` for an async lambda (more specific, §12.6.4.3). */
    fun testAsyncLambdaPicksTheTaskOverload() {
        assertEmpty(errors("""
            using System;
            using System.Collections.Generic;
            using System.Threading.Tasks;
            interface ICache
            {
                Task<T> GetAsync<T>(string key, Func<Task<T>> acquire);
                Task<T> GetAsync<T>(string key, Func<T> acquire);
            }
            class Service
            {
                readonly ICache _cache = null!;
                async Task<Dictionary<int, int>> CountsAsync() => await _cache.GetAsync("k", async () =>
                {
                    await Task.Yield();
                    return new Dictionary<int, int>();
                });
                async Task<int[]> IdsAsync() => await _cache.GetAsync("k", async () => await Task.FromResult(new int[0]));
                async Task<Dictionary<int, int>> DelayAsync() => await _cache.GetAsync("k", async () => { await Task.Delay(1); return new Dictionary<int, int>(); });
                async Task<Dictionary<int, int>> PlainAsync() => await _cache.GetAsync("k", async () => { return new Dictionary<int, int>(); });
                async Task<Dictionary<int, int>> NoAwaitAsync() => await _cache.GetAsync("k", () => Task.FromResult(new Dictionary<int, int>()));
            }
        """))
    }

    /**
     * MudBlazor's tests: a type of a referenced project comes both from its sources and from its built assembly (the IDE indexes the
     * outputs of referenced projects too) — `KeyInterceptorService` of an extension method of the built `UnitTests.Shared` to a parameter
     * written in the sources. A built project is its sources: they are one type. Here the fixture assembly plays the built project.
     */
    fun testATypeOfABuiltProjectIsItsSourceType() {
        assertEmpty(errors("""
            namespace Fixture
            {
                public abstract class Shape { }
            }
            class User
            {
                static void Take(Fixture.Shape shape) { }
                void M(Fixture.Circle circle) => Take(circle);
            }
        """))
    }

    /** MudBlazor's KeyObserverTests: `KeyUp(null)` takes the delegate returning a value over the `void` one (Roslyn's better conversion target). */
    fun testNullGoesToTheDelegateReturningAValue() {
        assertEmpty(errors("""
            using System;
            using System.Threading.Tasks;
            class Args { }
            static class KeyObserver
            {
                public static int KeyUp(Func<Args, Task>? lambda) => 1;
                public static string KeyUp(Action<Args>? lambda) => "";
                static int M() => KeyUp(null);
            }
        """))
    }

    /** MudBlazor's tests: AwesomeAssertions' `Should()` of an `IDictionary<K, V>` is the dictionary one, among overloads that cannot apply. */
    fun testShouldOfADictionary() {
        assertEmpty(errors("""
            using System;
            using System.Collections.Generic;
            class ObjectAssertions { }
            class EnumAssertions { }
            class CollectionAssertions<T> { }
            class DictionaryAssertions<TKey, TValue> { public void NotBeEmpty() { } }
            static class AssertionExtensions
            {
                public static ObjectAssertions Should(this object actualValue) => new();
                public static CollectionAssertions<T> Should<T>(this IEnumerable<T> actualValue) => new();
                public static CollectionAssertions<string> Should(this IEnumerable<string> actualValue) => new();
                public static DictionaryAssertions<TKey, TValue> Should<TKey, TValue>(this IDictionary<TKey, TValue> actualValue) => new();
                public static DictionaryAssertions<TKey, TValue> Should<TKey, TValue>(this IEnumerable<KeyValuePair<TKey, TValue>> actualValue) => new();
                public static DictionaryAssertions<TKey, TValue> Should<TCollection, TKey, TValue>(this TCollection actualValue)
                    where TCollection : IEnumerable<KeyValuePair<TKey, TValue>> => new();
            }
            static class EnumAssertionsExtensions
            {
                public static EnumAssertions Should<TEnum>(this TEnum @enum) where TEnum : struct, Enum => new();
            }
            delegate void SizeChanged(IDictionary<string, int> changes);
            class Tests
            {
                event SizeChanged? OnResized;
                IDictionary<int, string> Observers => new Dictionary<int, string>();
                void M()
                {
                    OnResized += (sizeChanges) => sizeChanges.Should().NotBeEmpty();
                    Observers.Should().NotBeEmpty();
                }
            }
        """))
    }

    /** MudBlazor's SearchServiceTests: `Should()` of an `IReadOnlyList<KeyValuePair<K, V>>` is the one of `IEnumerable<KeyValuePair<TKey, TValue>>`. */
    fun testShouldOfAListOfPairs() {
        val found = errors("""
            using System;
            using System.Collections.Generic;
            class ObjectAssertions { }
            class EnumAssertions { }
            class CollectionAssertions<T> { public void NotBeEmpty() { } }
            class DictionaryAssertions<TKey, TValue> { public void NotBeEmpty() { } }
            static class AssertionExtensions
            {
                public static ObjectAssertions Should(this object actualValue) => new();
                public static CollectionAssertions<T> Should<T>(this IEnumerable<T> actualValue) => new();
                public static CollectionAssertions<string> Should(this IEnumerable<string> actualValue) => new();
                public static DictionaryAssertions<TKey, TValue> Should<TKey, TValue>(this IDictionary<TKey, TValue> actualValue) => new();
                public static DictionaryAssertions<TKey, TValue> Should<TKey, TValue>(this IEnumerable<KeyValuePair<TKey, TValue>> actualValue) => new();
                public static DictionaryAssertions<TKey, TValue> Should<TCollection, TKey, TValue>(this TCollection actualValue)
                    where TCollection : IEnumerable<KeyValuePair<TKey, TValue>> => new();
            }
            static class EnumAssertionsExtensions
            {
                public static EnumAssertions Should<TEnum>(this TEnum @enum) where TEnum : struct, Enum => new();
            }
            interface ISearchService { IReadOnlyList<T> Search<T>(IEnumerable<T> items, Func<T, IEnumerable<string>> getKeywords, string query) where T : notnull; }
            class Tests
            {
                static readonly ISearchService Service = null!;
                static readonly IReadOnlyList<KeyValuePair<string, string>> SyntheticIndex = [new("button", "Button")];
                void M(string query)
                {
                    var results = Service.Search(SyntheticIndex, e => [e.Key], query);
                    results.Should().NotBeEmpty();
                    results.Should().Nope();
                }
            }
            // nopCommerce's Nop.Tests: `request.Headers.Should().Contain(h => …)` — a class implementing `IEnumerable<KeyValuePair<K, V>>`
            class Headers : IEnumerable<KeyValuePair<string, IEnumerable<string>>>
            {
                public IEnumerator<KeyValuePair<string, IEnumerable<string>>> GetEnumerator() => null!;
                System.Collections.IEnumerator System.Collections.IEnumerable.GetEnumerator() => null!;
            }
            class HeaderTests
            {
                void M(Headers headers) => headers.Should().Nope();
            }
        """)
        // the control: the answer is the dictionary assertions, so a member they lack is an error on them
        assertEquals(found.toString(), 2, found.size)
        assertTrue(found[0], "CS1061" in found[0] && "'DictionaryAssertions<string, string>'" in found[0])
        assertTrue(found[1], "CS1061" in found[1] && "'DictionaryAssertions<string, IEnumerable<string>>'" in found[1])
    }

    /** MudBlazor.Analyzers: an extension method given to a delegate (`_resolve = compilation.GetBestTypeByMetadataName`) is a method group. */
    fun testExtensionMethodGroupToADelegate() {
        assertEmpty(errors("""
            using System;
            class Compilation { }
            class Symbol { }
            static class CompilationExtensions
            {
                public static Symbol? GetBestTypeByMetadataName(this Compilation compilation, string name) => null;
            }
            class Resolver
            {
                private readonly Func<string, Symbol?> _resolve;
                Resolver(Compilation compilation) { _resolve = compilation.GetBestTypeByMetadataName; }
            }
        """))
    }

    /**
     * MudBlazor.Docs' `struct T : INumber<T>`: `IUtf8SpanFormattable.TryFormat` is implemented by default, explicitly, in `INumberBase<T>` —
     * the index of assemblies keeps such members since format 6 (the fixture's `INumberLike`), so no CS0535; a class deriving from a library
     * class that implements an interface explicitly owes nothing either.
     */
    fun testExplicitDefaultImplementationOfALibraryInterface() {
        assertEmpty(errors("""
            using Fixture;
            struct Number : INumberLike { }
            class Named : ExplicitBase, INamed { }
        """))
    }

    /**
     * eShop's AppHost: a `static class Extensions` of the global namespace in a project this one does not reference is not seen, so
     * `Extensions` is the one `using eShop.AppHost` brings; with a reference to that project the global one wins, as in C# — CS0117.
     */
    /**
     * The solution bounds what a project sees (Open Solution, 0.1.243): references the project file does not tell (`$(Mystery)\…`)
     * open every project of the active solution and none outside it; `$(SolutionDir)` is the folder of that solution.
     */
    fun testTheSolutionBoundsWhatAProjectWithUnknownReferencesSees() {
        fun csproj(path: String, reference: String?) = myFixture.addFileToProject(path,
            "<Project Sdk=\"Microsoft.NET.Sdk\"><PropertyGroup><TargetFramework>net10.0</TargetFramework></PropertyGroup>" +
                (reference?.let { "<ItemGroup><ProjectReference Include=\"$it\" /></ItemGroup>" } ?: "") + "</Project>")
        fun slnProject(name: String) = "Project(\"{FAE04EC0-301F-11D3-BF4B-00C04F79EFBC}\") = \"$name\", \"$name\\\\$name.csproj\", \"{00000000-0000-0000-0000-00000000000${name[0]}}\"\nEndProject\n"
        val sln = myFixture.addFileToProject("Ctx/Ctx.sln", "Microsoft Visual Studio Solution File, Format Version 12.00\n" + listOf("A", "B", "C", "E").joinToString("") { slnProject(it) }).virtualFile
        csproj("Ctx/A/A.csproj", "\$(Mystery)\\X\\X.csproj")
        csproj("Ctx/B/B.csproj", null)
        myFixture.addFileToProject("Ctx/B/BType.cs", "namespace InB { public class BType { } }")
        csproj("Ctx/C/C.csproj", null)
        myFixture.addFileToProject("Ctx/C/CType.cs", "namespace InC { public class CType { } }")
        csproj("Ctx/D/D.csproj", null)   // not in the solution
        myFixture.addFileToProject("Ctx/D/DType.cs", "namespace InD { public class DType { } }")
        csproj("Ctx/E/E.csproj", "\$(SolutionDir)\\B\\B.csproj")
        val solutions = SolutionService.getInstance(project)
        solutions.solutionFilesChanged()
        ActiveSolution.getInstance(project).choose(sln)
        try {
            assertEquals(listOf(sln), solutions.solutionFiles())
            val bounded = CSharpSemanticEnvironment.solutionProjects(project)
            assertEquals(bounded.toString(), setOf("A", "B", "C", "E").map { "${sln.parent.path}/$it/$it.csproj".lowercase() }.toSet(), bounded)
            fun errorsIn(path: String, text: String): List<String> {
                myFixture.configureFromExistingVirtualFile(myFixture.addFileToProject(path, text).virtualFile)
                return myFixture.doHighlighting(HighlightSeverity.ERROR).filter { it.description?.startsWith("CS") == true }.map { it.description!!.substringBefore(':') }
            }
            // A: references unknown — B and C of the solution are seen, D outside it is not (`using InD;` and `DType`)
            val use = myFixture.addFileToProject("Ctx/A/Use.cs", "using InB;\nusing InC;\nusing InD;\nclass U : BType { CType c; DType d; }")
            val aProject = io.github.dotnetsupport.msbuild.DotNetProjects.findOwningProject(use.virtualFile)!!
            assertEquals("A.csproj", aProject.name)
            assertNull(CSharpSemanticEnvironment.referencedProjects(project, aProject))
            assertTrue(CSharpSemanticEnvironment.sees(use, myFixture.findFileInTempDir("Ctx/B/BType.cs")))
            assertFalse(CSharpSemanticEnvironment.sees(use, myFixture.findFileInTempDir("Ctx/D/DType.cs")))
            myFixture.configureFromExistingVirtualFile(use.virtualFile)
            val fromA = myFixture.doHighlighting(HighlightSeverity.ERROR).filter { it.description?.startsWith("CS") == true }.map { it.description!!.substringBefore(':') }
            assertEquals(fromA.toString(), listOf("CS0246", "CS0246"), fromA)
            val eProject = myFixture.findFileInTempDir("Ctx/E/E.csproj")
            assertEquals(setOf("${sln.parent.path}/B/B.csproj".lowercase()), CSharpSemanticEnvironment.referencedProjects(project, eProject))
            // E: `$(SolutionDir)\B\B.csproj` resolves — B is seen, C of the same solution is not
            val fromE = errorsIn("Ctx/E/Use.cs", "using InB;\nusing InC;\nclass V : BType { CType c; }")
            assertEquals(fromE.toString(), listOf("CS0246", "CS0246"), fromE)
            assertEmpty(errorsIn("Ctx/E/Fine.cs", "using InB;\nclass W : BType { }"))
        } finally {
            ActiveSolution.getInstance(project).loadState(ActiveSolution.Choice())
            solutions.solutionFilesChanged()
        }
    }

    fun testATypeOfAProjectNotReferencedIsNotSeen() {
        myFixture.addFileToProject("Catalog/Catalog.csproj", "<Project Sdk=\"Microsoft.NET.Sdk\"><PropertyGroup><TargetFramework>net10.0</TargetFramework></PropertyGroup></Project>")
        myFixture.addFileToProject("Catalog/Extensions.cs", "public static class Extensions { public static void AddCatalog() { } }")
        myFixture.addFileToProject("Catalog/Seeding.cs", "namespace Seeding { public interface IDbSeeder { } public static class SeedingExtensions { public static int AddMigration(this string s) => 1; } }")
        myFixture.addFileToProject("Host/Host.csproj", "<Project Sdk=\"Microsoft.NET.Sdk\"><PropertyGroup><TargetFramework>net10.0</TargetFramework></PropertyGroup></Project>")
        myFixture.addFileToProject("Host/Extensions.cs", "namespace eShop.Host { internal static class Extensions { public static bool IsFoundryEnabled() => true; } }")
        myFixture.addFileToProject("Referencing/Referencing.csproj",
            "<Project Sdk=\"Microsoft.NET.Sdk\"><ItemGroup><ProjectReference Include=\"..\\Catalog\\Catalog.csproj\" /></ItemGroup></Project>")
        myFixture.addFileToProject("Referencing/Extensions.cs", "namespace eShop.Referencing { internal static class Extensions { public static bool IsFoundryEnabled() => true; } }")
        fun errorsIn(path: String, text: String): List<String> {
            myFixture.configureFromExistingVirtualFile(myFixture.addFileToProject(path, text).virtualFile)
            return myFixture.doHighlighting(HighlightSeverity.ERROR).filter { it.description?.startsWith("CS") == true }.map { it.description!!.substringBefore(':') }
        }
        assertEmpty(errorsIn("Host/AppHost.cs", "using eShop.Host;\nclass P { bool M() => Extensions.IsFoundryEnabled(); }"))
        val found = errorsIn("Referencing/AppHost.cs", "using eShop.Referencing;\nclass P { bool M() => Extensions.IsFoundryEnabled(); }")
        assertEquals(found.toString(), listOf("CS0117"), found)
        // a type and an extension method of the unreferenced project are not found either (CS0246, CS1061); with the reference they are;
        // the namespace `Seeding` is declared by that project only, so `using Seeding;` is CS0246 as well (0.1.240), as the compiler says
        val unseenType = errorsIn("Host/Seeded.cs", "using Seeding;\nclass Q : IDbSeeder { }")
        assertEquals(unseenType.toString(), listOf("CS0246", "CS0246"), unseenType)
        val unseenExtension = errorsIn("Host/Migrated.cs", "using Seeding;\nclass R { int M() => \"x\".AddMigration(); }")
        assertEquals(unseenExtension.toString(), listOf("CS0246", "CS1061"), unseenExtension)
        assertEmpty(errorsIn("Referencing/Seeded.cs", "using Seeding;\nclass Q : IDbSeeder { int M() => \"x\".AddMigration(); }"))
    }

    /**
     * nopCommerce's PayPal plugin: the base `Order` of `GetOrderResponse` is resolved by the usings of the file declaring it — not by those of
     * the file using it, where `Order` would be the shop's own `Order` (an `int Id`, CS0029 to `string`).
     */
    fun testBaseTypeResolvedInItsOwnFile() {
        myFixture.addFileToProject("Shop/Order.cs", "namespace Shop.Orders { public class Order { public int Id { get; set; } } }")
        myFixture.addFileToProject("Api/Order.cs", "namespace Api.Models { public class Order { public string Id { get; set; } } }")
        myFixture.addFileToProject("Api/GetOrderResponse.cs", "using Api.Models;\nnamespace Api.Orders;\npublic class GetOrderResponse : Order;")
        assertEmpty(errors("""
            using System;
            using System.Threading.Tasks;
            using Shop.Orders;
            using Api.Orders;
            class Manager
            {
                static Task<T> HandleAsync<T>(Func<Task<T>> function) => function();
                static Task<TResponse> RequestAsync<TRequest, TResponse>(TRequest request) => Task.FromResult(default(TResponse)!);
                Task<bool> M() => HandleAsync(async () =>
                {
                    var order = await RequestAsync<string, GetOrderResponse>("id");
                    string id = order.Id;
                    return true;
                });
            }
        """))
    }
}
