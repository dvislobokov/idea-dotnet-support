using System;
using Newtonsoft.Json;

namespace LegacyConsole
{
    internal static class Program
    {
        private static void Main(string[] args)
        {
            // Newtonsoft.Json comes from ..\packages: the build restores it (packages.config), the reference is a HintPath
            var json = JsonConvert.SerializeObject(new { Runtime = Environment.Version.ToString(), Arguments = args });
            Console.WriteLine(json); // BP:legacy-console — Debug: stops here; EXPECT: frame Main, json with Runtime 4.0.30319.x, Evaluate Environment.Version works
            // the console of a .NET Framework program writes in the OEM code page, not UTF-8: EXPECT the line as it is here in the Run window
            Console.WriteLine("Кириллица: привет, ёжик");
        }
    }
}
