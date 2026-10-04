namespace Synthetic.Records;

public record Person(string Name, int Age);

public record struct Point(int X, int Y)
{
    public double Length => Math.Sqrt(X * X + Y * Y);
}

public sealed record class Box<T>(T Value) where T : notnull
{
    public Box<T> With(T value) => this with { Value = value };
}

public readonly record struct Money(decimal Amount, string Currency)
{
    public static Money Zero { get; } = new(0, "EUR");
    public required string Note { get; init; }
}

public abstract record Shape;
public record Circle(double Radius) : Shape;
