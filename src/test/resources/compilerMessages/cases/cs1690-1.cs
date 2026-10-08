// docs: cs1690.md #1; codes: CS1690
// CS1690.cs  
using System;

class WarningCS1690 : MarshalByRefObject
{
    int i = 5;

    public static void Main()
    {
        AppDomain domain = AppDomain.CreateDomain("MyDomain");
        Type t = typeof(WarningCS1690);
        WarningCS1690 e = (WarningCS1690)domain.CreateInstanceAndUnwrap(t.Assembly.FullName,t.FullName);

        e.i.ToString(); // CS1690

        // OK  
        int i = e.i;
        i.ToString();
        e.i = i;
    }
}
