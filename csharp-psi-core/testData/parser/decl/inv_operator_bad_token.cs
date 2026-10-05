class C
{
    public static C operator ..(C a, C b) => a;
    public static C operator ;(C a) => a;
    public static C operator implicit(C a) => a;
}
