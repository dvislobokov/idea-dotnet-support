// docs: -; codes: CS0029, CS1662, CS1503 (a lambda whose parameter is typed only through the call it is an argument of — bUnit's `parameters.Add(x => x.Severity, …)`: its body is bound for each candidate's delegate, D6c2 of CSHARP_PSI_MIGRATION.md)
using System;

public enum Severity { Normal, Warning }

public class Alert
{
    public Severity Severity { get; set; }
    public string Text { get; set; } = "";
}

public class Builder<TComponent>
{
    public Builder<TComponent> Add<TValue>(Func<TComponent, TValue> selector, TValue value) => this;
    public Builder<TComponent> Label(Func<TComponent, string> text) => this;
    public Builder<TComponent> Count(Func<TComponent, int> count, int extra) => this;
}

public class Usage
{
    public void M()
    {
        var b = new Builder<Alert>()
            .Add(x => x.Severity, Severity.Warning)
            .Add(x => x.Text, "a")
            .Label(x => x.Text)
            .Count(x => (int)x.Severity, 1);
        new Builder<Alert>().Label(x => x.Severity);
        new Builder<Alert>().Count(x => x.Text, 1);
    }
}
