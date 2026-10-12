class C
{
    int P { get set }
    int Q { private }
    int R { get; private private set; }
    event System.Action E { add; remove }
    event System.Action F { get; }
}
