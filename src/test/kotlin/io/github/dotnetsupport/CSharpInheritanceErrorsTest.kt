package io.github.dotnetsupport

import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.dotnetsupport.lang.CSharpFeature
import io.github.dotnetsupport.lang.CSharpFeatureSource
import io.github.dotnetsupport.lang.CSharpSyntaxTrees
import io.github.dotnetsupport.lang.semantic.CSharpSemanticEnvironment
import io.github.dotnetsupport.lsp.RoslynLanguageServerSettings

/**
 * The errors of [io.github.dotnetsupport.lang.semantic.CSharpInheritanceChecks]: overrides (CS0115, CS0506, CS0239, CS0507, CS0508),
 * abstract members and bodies (CS0513, CS0500, CS0501), base lists (CS0509, CS0527), `new` (CS0144, CS0712), CS0176 and CS0236, with
 * Roslyn's texts (taken from `dotnet build`) and spans, and silence where something involved is not surely known.
 */
class CSharpInheritanceErrorsTest : BasePlatformTestCase() {
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

    /** Each error of [codes] as `text under it -> CSxxxx: message`. */
    private fun errors(text: String, vararg codes: String): List<String> {
        myFixture.configureByText("Inheritance${files++}.cs", "using System;\nusing System.Collections.Generic;\nusing System.IO;\n\n" + text.trimIndent())
        val document = myFixture.editor.document.text
        return myFixture.doHighlighting(HighlightSeverity.ERROR).filter { it.severity == HighlightSeverity.ERROR && it.description?.substringBefore(':') in codes }
            .sortedBy { it.startOffset }.map { document.substring(it.startOffset, it.endOffset) + " -> " + it.description }
    }

    fun testOverrideWithoutABaseMember() {
        assertEquals(
            listOf(
                "Perimeter -> CS0115: 'Circle.Perimeter()': no suitable method found to override",
                "Describe -> CS0115: 'Circle.Describe(string)': no suitable method found to override",
                "this -> CS0115: 'Circle.this[string]': no suitable method found to override",
                "Resized -> CS0115: 'Circle.Resized': no suitable method found to override",
                "Get -> CS0115: 'IntBox.Get(long)': no suitable method found to override",
                "Reason -> CS0115: 'Failure.Reason': no suitable method found to override",
            ),
            errors(
                """
                namespace Shop;
                public abstract class Shape
                {
                    public abstract double Area();
                    public virtual string Describe(int precision) => "";
                    public virtual int this[int index] => index;
                    public virtual event EventHandler? Changed;
                }
                public class Circle : Shape
                {
                    public override double Area() => 1;
                    public override double Perimeter() => 1;
                    public override string Describe(int precision) => "";
                    public override string Describe(string format) => format;
                    public override int this[int index] => index;
                    public override int this[string key] => 1;
                    public override event EventHandler? Changed;
                    public override event EventHandler? Resized;
                    public override string ToString() => "";
                    public override bool Equals(object? obj) => false;
                    public override int GetHashCode() => 0;
                }
                public class Box<T> { public virtual T? Get(int index) => default; }
                public class IntBox : Box<int>
                {
                    public override int Get(int index) => index;
                    public override int Get(long index) => 0;
                }
                public class Failure : Exception
                {
                    public override string Message => "";
                    public override string Reason => "";
                }
                public record Person(string Name) { public override string ToString() => Name; }
                public record Employee(string Name) : Person(Name) { protected override bool PrintMembers(System.Text.StringBuilder builder) => true; }
                public struct Point { public override string ToString() => ""; }
                """,
                "CS0115",
            ),
        )
    }

