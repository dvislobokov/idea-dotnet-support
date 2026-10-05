using System.ComponentModel;

namespace Fixture;

/// <summary>A shape of the plane.</summary>
/// <remarks>Abstract: <see cref="Circle"/> is one.</remarks>
public abstract class Shape
{
    public const string Kind = "shape\n";
    public const double Ratio = 1.5;
    public const long Big = -9000000000;

    protected int Counter;
    public readonly int Created;
    public static int Count;

    protected Shape()
    {
    }

    /// <summary>The area of the shape.</summary>
    /// <value>Square units.</value>
    public abstract double Area { get; }

    /// <summary>Says what the shape is.</summary>
    /// <param name="prefix">What goes first.</param>
    /// <param name="digits">How many digits of the area.</param>
    /// <returns>The text.</returns>
    public virtual string Describe(string? prefix = null, int digits = 2) => "";

    public string? Label { get; protected set; }

    public event EventHandler? Changed;

    public static Shape operator +(Shape a, Shape b) => a;

    public static implicit operator double(Shape shape) => shape.Area;

    private void Hidden()
    {
    }

    private protected void Narrow()
    {
    }
}

public sealed class Circle : Shape
{
    public Circle(double radius) => Radius = radius;

    public double Radius { get; init; }

    public required string Name { get; set; }

    public override double Area => Radius * Radius * Math.PI;

    public override string Describe(string? prefix = null, int digits = 2) => "";

    /// <summary>A part of the circle.</summary>
    public int this[int index, string? key = null] => index;
}

/// <summary>A box of a <typeparamref name="T"/>.</summary>
/// <typeparam name="T">What is in the box.</typeparam>
public class Box<T> : IComparable<Box<T>>, IEnumerable<T>, IHolder<T> where T : class, new()
{
    public List<T?> Items = new();

    public T Held => Value;

    public string Title => "";

    public T Value { get; protected set; } = new();

    public int CompareTo(Box<T>? other) => 0;

    public TResult Map<TResult>(Func<T, TResult> map) where TResult : notnull => map(Value);

    public virtual Dictionary<string, List<T>> Group(params T[] values) => new();

    public IEnumerator<T> GetEnumerator() => Items.Where(item => item != null).Select(item => item!).GetEnumerator();

    System.Collections.IEnumerator System.Collections.IEnumerable.GetEnumerator() => GetEnumerator();

    public class Inner<U> where U : struct
    {
        public (T First, U? second, int) Pair;

        public (int A, int B, int C, int D, int E, int F, int G, int H, (string Nested, int) I) Long;
    }
}

public class StringBox : Box<StringBuilderLike>
{
    public override Dictionary<string, List<StringBuilderLike>> Group(params StringBuilderLike[] values) => new();
}

public class StringBuilderLike
{
}

public interface INamed
{
    string Title { get; }
}

public interface IHolder<out T> : INamed
{
    T Held { get; }
}

public record Point(int X, int Y);

public readonly struct Money
{
    public decimal Amount { get; }

    public readonly override string ToString() => "";
}

public ref struct Cursor
{
    public ref int Position;

    public readonly ref readonly int Peek() => ref Position;
}

public interface IShape<in TIn, out TOut> where TIn : allows ref struct
{
    static abstract int Total { get; }

    TOut Get(TIn input);
}

public enum Color : byte
{
    Red = 1,
    Green = 2,
}

public delegate int Handler(ref int x, out string y, in long z, params object[] rest);

public static class ShapeExtensions
{
    public static T Twice<T>(this T shape) where T : Shape => shape;

    public static int Total(this int[] values) => 0;

    public static string Name(this Shape? shape, string fallback = "none", char separator = '\'', Color color = Color.Green, double? scale = null) => "";

    public static int Sum(this IEnumerable<int> values) => 0;

    public static unsafe void Pointer(int* pointer, delegate*<int, void> callback, int[,] matrix)
    {
    }

    public static void Unmanaged<T>(T value) where T : unmanaged
    {
    }
}

[Obsolete("Use Shape")]
public class Old
{
}

[EditorBrowsable(EditorBrowsableState.Never)]
public class Concealed
{
    public static void Run()
    {
    }
}

public class Outer
{
    protected class Secret
    {
        public static void Tell()
        {
        }
    }

    private class Private
    {
    }

    internal class Internal
    {
    }
}

internal class NotSeen
{
    public static void Never()
    {
    }
}
