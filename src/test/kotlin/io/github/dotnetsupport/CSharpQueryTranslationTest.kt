package io.github.dotnetsupport

import com.intellij.codeInsight.CodeInsightSettings
import com.intellij.openapi.util.TextRange
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.dotnetsupport.csharp.lang.CSharpFile
import io.github.dotnetsupport.index.AssemblyDocs
import io.github.dotnetsupport.index.AssemblyIndex
import io.github.dotnetsupport.index.AssemblyIndexSet
import io.github.dotnetsupport.lang.CSharpFeature
import io.github.dotnetsupport.lang.CSharpFeatureSource
import io.github.dotnetsupport.lang.CSharpSyntaxTrees
import io.github.dotnetsupport.lang.NativeCSharpCompletion
import io.github.dotnetsupport.lang.NativeCSharpDocumentation
import io.github.dotnetsupport.lang.semantic.CSharpSemanticChecks
import io.github.dotnetsupport.lang.semantic.CSharpSemanticEnvironment
import io.github.dotnetsupport.lang.semantic.CSharpSemanticSession
import io.github.dotnetsupport.lang.semantic.NativeCSharpSemanticModel
import io.github.dotnetsupport.lsp.RoslynLanguageServerSettings

/**
 * Query expressions translated to method calls (C# §12.20.3, 0.1.80, `CSharpQueryTranslation`): range variables typed by the lambdas of the
 * methods the source type really has — `Queryable` for an `IQueryable<T>` (declared here in the sources: the index fixtures have no
 * System.Linq.Queryable / System.Linq.Expressions), an EF Core-like `DbSet<T>`, own types with instance or extension `Select` / `Where`,
 * `Enumerable` with transparent identifiers, joins, groups and continuations; method-syntax lambdas against `Expression<Func<…>>`;
 * completion, quick documentation and semantic errors inside queries.
 */
class CSharpQueryTranslationTest : BasePlatformTestCase() {
    private val settings get() = RoslynLanguageServerSettings.getInstance()
    private var autocomplete = true

    override fun setUp() {
        super.setUp()
        CSharpSyntaxTrees.forceNativeTreeForTests(true)
        CSharpSemanticEnvironment.setAssembliesForTests { ASSEMBLIES }
        settings.setSource(CSharpFeature.COMPLETION, CSharpFeatureSource.NATIVE)
        settings.setSource(CSharpFeature.DOCUMENTATION, CSharpFeatureSource.NATIVE)
        autocomplete = CodeInsightSettings.getInstance().AUTOCOMPLETE_ON_CODE_COMPLETION
        CodeInsightSettings.getInstance().AUTOCOMPLETE_ON_CODE_COMPLETION = false
        myFixture.addFileToProject("queries/QueryableApi.cs", QUERYABLE)
    }

    override fun tearDown() {
        try {
            CodeInsightSettings.getInstance().AUTOCOMPLETE_ON_CODE_COMPLETION = autocomplete
            settings.state.features = mutableMapOf()
            settings.state.enabled = RoslynLanguageServerSettings.ENABLED_BY_DEFAULT
            CSharpSemanticEnvironment.setAssembliesForTests(null)
            CSharpSyntaxTrees.forceNativeTreeForTests(null)
        } catch (e: Throwable) {
            addSuppressedException(e)
        } finally {
            super.tearDown()
        }
    }

    private fun source(body: String, members: String = ""): String = """
        using System;
        using System.Collections.Generic;
        using System.Linq;
        using Shop;

        class Sample
        {
        ${members.prependIndent("    ")}
            void Body(IQueryable<Order> orders, ShopContext db, List<int> numbers, string[] texts, Box<int> box, Maybe<string> maybe, List<Customer> customers)
            {
        ${body.prependIndent("        ")}
            }
        }
    """.trimIndent()