    fun testOverrideOfWhatCannotBeOverridden() {
        assertEquals(
            listOf(
                "Save -> CS0506: 'Cached.Save(string)': cannot override inherited member 'Repository.Save(string)' because it is not marked virtual, abstract, or override",
                "Count -> CS0506: 'Cached.Count': cannot override inherited member 'Repository.Count' because it is not marked virtual, abstract, or override",
                "Put -> CS0506: 'TextHolder.Put(string)': cannot override inherited member 'Holder<string>.Put(string)' because it is not marked virtual, abstract, or override",
                "Add -> CS0506: 'Names.Add(string)': cannot override inherited member 'List<string>.Add(string)' because it is not marked virtual, abstract, or override",
                "Sound -> CS0239: 'Puppy.Sound()': cannot override inherited member 'Dog.Sound()' because it is sealed",
            ),
            errors(
                """
                namespace Shop;
                public class Repository { public void Save(string item) { } public virtual void Delete(string item) { } public int Count => 0; }
                public class Cached : Repository
                {
                    public override void Save(string item) { }
                    public override void Delete(string item) { }
                    public override int Count => 1;
                }
                public class Holder<T> { public void Put(T item) { } public virtual void Take(T item) { } }
                public class TextHolder : Holder<string> { public override void Put(string item) { } public override void Take(string item) { } }
                public class Names : List<string> { public override void Add(string item) { } }
                public abstract class Animal { public abstract string Sound(); }
                public class Dog : Animal { public sealed override string Sound() => ""; }
                public class Puppy : Dog { public override string Sound() => ""; }
                """,
                "CS0506", "CS0239", "CS0115",
            ),
        )
    }

    fun testAccessAndReturnTypeOfAnOverride() {
        assertEquals(
            listOf(
                "Handle -> CS0507: 'Log.Handle(string)': cannot change access modifiers when overriding 'public' inherited member 'Handler.Handle(string)'",
                "OnError -> CS0507: 'Log.OnError()': cannot change access modifiers when overriding 'protected' inherited member 'Handler.OnError()'",
                "ToString -> CS0507: 'Log.ToString()': cannot change access modifiers when overriding 'public' inherited member 'object.ToString()'",
                "Count -> CS0508: 'Cats.Count()': return type must be 'int' to match overridden member 'Shelter.Count()'",
                "Clean -> CS0508: 'Cats.Clean()': return type must be 'void' to match overridden member 'Shelter.Clean()'",
                "Adopt -> CS0508: 'Rocks.Adopt()': return type must be 'Animal' to match overridden member 'Shelter.Adopt()'",
                "GetHashCode -> CS0508: 'Rocks.GetHashCode()': return type must be 'int' to match overridden member 'object.GetHashCode()'",
            ),
            errors(
                """
                namespace Shop;
                public abstract class Handler { public abstract void Handle(string message); protected virtual void OnError() { } internal virtual void Trace() { } }
                public class Log : Handler
                {
                    protected override void Handle(string message) { }
                    public override void OnError() { }
                    internal override void Trace() { }
                    protected override string ToString() => "";
                }
                public class Animal { }
                public class Cat : Animal { }
                public class Rock { }
                public abstract class Shelter { public abstract int Count(); public abstract Animal Adopt(); public virtual void Clean() { } public virtual object Payload() => 1; }
                public class Cats : Shelter
                {
                    public override long Count() => 0;
                    public override Cat Adopt() => new Cat();
                    public override int Clean() => 0;
                    public override string Payload() => "";
                }
                public class Rocks : Shelter
                {
                    public override int Count() => 0;
                    public override Rock Adopt() => new Rock();
                    public override string GetHashCode() => "";
                }
                """,
                "CS0507", "CS0508",
            ),
        )
    }

    fun testOverridesStaySilentWhereTheBaseIsNotSurelyKnown() {
        assertEquals(
            emptyList<String>(),
            errors(
                """
                namespace Shop;
                public partial class Base { public virtual void Run() { } }
                public class FromPartial : Base { public override void Walk() { } }
                public class FromUnknown : Missing { public override void Walk() { } }
                public class Generic { public virtual void Map<T>(T item) { } public virtual void Pair((int a, int b) p) { } public virtual void Ref(ref int x) { } }
                public class FromGeneric : Generic
                {
                    public override void Map<U>(U item) { }
                    public override void Pair((int a, int b) p) { }
                    public override void Ref(ref int x) { }
                }
                public partial class Part : Generic { public override void Walk() { } }
                public record Person(string Name);
                public record Employee(string Name) : Person(Name) { public override bool Equals(Person? other) => true; }
                """,
                "CS0115", "CS0506", "CS0239", "CS0507", "CS0508",
            ),
        )
    }

