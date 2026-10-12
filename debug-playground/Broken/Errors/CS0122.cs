// CS0122: 'x' is inaccessible due to its protection level. Lines marked `// ERROR CSxxxx` must show that error in the editor (and in
// `dotnet build`), every other line no error at all: tools/diag/check_errors.py roslyn|ide checks both. Not compiled with Broken.
namespace DebugPlayground.Broken.Errors.CS0122;

public class Account
{
    private decimal _balance;
    protected string Owner = "";
    private protected int Revision;
    internal int Branch;
    protected internal int Region;
    private void Audit() { }
    private class Ledger { public int Lines; }
    protected class Statement { }
    public class Card { private int _pin; }

    public void Deposit(Account other, Card card)
    {
        _balance += 1;
        other._balance += 2;         // private: any Account inside Account
        var ledger = new Ledger();
        ledger.Lines++;
        card._pin = 1; // ERROR CS0122
    }
}

public class Savings : Account
{
    public void Rename(Savings other)
    {
        Owner = "me";
        other.Owner = "you";         // protected through the derived type
        Revision++;
        var statement = new Statement();
        _balance = 1; // ERROR CS0122
    }

    private class Helper
    {
        public void Touch(Savings savings) => savings.Owner = "nested";
    }
}

public class Teller
{
    public void Serve(Account account)
    {
        account._balance = 0; // ERROR CS0122
        account.Audit(); // ERROR CS0122
        account.Owner = "x"; // ERROR CS0122
        account.Revision = 2; // ERROR CS0122
        account.Branch = 1;
        account.Region = 2;
        var ledger = new Account.Ledger(); // ERROR CS0122
        Console.WriteLine(account.ToString());
    }
}

public class Vault
{
    private void Open(string code) { }
    private void Open(int pin) { }
    private Vault(string name) { }
    public Vault(double size) { }
    protected Vault() { }
}

public class Bank : Vault
{
    public Bank() : base() { }       // a protected constructor through base(...)
}

public class Locksmith
{
    public void Visit(Vault vault)
    {
        var big = new Vault(2);      // int to double: the public constructor fits
        vault.Open(42); // ERROR CS0122
        var named = new Vault("main"); // ERROR CS0122
        var plain = new Vault(); // ERROR CS0122
    }
}