    /** The type of each marked expression `/*<*/e/*>*/` of [body], in order. */
    private fun types(body: String, members: String = ""): List<String?> {
        val f = myFixture.addFileToProject("queries/Q${counter++}.cs", source(body, members)) as CSharpFile
        val types = types(f, namesFirst = false)
        assertEquals("the answers do not depend on what is asked first", types, types(f, namesFirst = true))
        return types
    }

    private fun types(f: CSharpFile, namesFirst: Boolean): List<String?> {
        val model = NativeCSharpSemanticModel(CSharpSemanticSession(project))
        val text = f.text
        // as the semantic gate asks: every name of the file first, then the types
        if (namesFirst) for (offset in text.indices) if (text[offset].isLetter() && (offset == 0 || !text[offset - 1].isLetterOrDigit())) model.symbolAt(f, offset)
        val found = ArrayList<String?>()
        var at = text.indexOf(OPEN)
        while (at >= 0) {
            val start = at + OPEN.length
            val end = text.indexOf(CLOSE, start)
            found += model.typeOf(f, TextRange(start, end))?.display
            at = text.indexOf(OPEN, end)
        }
        assertFalse("no marks", found.isEmpty())
        return found
    }

    fun testQueryableSourceUsesQueryable() {
        assertEquals(
            listOf("System.Linq.IQueryable<string>", "Shop.Order", "System.Linq.IQueryable<Shop.Order>", "System.Linq.IOrderedQueryable<Shop.Order>", "decimal"),
            types("""
                var a = /*<*/from o in orders where o.Total > 1 select o.Name/*>*/;
                var b = from o in orders select /*<*/o/*>*/;
                var c = /*<*/from o in orders where o.Total > 1 select o/*>*/;
                var d = /*<*/from o in orders orderby o.Total descending, o.Name select o/*>*/;
                var e = from o in orders let t = o.Total * 2 select /*<*/t/*>*/;
            """.trimIndent()),
        )
    }

    fun testDbSetOfAContext() {
        assertEquals(
            listOf("System.Linq.IQueryable<int>", "Shop.Customer", "System.Linq.IQueryable<System.Linq.IGrouping<string, Shop.Order>>"),
            types("""
                var a = /*<*/from c in db.Customers where c.Name != null select c.Id/*>*/;
                var b = from c in db.Customers select /*<*/c/*>*/;
                var g = /*<*/from o in db.Orders group o by o.Name/*>*/;
            """.trimIndent()),
        )
    }

    fun testMethodSyntaxAgainstExpressionTrees() {
        assertEquals(
            listOf("System.Linq.IQueryable<Shop.Order>", "Shop.Order", "System.Linq.IQueryable<string>", "System.Linq.IQueryable<int>"),
            types("""
                var a = /*<*/orders.Where(o => o.Total > 1)/*>*/;
                var b = orders.Where(o => /*<*/o/*>*/.Total > 1);
                var c = /*<*/orders.Where(o => o.Total > 1).Select(o => o.Name)/*>*/;
                var d = /*<*/db.Customers.Select(c => c.Id)/*>*/;
            """.trimIndent()),
        )
    }

    fun testOwnTypesWithInstanceAndExtensionMethods() {
        assertEquals(
            listOf("Shop.Box<string>", "int", "Shop.Box<int>", "Shop.Maybe<int>", "string"),
            types("""
                var a = /*<*/from x in box where x > 0 select x.ToString()/*>*/;
                var b = from x in box select /*<*/x/*>*/;
                var c = /*<*/from x in box where x > 0 select x/*>*/;
                var d = /*<*/from s in maybe select s.Length/*>*/;
                var e = from s in maybe select /*<*/s/*>*/;
            """.trimIndent()),
        )
    }

    fun testTransparentIdentifiersJoinsAndContinuations() {
        assertEquals(
            listOf(
                "string", "int", "System.Collections.Generic.IEnumerable<int>",
                "Shop.Customer", "System.Collections.Generic.IEnumerable<Shop.Customer>", "System.Collections.Generic.IEnumerable<string>",
                "System.Collections.Generic.IEnumerable<int>", "object", "System.Collections.Generic.IEnumerable<string>",
            ),
            types("""
                var a = from n in numbers from s in texts let l = s.Length where l > n select /*<*/s/*>*/;
                var b = from n in numbers from s in texts let l = s.Length where l > n select /*<*/l/*>*/;
                var c = /*<*/from n in numbers from s in texts select n + s.Length/*>*/;
                var d = from n in numbers join c in customers on n equals c.Id select /*<*/c/*>*/;
                var e = from n in numbers join c in customers on n equals c.Id into matched select /*<*/matched/*>*/;
                var f = /*<*/from c in customers group c by c.Name into g select g.Key/*>*/;
                var h = /*<*/from n in numbers where n > 1 select n into m select m * 2/*>*/;
                var i = from object o in numbers select /*<*/o/*>*/;
                var j = /*<*/from n in numbers join c in customers on n equals c.Id select c.Name/*>*/;
            """.trimIndent()),
        )
    }

    fun testLetInAnExpressionBody() {
        assertEquals(
            listOf("Shop.Customer", "int", "System.Collections.Generic.IEnumerable<int>", "int"),
            types(
                "var x = Totals(customers);",
                // a range variable asked before its query: the query is translated again, not left unknown
                "static IEnumerable<int> First(IEnumerable<Customer> all) => from t in all let total = /*<*/t/*>*/.Id select /*<*/total/*>*/;\n" +
                "static IEnumerable<int> Totals(IEnumerable<Customer> all) => /*<*/from t in all let total = t.Id select total/*>*/;\n" +
                    "static IEnumerable<int> Again(IEnumerable<Customer> all) => from t in all let total = t.Id select /*<*/total/*>*/;",
            ),
        )
    }

    fun testWhatHasNoQueryMethodsStaysUnknown() {
        assertEquals(
            listOf(null, null),
            types("""
                var a = /*<*/from x in new NoQueries() select x/*>*/;
                var b = from x in new NoQueries() select /*<*/x/*>*/;
            """.trimIndent()),
        )
    }

    fun testCompletionInsideAQueryOfAnIQueryable() {
        myFixture.configureByText("Complete${counter++}.cs", source("var a = from o in db.Orders where o.<caret> select o;"))
        myFixture.completeBasic()
        val items = myFixture.lookupElements.orEmpty().filter { it.getUserData(NativeCSharpCompletion.NATIVE) == true }.map { it.lookupString }
        assertTrue(items.toString(), items.containsAll(listOf("Total", "Name")))
        myFixture.configureByText("Complete${counter++}.cs", source("var a = orders.Where(o => o.<caret>);"))
        myFixture.completeBasic()
        val lambda = myFixture.lookupElements.orEmpty().filter { it.getUserData(NativeCSharpCompletion.NATIVE) == true }.map { it.lookupString }
        assertTrue(lambda.toString(), lambda.containsAll(listOf("Total", "Name")))
    }

    fun testQuickDocOfARangeVariable() {
        val text = source("var a = from o in orders select o|.Name;")
        val offset = text.indexOf('|')
        val file = myFixture.configureByText("Doc${counter++}.cs", text.removeRange(offset, offset + 1)) as CSharpFile
        assertEquals("(range variable) Order o", NativeCSharpDocumentation.at(file, offset - 1)?.definition)
    }

    fun testErrorsInsideQueriesFollowTheMethods() {
        val f = myFixture.addFileToProject("queries/Errors${counter++}.cs", source("""
            var a = from o in orders where o.Totl > 1 select o.Name;
            var b = from c in db.Customers select c.Id;
        """.trimIndent())) as CSharpFile
        val problems = CSharpSemanticChecks(CSharpSemanticSession(project).resolver(f)).run().filter { it.isError }.map { it.text }
        assertEquals(listOf("CS1061: 'Order' does not contain a definition for 'Totl' and no accessible extension method 'Totl' accepting a first argument of type 'Order' could be found (are you missing a using directive or an assembly reference?)"), problems)
    }

    /** The semantic errors of [text] added as a file of the project, after [others]. */
    private fun problems(text: String, vararg others: Pair<String, String>): List<String> {
        for ((path, content) in others) myFixture.addFileToProject(path, content.trimIndent())
        val f = myFixture.addFileToProject("queries/Problems${counter++}.cs", text.trimIndent()) as CSharpFile
        return CSharpSemanticChecks(CSharpSemanticSession(project).resolver(f)).run().filter { it.isError }.map { it.text }
    }

    /** The shape of an EF Core handler of a real project: entities with an abstract base, an own `DbContext`, a DTO named as the base. */
    private fun stressTestProject(prefix: String, entities: List<String>): Array<Pair<String, String>> = (entities.mapIndexed { i, text ->
        "$prefix/Db/Entities/Entities$i.cs" to text.replace("NS.", "$prefix.")
    } + listOf(
        "$prefix/Common/Date.cs" to """
            namespace $prefix.Common;
            public readonly struct Date
            {
                public Date(int year, int month, int day) { }
                public static bool operator ==(Date a, Date b) => true;
                public static bool operator !=(Date a, Date b) => false;
                public override bool Equals(object? o) => true;
                public override int GetHashCode() => 0;
            }
        """,
        "$prefix/Requests/Models.cs" to """
            namespace $prefix.StressTest.Requests.Templates.Models;
            public class StressTest { public string Title { get; set; } = ""; }
            public class TemplateResult { public long Id { get; set; } public string MessageId { get; set; } = ""; }
        """,
        "$prefix/Requests/StressTest/GetStageResults.cs" to """
            using System.Collections.Generic;
            namespace $prefix.StressTest.Requests.StressTest;
            public class GetStageResults { public List<long> Ids { get; set; } = new(); }
        """,
        "$prefix/Db/DbContext.cs" to """
            using Shop;
            using $prefix.StressTest.Db.Entities;
            namespace $prefix.StressTest.Db;
            public class DbContext
            {
                public DbSet<DirectStressTest> DirectStressTests { get; } = null!;
                public DbSet<StageInstanceResult> StageInstanceResults { get; } = null!;
            }
        """,
    )).toTypedArray()

    private fun handler(prefix: String) = """
        using System.Collections.Generic;
        using System.Linq;
        using $prefix.Common;
        using $prefix.StressTest.Requests.StressTest;
        using $prefix.StressTest.Requests.Templates.Models;
        namespace $prefix.StressTest.Db.Handlers.StressTestHandlers;
        internal class GetStageResultsHandler
        {
            private readonly DbContext _context = null!;
            public IList<TemplateResult> Handle(GetStageResults request)
            {
                var test = _context
                    .DirectStressTests
                    .Where(x => x.BookDate == new Date(2002, 01, 01))
                    .Select(x => x.CalculationTypeId)
                    .ToList();
                var missing = _context.DirectStressTests.Select(x => x.Nope).ToList();
                return _context.StageInstanceResults.Where(x => request.Ids.Contains(x.Id)).Select(x => new TemplateResult { Id = x.Id, MessageId = x.MessageId }).ToList();
            }
        }
    """

    private fun nope(type: String) =
        "CS1061: '$type' does not contain a definition for 'Nope' and no accessible extension method 'Nope' accepting a first argument of type '$type' could be found (are you missing a using directive or an assembly reference?)"

    fun testMembersOfAnEntityBaseNamedAsADtoTheHandlerImports() {
        // the base `StressTest` of the entity is the entity's own, not the DTO of that name the handler's usings bring (E-190)
        val entities = """
            using NS.Common;
            namespace NS.StressTest.Db.Entities;
            public abstract class Entity<TKey> { public TKey Id { get; set; } = default!; }
            public abstract class StressTest : Entity<long>
            {
                public Date BookDate { get; set; }
                public int CalculationTypeId { get; set; }
            }
            public class DirectStressTest : StressTest { public string? Comment { get; set; } }
            public class StageInstanceResult : Entity<long> { public string MessageId { get; set; } = ""; }
        """
        assertEquals(listOf(nope("DirectStressTest")), problems(handler("Rst1"), *stressTestProject("Rst1", listOf(entities))))
    }

    fun testMembersOfAnEntityBaseImportedInsideTheNamespace() {
        // the base is seen through a `using` of the entity's namespace: the handler's own imports bring a DTO of that name
        val bases = """
            using NS.Common;
            namespace NS.StressTest.Db.Entities.Base;
            public abstract class Entity<TKey> { public TKey Id { get; set; } = default!; }
            public abstract class StressTest : Entity<long>
            {
                public Date BookDate { get; set; }
                public int CalculationTypeId { get; set; }
            }
        """
        val entities = """
            namespace NS.StressTest.Db.Entities;
            using NS.StressTest.Db.Entities.Base;
            public class DirectStressTest : StressTest { public string? Comment { get; set; } }
            public class StageInstanceResult : Entity<long> { public string MessageId { get; set; } = ""; }
        """
        assertEquals(listOf(nope("DirectStressTest")), problems(handler("Rst2"), *stressTestProject("Rst2", listOf(bases, entities))))
    }

    private companion object {
        const val OPEN = "/*<*/"
        const val CLOSE = "/*>*/"
        var counter = 0

        /** The part of System.Linq.Queryable / System.Linq.Expressions a query needs, and the types of the shop. */
        val QUERYABLE = """
            using System;
            using System.Collections;
            using System.Collections.Generic;

            namespace System.Linq.Expressions
            {
                public abstract class Expression { }
                public sealed class Expression<TDelegate> : Expression { }
            }

            namespace System.Linq
            {
                using System.Linq.Expressions;

                public interface IQueryable : IEnumerable { }
                public interface IQueryable<out T> : IEnumerable<T>, IQueryable { }
                public interface IOrderedQueryable<out T> : IQueryable<T> { }

                public static class Queryable
                {
                    public static IQueryable<TSource> Where<TSource>(this IQueryable<TSource> source, Expression<Func<TSource, bool>> predicate) => null!;
                    public static IQueryable<TSource> Where<TSource>(this IQueryable<TSource> source, Expression<Func<TSource, int, bool>> predicate) => null!;
                    public static IQueryable<TResult> Select<TSource, TResult>(this IQueryable<TSource> source, Expression<Func<TSource, TResult>> selector) => null!;
                    public static IQueryable<TResult> Select<TSource, TResult>(this IQueryable<TSource> source, Expression<Func<TSource, int, TResult>> selector) => null!;
                    public static IOrderedQueryable<TSource> OrderBy<TSource, TKey>(this IQueryable<TSource> source, Expression<Func<TSource, TKey>> keySelector) => null!;
                    public static IOrderedQueryable<TSource> OrderByDescending<TSource, TKey>(this IQueryable<TSource> source, Expression<Func<TSource, TKey>> keySelector) => null!;
                    public static IOrderedQueryable<TSource> ThenBy<TSource, TKey>(this IOrderedQueryable<TSource> source, Expression<Func<TSource, TKey>> keySelector) => null!;
                    public static IOrderedQueryable<TSource> ThenByDescending<TSource, TKey>(this IOrderedQueryable<TSource> source, Expression<Func<TSource, TKey>> keySelector) => null!;
                    public static IQueryable<IGrouping<TKey, TSource>> GroupBy<TSource, TKey>(this IQueryable<TSource> source, Expression<Func<TSource, TKey>> keySelector) => null!;
                }
            }

            namespace Shop
            {
                using System.Linq;

                public class Order { public string Name { get; set; } = ""; public decimal Total { get; set; } public int CustomerId { get; set; } }
                public class Customer { public int Id { get; set; } public string Name { get; set; } = ""; }

                /** As EF Core's DbSet<T>: a queryable of the entities. */
                public abstract class DbSet<TEntity> : IQueryable<TEntity> where TEntity : class
                {
                    public abstract IEnumerator<TEntity> GetEnumerator();
                    IEnumerator IEnumerable.GetEnumerator() => GetEnumerator();
                }

                public class ShopContext
                {
                    public DbSet<Customer> Customers { get; } = null!;
                    public DbSet<Order> Orders { get; } = null!;
                }

                /** A source of its own: instance query methods, no IEnumerable. */
                public class Box<T>
                {
                    public Box<TResult> Select<TResult>(Func<T, TResult> selector) => new Box<TResult>();
                    public Box<T> Where(Func<T, bool> predicate) => this;
                }

                /** Query methods as extension methods. */
                public class Maybe<T> { }

                public static class MaybeQueries
                {
                    public static Maybe<TResult> Select<T, TResult>(this Maybe<T> source, Func<T, TResult> selector) => new Maybe<TResult>();
                }

                public class NoQueries { }
            }
        """.trimIndent()

        private fun bytes(name: String): ByteArray? = CSharpQueryTranslationTest::class.java.getResourceAsStream("/index/$name")?.use { it.readBytes() }
        private fun fixture(name: String): AssemblyIndex = AssemblyIndex.read(bytes("$name.dnix")!!, bytes("$name.dnxd")?.let(AssemblyDocs::read))

        val ASSEMBLIES: AssemblyIndexSet by lazy {
            AssemblyIndexSet(listOf("IndexFixture", "System.Runtime", "System.Console", "System.Linq", "System.Collections").map(::fixture))
        }
    }
}