    fun testAbstractMembersAndBodies() {
        assertEquals(
            listOf(
                "Render -> CS0513: 'Report.Render(int, string)' is abstract but it is contained in non-abstract type 'Report'",
                "get -> CS0513: 'Report.Pages.get' is abstract but it is contained in non-abstract type 'Report'",
                "get -> CS0513: 'Report.this[int].get' is abstract but it is contained in non-abstract type 'Report'",
                "set -> CS0513: 'Report.this[int].set' is abstract but it is contained in non-abstract type 'Report'",
                "Printed -> CS0513: 'Report.Printed' is abstract but it is contained in non-abstract type 'Report'",
                // a partial class of a project that generates no types is as known as any other
                "Later -> CS0513: 'Partial.Later()' is abstract but it is contained in non-abstract type 'Partial'",
                "Parse -> CS0500: 'Parser.Parse(string)' cannot declare a body because it is marked abstract",
                "get -> CS0500: 'Parser.Name.get' cannot declare a body because it is marked abstract",
                "Start -> CS0501: 'Parser.Start()' must declare a body because it is not marked abstract, extern, or partial",
                "Increment -> CS0501: 'Counter.Increment()' must declare a body because it is not marked abstract, extern, or partial",
            ),
            errors(
                """
                namespace Shop;
                public class Report
                {
                    public abstract string Render(int width, string title);
                    public abstract int Pages { get; }
                    public abstract string this[int page] { get; set; }
                    public abstract event EventHandler Printed;
                }
                public abstract class Template { public abstract string Render(); public abstract int Pages { get; } }
                public partial class Partial { public abstract void Later(); }
                public abstract class Parser
                {
                    public abstract int Parse(string text) { return 0; }
                    public abstract string Name { get { return ""; } }
                    public abstract int Depth { get; set; }
                    public void Start();
                    public static extern int Tick();
                }
                public struct Counter { public void Increment(); }
                public interface IService { void Start(); abstract void Stop(); }
                """,
                "CS0513", "CS0500", "CS0501",
            ),
        )
    }

    fun testOverridesOfGenericMethodsByRefParametersTuplesAndProperties() {
        assertEquals(
            listOf(
                "Map -> CS0115: 'Shelf.Map(int)': no suitable method found to override",
                "Pair -> CS0115: 'Shelf.Pair<A>(A, A)': no suitable method found to override",
                "Load -> CS0115: 'Shelf.Load(int)': no suitable method found to override",
                "Find -> CS0115: 'Shelf.Find(ref int)': no suitable method found to override",
                "Read -> CS0115: 'Shelf.Read(ref int)': no suitable method found to override",
                "Join -> CS0115: 'Shelf.Join((int Key, string Text), int)': no suitable method found to override",
                "Size -> CS1715: 'Shelf.Size': type must be 'int' to match overridden member 'Store<string>.Size'",
                "Owner -> CS1715: 'Shelf.Owner': type must be 'Animal' to match overridden member 'Store<string>.Owner'",
                "Make -> CS0508: 'Shelf.Make()': return type must be 'string' to match overridden member 'Store<string>.Make()'",
                "Get -> CS0508: 'Shelf.Get<W>()': return type must be 'W' to match overridden member 'Store<string>.Get<U>()'",
                "All -> CS0508: 'Shelf.All()': return type must be 'List<string>' to match overridden member 'Store<string>.All()'",
                "this -> CS1715: 'Shelf.this[int]': type must be 'int' to match overridden member 'Store<string>.this[int]'",
                "Make -> CS0508: 'Rack<X>.Make()': return type must be 'X' to match overridden member 'Store<X>.Make()'",
                "Get -> CS0508: 'Rack<X>.Get<W>()': return type must be 'W' to match overridden member 'Store<X>.Get<U>()'",
            ),
            errors(
                """
                namespace Shop;
                public class Animal { }
                public class Dog : Animal { }
                public abstract class Store<T>
                {
                    public virtual void Map<U>(U item) { }
                    public virtual void Pair<U, V>(U first, V second) { }
                    public virtual void Load(ref int count) { }
                    public virtual bool Find(out int index) { index = 0; return false; }
                    public virtual void Read(in int position) { }
                    public virtual void Join((int Id, string Name) row) { }
                    public virtual int Size { get; set; }
                    public virtual Animal Pet { get; } = new();
                    public virtual Animal Owner { get; set; } = new();
                    public virtual T Make() => default!;
                    public virtual U Get<U>() => default!;
                    public virtual List<T> All() => new();
                    public virtual int this[int index] => 0;
                }
                public class Shelf : Store<string>
                {
                    public override void Map(int item) { }
                    public override void Map<X>(X item) { }
                    public override void Pair<A>(A first, A second) { }
                    public override void Load(int count) { }
                    public override bool Find(ref int index) => false;
                    public override void Read(ref int position) { }
                    public override void Join((int, string) row) { }
                    public override void Join((int Key, string Text) row, int extra) { }
                    public override long Size { get; set; }
                    public override Dog Pet { get; } = new();
                    public override Dog Owner { get; set; } = new();
                    public override object Make() => "";
                    public override object Get<W>() => 1;
                    public override List<object> All() => new();
                    public override long this[int index] => 0;
                }
                public class Rack<X> : Store<X>
                {
                    public override object Make() => 1;
                    public override X Get<W>() => default!;
                }
                public class Cabinet : Store<int>
                {
                    public override int Make() => 0;
                    public override W Get<W>() => default!;
                    public override void Map<Y>(Y item) { }
                    public override bool Find(out int index) { index = 1; return true; }
                }
                """,
                "CS0115", "CS0508", "CS1715",
            ),
        )
    }

