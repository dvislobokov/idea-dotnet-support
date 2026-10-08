// docs: cs0050.md #3; codes: CS0050
// Fixed version
using System.Collections.ObjectModel;

public class CeisData // Now public
{
    public string Name { get; set; }
}

public class MyClass
{
    public static ObservableCollection<CeisData> BuildCeis()   // OK
    {
        return new ObservableCollection<CeisData>();
    }
}
