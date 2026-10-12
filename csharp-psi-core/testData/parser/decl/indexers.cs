class C
{
    int this[int i] { get => i; set { } }
    int this[int i, string s] => i;
    int I.this[int i] { get { return 0; } }
}