    fun testAccessOfProtectedInternalMembersOfAnAssembly() {
        // a `protected internal` member of another assembly is overridden as `protected` (the index tells it from `protected` since format 4)
        assertEquals(
            listOf(
                "QueueTask -> CS0507: 'Queue1.QueueTask(Task)': cannot change access modifiers when overriding 'protected internal' inherited member 'TaskScheduler.QueueTask(Task)'",
                "GetScheduledTasks -> CS0507: 'Queue1.GetScheduledTasks()': cannot change access modifiers when overriding 'protected' inherited member 'TaskScheduler.GetScheduledTasks()'",
                "QueueTask -> CS0507: 'Queue2.QueueTask(Task)': cannot change access modifiers when overriding 'protected internal' inherited member 'TaskScheduler.QueueTask(Task)'",
                "TryDequeue -> CS0507: 'Queue2.TryDequeue(Task)': cannot change access modifiers when overriding 'protected internal' inherited member 'TaskScheduler.TryDequeue(Task)'",
            ),
            errors(
                """
                using System.Threading.Tasks;
                namespace Shop;
                public class Queue1 : TaskScheduler
                {
                    public override void QueueTask(Task task) { }
                    protected override bool TryDequeue(Task task) => false;
                    public override IEnumerable<Task>? GetScheduledTasks() => null;
                    protected override bool TryExecuteTaskInline(Task task, bool previous) => false;
                }
                public class Queue2 : TaskScheduler
                {
                    protected internal override void QueueTask(Task task) { }
                    protected internal override bool TryDequeue(Task task) => false;
                    protected override IEnumerable<Task>? GetScheduledTasks() => null;
                    protected override bool TryExecuteTaskInline(Task task, bool previous) => false;
                }
                public class Queue3 : TaskScheduler
                {
                    protected override void QueueTask(Task task) { }
                    protected override IEnumerable<Task>? GetScheduledTasks() => null;
                    protected override bool TryExecuteTaskInline(Task task, bool previous) => false;
                }
                """,
                "CS0507", "CS0115",
            ),
        )
    }

