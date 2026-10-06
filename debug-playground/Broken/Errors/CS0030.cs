// CS0030: Cannot convert type 'A' to 'B'. Lines marked `// ERROR CSxxxx` must show that error in the editor (and in
// `dotnet build`), every other line no error at all: tools/diag/check_errors.py roslyn|ide checks both. Not compiled with Broken.
namespace DebugPlayground.Broken.Errors.CS0030;

public enum CastLevel { Low, High }

public struct CastPoint { public int X; }

public class CastAnimal { }
public class CastDog : CastAnimal { }
public class CastCar { }

public readonly struct CastMeters(double value)
{
    public double Value { get; } = value;
    public static explicit operator CastMeters(double value) => new(value);
}

public class CastCases
{
    public void Run(bool flag, int count, string text, double rate, CastLevel level, CastPoint point, CastAnimal animal, CastCar car, object any, int? maybe)
    {
        var number = (int)flag; // ERROR CS0030
        var truth = (bool)count; // ERROR CS0030
        var parsed = (int)text; // ERROR CS0030
        var shown = (string)count; // ERROR CS0030
        var dog = (CastDog)animal;
        var vehicle = (CastCar)animal; // ERROR CS0030
        var asText = (string)car; // ERROR CS0030
        var level2 = (CastLevel)count;
        var rounded = (int)rate + (long)level + (char)count;
        var fromBool = (CastLevel)flag; // ERROR CS0030
        var unboxed = (CastPoint)any;
        var notPoint = (CastPoint)count; // ERROR CS0030
        var nullableFlag = (int?)flag; // ERROR CS0030
        var lifted = (long?)maybe;
        var meters = (CastMeters)rate;
        Console.WriteLine($"{number} {truth} {parsed} {shown} {dog} {vehicle} {asText} {level2} {rounded} {fromBool} {unboxed} {notPoint} {nullableFlag} {lifted} {meters.Value}");
    }
}
