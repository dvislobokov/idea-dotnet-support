class C
{
    public static C operator
    int x;
    public static C operator +;
    public static C operator +(C a) where T : U => a;
    public static C operator unchecked +(C a, C b) => a;
    public static C operator >>=(C a) => a;
    public static C operator > >(C a, int b) => a;
}