    fun testRecordsAndStaticClassesInBaseLists() {
        assertEquals(
            listOf(
                "Money -> CS0509: 'Wallet': cannot derive from sealed type 'Money'",
                "Entity -> CS8865: Only records may inherit from records.",
                "Helpers -> CS0709: 'Helpers': cannot derive from static class 'Tools'",
                "Output -> CS0709: 'Output': cannot derive from static class 'Console'",
                "Plain -> CS8864: Records may only inherit from object or another record",
                "Exception -> CS8864: Records may only inherit from object or another record",
                "Money -> CS0509: 'Price': cannot derive from sealed type 'Money'",
            ),
            errors(
                """
                namespace Shop;
                public sealed record Money(decimal Amount);
                public record Entity(int Id);
                public static class Tools { }
                public class Plain { }
                public class Wallet : Money { public Wallet() : base(1) { } }
                public class Customer : Entity { public Customer() : base(1) { } }
                public class Helpers : Tools { }
                public class Output : Console { }
                public record Order : Plain { }
                public record Failure : Exception { }
                public record Item : Entity { public Item() : base(1) { } }
                public record Price : Money { public Price() : base(1) { } }
                public record Tag : object { }
                public class Free : object { }
                """,
                "CS0509", "CS0527", "CS0709", "CS8864", "CS8865",
            ),
        )
    }

    fun testTargetTypedCreationsAndInheritedMembersOfAssemblies() {
        assertEquals(
            listOf(
                "new() -> CS0144: Cannot create an instance of the abstract type or interface 'Shape'",
                "new() -> CS0144: Cannot create an instance of the abstract type or interface 'Stream'",
                "new() -> CS0144: Cannot create an instance of the abstract type or interface 'IList<int>'",
                "new() -> CS0144: Cannot create an instance of the abstract type or interface 'IStore'",
                "new() -> CS0144: Cannot create an instance of the abstract type or interface 'Shape'",
                "new() -> CS0144: Cannot create an instance of the abstract type or interface 'IStore'",
                "new() -> CS0144: Cannot create an instance of the abstract type or interface 'Stream'",
                "Count -> CS0236: A field initializer cannot reference the non-static field, method, or property 'List<int>.Count'",
                "Capacity -> CS0236: A field initializer cannot reference the non-static field, method, or property 'List<int>.Capacity'",
                "ToArray -> CS0236: A field initializer cannot reference the non-static field, method, or property 'List<int>.ToArray()'",
                "GetType -> CS0236: A field initializer cannot reference the non-static field, method, or property 'object.GetType()'",
                "Message -> CS0236: A field initializer cannot reference the non-static field, method, or property 'Exception.Message'",
                "Data -> CS0236: A field initializer cannot reference the non-static field, method, or property 'Exception.Data'",
            ),
            errors(
                """
                namespace Shop;
                public interface IStore { }
                public abstract class Shape { }
                public class Square : Shape { }
                public class Holder
                {
                    private IStore? _store;
                    private Shape _shape = new();
                    public Stream Output { get; set; } = new();
                    public IList<int> Items = new();
                    public List<int> Fine = new();
                    public void Run()
                    {
                        IStore a = new();
                        Shape? b = new();
                        _store = new();
                        Output = new();
                        Square c = new();
                        var d = new Square();
                        Console.WriteLine($"{a}{b}{c}{d}{_store}{_shape}");
                    }
                }
                public class Numbers : List<int>
                {
                    private int size = Count;
                    private int room = Capacity;
                    private object copy = ToArray();
                    private object type = GetType();
                    private static int Zero = 0;
                    private int fine = Zero;
                }
                public class Failure : Exception
                {
                    private string text = Message;
                    private object data = Data;
                }
                """,
                "CS0144", "CS0236", "CS0120",
            ),
        )
    }

