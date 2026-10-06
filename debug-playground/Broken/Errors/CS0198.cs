// CS0198: A static readonly field cannot be assigned to (except in a static constructor or a variable initializer). Lines marked `// ERROR CSxxxx` must show that error in the editor (and in
// `dotnet build`), every other line no error at all: tools/diag/check_errors.py roslyn|ide checks both. Not compiled with Broken.
namespace DebugPlayground.Broken.Errors.CS0198;

public class Settings
{
    public static readonly string Default = "en";
    private static readonly int Limit;
    private static int _loaded;

    static Settings()
    {
        Limit = 10;
        Settings.Limit += 1;
    }

    public Settings()
    {
        _loaded++;
        Limit = 20; // ERROR CS0198
    }

    public static void Reload()
    {
        _loaded = 0;
        Default = "de"; // ERROR CS0198
        Limit++; // ERROR CS0198
    }
}

public class Loader
{
    public void Load()
    {
        Settings.Default = "fr"; // ERROR CS0198
        string.Empty = "-"; // ERROR CS0198
        var language = Settings.Default;
        Console.WriteLine(language);
    }
}
