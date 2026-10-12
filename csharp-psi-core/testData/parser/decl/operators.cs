class C
{
    public static C operator +(C a, C b) => a;
    public static C operator -(C a) => a;
    public static bool operator ==(C a, C b) => true;
    public static C operator >>(C a, int b) => a;
    public static C operator >>>(C a, int b) => a;
    public static implicit operator int(C c) => 0;
    public static explicit operator checked C(int i) => null;
    public static C operator checked +(C a, C b) => a;
    public void operator +=(C b) { }
    public static bool operator true(C c) => true;
    static C I.operator +(C a, C b) => a;
    static explicit I.operator int(C c) => 0;
}