    fun testBaseLists() {
        assertEquals(
            listOf(
                "Settings -> CS0509: 'AppSettings': cannot derive from sealed type 'Settings'",
                "string -> CS0509: 'Text': cannot derive from sealed type 'string'",
                "Size -> CS0509: 'Big': cannot derive from sealed type 'Size'",
                "Settings -> CS0509: 'Generic<T>': cannot derive from sealed type 'Settings'",
                "Entity -> CS0527: Type 'Entity' in interface list is not an interface",
                "Entity -> CS0527: Type 'Entity' in interface list is not an interface",
                "Exception -> CS0527: Type 'Exception' in interface list is not an interface",
            ),
            errors(
                """
                namespace Shop;
                public sealed class Settings { }
                public class Options { }
                public struct Size { }
                public interface IEntity { }
                public class Entity { }
                public class AppSettings : Settings { }
                public class AppOptions : Options, IEntity { }
                public class Text : string { }
                public class Big : Size { }
                public class Generic<T> : Settings { }
                public class Failure : Exception { }
                public struct Order : IEntity, Entity { }
                public interface IRepository : Entity { }
                public record struct Key(int Value) : Exception;
                public record struct Code(int Value) : IEntity;
                public interface IUnknown : Missing { }
                """,
                "CS0509", "CS0527",
            ),
        )
    }

    fun testCreationsAndStaticMembers() {
        assertEquals(
            listOf(
                "new Shape() -> CS0144: Cannot create an instance of the abstract type or interface 'Shape'",
                "new IStore() -> CS0144: Cannot create an instance of the abstract type or interface 'IStore'",
                "new Stream() -> CS0144: Cannot create an instance of the abstract type or interface 'Stream'",
                "new Helpers() -> CS0712: Cannot create an instance of the static class 'Helpers'",
                "new Console() -> CS0712: Cannot create an instance of the static class 'Console'",
                "calc.Max -> CS0176: Member 'Calc.Max' cannot be accessed with an instance reference; qualify it with a type name instead",
                "text.Empty -> CS0176: Member 'string.Empty' cannot be accessed with an instance reference; qualify it with a type name instead",
                "only.Twice -> CS0176: Member 'Only.Twice(int)' cannot be accessed with an instance reference; qualify it with a type name instead",
            ),
            errors(
                """
                namespace Shop;
                public abstract class Shape { }
                public class Square : Shape { }
                public interface IStore { }
                public static class Helpers { }
                public enum Color { Red }
                public class Calc { public static int Twice(int a) => a; public int Twice(string s) => 0; public static int Twice(long a) => 0; public const int Max = 1; public int Add() => 0; }
                public class Only { public static int Twice(int a) => a; }
                public class Palette { public Color Color => Color.Red; }
                public class Use
                {
                    public void Run(Calc calc, string text, Only only, Palette p, MemoryStream memory)
                    {
                        object a = new Shape();
                        object b = new Square();
                        object c = new IStore();
                        object d = new Stream();
                        object e = new Helpers();
                        object f = new Console();
                        object g = new Shape[2];
                        int h = calc.Add();
                        int i = Calc.Twice(1);
                        int j = calc.Max;
                        string k = text.Empty;
                        var l = p.Color;
                        int m = calc.Twice("x");
                        int n = only.Twice(1);
                    }
                }
                """,
                "CS0144", "CS0712", "CS0176",
            ),
        )
    }

    fun testFieldInitializers() {
        assertEquals(
            listOf(
                "host -> CS0236: A field initializer cannot reference the non-static field, method, or property 'Connection.host'",
                "host -> CS0236: A field initializer cannot reference the non-static field, method, or property 'Connection.host'",
                "Retries -> CS0236: A field initializer cannot reference the non-static field, method, or property 'Connection.Retries()'",
                "host -> CS0236: A field initializer cannot reference the non-static field, method, or property 'Connection.host'",
                "Size -> CS0236: A field initializer cannot reference the non-static field, method, or property 'Base.Size'",
            ),
            errors(
                """
                namespace Shop;
                public class Connection
                {
                    private string host = "localhost";
                    private string url = host + ":80";
                    private static string Default = host;
                    private int attempts = Retries();
                    private int Retries() => 3;
                    private static int Port() => 80;
                    private int next = Port();
                    public string Host { get; } = host;
                    public string Name => host;
                    public Connection() { url = host; }
                }
                public class Base { protected int Size = 1; }
                public class Derived : Base { private int doubled = Size * 2; }
                public class Primary(int size) { public int Size { get; } = size; }
                public enum Color { Red }
                public class Palette { public Color Color = Color.Red; }
                """,
                "CS0236", "CS0120",
            ),
        )
    }
}
