namespace Playground.Editor;

/// <summary>The other files of <c>SemanticColors.cs</c>: a base class and the other part of a partial class, read from their stubs there.</summary>
public abstract class ColorBase
{
    protected int _ticks;

    public void Tick() => _ticks++;
}

public static partial class ColorRegistry
{
    public static int Count;

    public static void Reset() => Count = 0;
}

public partial class ColorOrder
{
    public ColorSize Size { get; } = new(1, 2);
}

public record struct ColorSize(int Width, int Height);
