// docs: lock-semantics.md #1; codes: CS0185 CS1996 CS9216 CS9217
object lockObject = new System.Threading.Lock();

lock (lockObject) // CS9216
{
    // .. Your code
}
