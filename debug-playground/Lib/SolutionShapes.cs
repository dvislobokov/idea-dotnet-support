namespace Playground.Lib;

// The other project of the scenarios of Console/Editor/SolutionUsages.cs (0.1.73): the usages, implementations and renames there reach
// across the ProjectReference into this file and back. Nothing to do here; the file only has to compile.

/// <summary>A figure with an area; see <see cref="SolutionSquare.Side"/>.</summary>
public interface ISolutionFigure
{
    double Area();

    string Label { get; }
}

public class SolutionSquare : ISolutionFigure
{
    public SolutionSquare(double side) { Side = side; }

    public double Side { get; set; }

    public virtual double Area() => Side * Side;

    public string Label => nameof(Side);
}
