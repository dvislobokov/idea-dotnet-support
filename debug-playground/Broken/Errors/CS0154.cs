// CS0154: The property or indexer 'x' cannot be used in this context because it lacks the get accessor. Lines marked `// ERROR CSxxxx` must show that error in the editor (and in
// `dotnet build`), every other line no error at all: tools/diag/check_errors.py roslyn|ide checks both. Not compiled with Broken.
namespace DebugPlayground.Broken.Errors.CS0154;

public class Sensor
{
    private int _raw;
    public int Calibration { set => _raw = value; }
    public int Reading { get => _raw; set => _raw = value; }

    public void Reset()
    {
        Calibration = 0;
        var raw = Calibration; // ERROR CS0154
        Console.WriteLine(raw);
    }
}

public class Station
{
    public void Poll(Sensor sensor)
    {
        sensor.Calibration = 5;
        sensor.Reading += 1;
        var value = sensor.Calibration; // ERROR CS0154
        sensor.Calibration += 1; // ERROR CS0154
        sensor.Calibration++; // ERROR CS0154
        Console.WriteLine(sensor.Calibration); // ERROR CS0154
        var copy = new Sensor { Calibration = 3 };
        Console.WriteLine(value + copy.Reading);
    }
}
