using System;
using System.IO;

namespace Playground.Editor;

/// <summary>
/// Live check of Generate (Alt+Insert) without the language server (0.1.75, CSHARP_PSI_MIGRATION.md task C4d). Works the same with the
/// server off (Settings | .NET | Language Server, uncheck the server) and on: with the server ready its rows of the same generators
/// ("Generate constructor …", "Generate Equals and GetHashCode…", "Generate overrides…", "Implement interface", "Implement abstract class")
/// are not in the list. Put the caret on the empty line under a marker, press Alt+Insert, choose the row, check the member chooser and
/// EXPECT, then undo (Ctrl+Z) so the file stays as it is.
/// </summary>
public class GenOrder
{
    private readonly int _id;
    private string _customer = "";
    public decimal Total { get; set; }

    // TYPE:gen-list — Alt+Insert here. EXPECT: the popup "Generate" lists Constructor, Read-only properties, Properties, Missing members
    // (gray, Ctrl+I), Overriding members (gray here: nothing virtual but object's members when the assemblies are not indexed; Ctrl+O),
    // Partial members (gray), Partial Part, Deconstructor, Equality members, Formatting members, Dispose pattern, Unit Test, then the
    // rest of the platform's group (Insert New GUID…). NOT expected: a second "Constructor" row of the server, "Override Methods…" or
    // "Implement Methods…" of the platform (the native rows answer them). Members generated here go on this line (as in Rider).

    // TYPE:gen-constructor — Alt+Insert → Constructor. EXPECT: the chooser "Generate Constructor" with groups Fields (`_id: int` checked,
    // `_customer: string`) and Properties (`Total: decimal`); OK with all three checked gives, formatted, under this comment:
    // `public GenOrder(int id, string customer, decimal total)` with `_id = id; _customer = customer; Total = total;`.

    // TYPE:gen-properties — Alt+Insert → Read-only properties. EXPECT: `public int Id => _id;` and `public string Customer => _customer;`.
    // Alt+Insert → Properties. EXPECT: only `_customer` is offered (a readonly field gets no setter): `public string Customer { get => _customer;
    // set => _customer = value; }` on three lines.

    // TYPE:gen-equality — Alt+Insert → Equality members, options "Implement 'IEquatable<T>' interface" and "Overload equality operators"
    // checked. EXPECT: `GenOrder : IEquatable<GenOrder>` in the header; `public bool Equals(GenOrder? other)` with the null and reference
    // checks and `_id == other._id && _customer == other._customer && Total == other.Total`; `Equals(object? obj)`; `GetHashCode()` with
    // `HashCode.Combine(_id, _customer, Total)`; `operator ==` / `!=`. The file builds (`dotnet build`).

    // TYPE:gen-formatting — Alt+Insert → Formatting members. EXPECT: `public override string ToString()` returning
    // `$"{nameof(_id)}: {_id}, {nameof(_customer)}: {_customer}, {nameof(Total)}: {Total}"`.

    // TYPE:gen-deconstructor — Alt+Insert → Deconstructor. EXPECT: `public void Deconstruct(out int id, out string customer, out decimal total)`.

    public string Describe() => $"{_id} {_customer} {Total}";
}

public abstract class GenShape
{
    public abstract double Area();
    protected abstract string Label { get; }
    public virtual string Describe(int depth) => Label + depth;
}

public class GenCircle : GenShape
{
    public double Radius { get; init; }
    public override double Area() => Math.PI * Radius * Radius;
    protected override string Label => "circle";

    // TYPE:gen-override — Alt+Insert → Overriding members (or Code | Override Methods, Ctrl+O). EXPECT: the chooser groups `GenShape`
    // (`Describe(int depth): string`) and `object` (`Equals`, `GetHashCode`, `ToString`); Area and Label are not offered (overridden).
    // Choosing Describe and ToString gives `public override string Describe(int depth) { return base.Describe(depth); }` and
    // `public override string? ToString() { return base.ToString(); }`. With only `}` after the caret the members go after the last member.

}

public abstract class GenSquare : GenShape
{
    // TYPE:gen-missing-abstract — Alt+Insert → Missing members (or Ctrl+I). EXPECT: `Area(): double` and `Label: string` checked;
    // OK gives `public override double Area() { throw new NotImplementedException(); }` and `protected override string Label { get; }`.

}

public class GenMoney
{
    public decimal Amount { get; init; }

    // TYPE:gen-missing-library — type `, IComparable<GenMoney>, IDisposable` after `GenMoney` in the header (it does not compile now),
    // put the caret back here, Alt+Insert → Missing members. EXPECT: groups `IComparable<GenMoney>` (`CompareTo(GenMoney? other): int`) and
    // `IDisposable` (`Dispose(): void`); OK gives `public int CompareTo(GenMoney? other)` and `public void Dispose()`, each with
    // `throw new NotImplementedException();`. The red marks go away.

}

public class GenResource
{
    private readonly MemoryStream _buffer = new();
    private System.Threading.Timer? _timer;

    // TYPE:gen-dispose — Alt+Insert → Dispose pattern. EXPECT: `_buffer` and `_timer` checked; OK adds `: IDisposable` to the header,
    // `protected virtual void Dispose(bool disposing)` with `_buffer.Dispose(); _timer?.Dispose();` and `public void Dispose()` with
    // `Dispose(true); GC.SuppressFinalize(this);`, all on the empty line under this comment.

    public void Start() => _timer = new System.Threading.Timer(_ => _buffer.WriteByte(1));
}

public class GenBase
{
    protected GenBase(string name) => Name = name;
    public string Name { get; }
}

public class GenDerived : GenBase
{
    private readonly int _size;

    public GenDerived() : base("default") => _size = 1;

    // TYPE:gen-base-constructor — Alt+Insert → Constructor. EXPECT: a group "Base constructor" with `base(string name)` checked and
    // `_size: int`; OK gives `public GenDerived(string name, int size) : base(name)` with `_size = size;`.

    public int Size => _size;
}

public partial class GenPartial
{
    partial void OnSaved(int id);

    // TYPE:gen-partial — Alt+Insert → Partial members. EXPECT: the chooser has the group `GenPartial` with `OnSaved(int id): void` checked;
    // OK gives `partial void OnSaved(int id) { }`.

    public void Save() => OnSaved(1);
}
