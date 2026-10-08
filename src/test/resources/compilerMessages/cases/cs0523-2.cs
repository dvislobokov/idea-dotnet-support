// docs: cs0523.md #2; codes: CS0523
// CS0523b.cs
// compile with: /target:library
struct ReferenceCycleStruct1
{
    public ReferenceCycleStruct2 other;   // CS0523
}

struct ReferenceCycleStruct2
{
    public ReferenceCycleStruct3 other;   // CS0523
}

struct ReferenceCycleStruct3
{
    public ReferenceCycleStruct1 other;   // CS0523
}
