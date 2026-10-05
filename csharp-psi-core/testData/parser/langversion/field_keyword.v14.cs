class C
{
    int field;
    int P { get { return field; } set => field = value; }
    int Q => field;
}
