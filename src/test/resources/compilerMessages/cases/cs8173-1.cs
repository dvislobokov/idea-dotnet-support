// docs: cs8173.md #1; codes: CS8173
// CS8173.cs (12,18)

class C
{
    void M()
    {
        string s = "s";
        object o = s;
        ref string rs = ref s;
        ref object ro = ref o;

        ro = ref s;
    }
}
