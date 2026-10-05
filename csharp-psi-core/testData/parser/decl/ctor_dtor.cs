class C
{
    C() { }
    public C(int x) : this() { }
    C(string s) : base(s) => F();
    static C() { }
    ~C() { }
}
