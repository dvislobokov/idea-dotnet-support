// docs: cs8147.md #1; codes: CS8147
// CS8147.cs (6,44)

public class C
{
    private ref int number;

    ref int Number { get => ref number; init => number = value; }
}
