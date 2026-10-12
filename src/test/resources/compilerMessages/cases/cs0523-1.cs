// docs: cs0523.md #1; codes: CS0523
// CS0523.cs
// compile with: /target:library
struct SelfReferentialStruct
{
    public SelfReferentialStruct other;   // CS0523
}

class SelfReferentialClass
{
    public SelfReferentialClass other;   // OK
}
