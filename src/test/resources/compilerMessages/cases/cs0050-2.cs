// docs: cs0050.md #2; codes: CS0050
// CS0050_Generic.cs
using System.Collections.ObjectModel;

internal class CeisData // Internal class
{
    public string Name { get; set; }
}

public class MyClass
{
    public static ObservableCollection<CeisData> BuildCeis()   // CS0050
    {
        return new ObservableCollection<CeisData>();
    }
}
