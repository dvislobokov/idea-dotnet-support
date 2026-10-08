// docs: cs1700.md #1; codes: CS1700
// CS1700.cs  
// compile with: /target:library  
using System.Runtime.CompilerServices;  
[assembly:InternalsVisibleTo("app2, Retargetable=f")]   // CS1700  
[assembly:InternalsVisibleTo("app2")]   // OK  
