interface I
{
    void M();
    int P { get; set; }
    event System.Action E;
    int this[int i] { get; }
    static abstract I operator +(I a, I b);
}
