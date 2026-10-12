using System.Runtime.CompilerServices;

// a friend named with a public key is a friend of an assembly signed with that key only: an unsigned Probe.Friend is not one
[assembly: InternalsVisibleTo("Probe.Friend, PublicKey=0024000004800000940000000602000000240000525341310004000001000100")]
[assembly: InternalsVisibleTo("Probe.Open")]

namespace AccessFixture;

public class Vault
{
    public Vault() { }
    protected Vault(int seed) { }
    internal Vault(string name) { }

    internal int InternalField;
    private int _private;
    private protected int Narrow;
    protected internal int Shared;
    protected int Guarded;
    public readonly int Ro;
    public static readonly int SRo;

    internal void InternalMethod() { }
    internal static void InternalStatic() { }

    public int PrivateSet { get; private set; }
    public int InternalSet { get; internal set; }
    public int ProtectedSet { get; protected set; }
    public int NarrowSet { get; private protected set; }
    public int SharedSet { get; protected internal set; }
    public int PrivateGet { private get; set; }
    public int ProtectedGet { protected get; set; }
    public int InternalGet { internal get; set; }
    public int ProtectedInit { get; protected init; }
    public int InternalInit { get; internal init; }
    public int SetOnly { set { } }
    public int GetOnly => 1;
    internal int InternalProperty { get; set; }

    public int this[int i] { get => i; protected set { } }
    public string this[string s] { get => s; set { } }
    public long this[long l] => l;

    protected event Action? Changed;

    internal class InternalNested { }
    private class PrivateNested { }
    protected class ProtectedNested { }
    public class PublicNested { }
}

public class Sealed
{
    internal Sealed() { }
}

public class Guarded
{
    protected Guarded() { }
}

internal class Hidden
{
    public static int Count;
    public void Run() { }
}

internal static class HiddenExtensions
{
    public static int Twice(this int value) => value * 2;
}

public static class Extensions
{
    internal static int Thrice(this int value) => value * 3;
}
