// docs: cs0173.md #2; codes: CS0173 CS8957
// Fix: Use explicit target type (C# 9.0+).
object result = true ? 100 : "ABC";  // OK in C# 9.0+

// Or use explicit casting (all versions).
var result = true ? (object)100 : (object)"ABC";
