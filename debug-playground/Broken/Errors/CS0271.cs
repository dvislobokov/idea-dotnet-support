// CS0271: The property or indexer 'x' cannot be used in this context because the get accessor is inaccessible. Lines marked `// ERROR CSxxxx` must show that error in the editor (and in
// `dotnet build`), every other line no error at all: tools/diag/check_errors.py roslyn|ide checks both. Not compiled with Broken.
namespace DebugPlayground.Broken.Errors.CS0271;

public class Vault
{
    public string Secret { private get; set; } = "";
    public int Code { protected get; set; }
    public int Size { get; private set; }

    public bool Check(Vault other) => other.Secret == Secret;
}

public class Safe : Vault
{
    public int Peek() => Code;
}

public class Thief
{
    public void Steal(Vault vault)
    {
        vault.Secret = "mine";
        vault.Code = 1;
        var secret = vault.Secret; // ERROR CS0271
        var code = vault.Code; // ERROR CS0271
        var size = vault.Size;
        Console.WriteLine(secret + code + size);
    }
}
