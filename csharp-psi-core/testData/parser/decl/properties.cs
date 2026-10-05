class C
{
    int P { get; set; }
    int Q { get => 1; private set { } }
    int R => 1;
    int S { get; init; } = 2;
    public required int T { get; set; }
    int I.P { get; }
    int F { get => field; set => field = value; }
}
