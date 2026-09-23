namespace Playground.Lib;

/// <summary>Code of another project of the solution: stepping into it and breakpoints in it check how source paths are resolved.</summary>
public static class Pricing
{
    public static decimal Total(IEnumerable<OrderLine> lines, decimal discountPercent)
    {
        decimal sum = 0; // BP:lib — a breakpoint in a class library
        foreach (var line in lines)
        {
            sum += line.Price * line.Quantity;
        }
        return Apply(sum, discountPercent);
    }

    private static decimal Apply(decimal sum, decimal discountPercent) => sum - sum * discountPercent / 100; // BP:lib-expression-body
}

public record OrderLine(string Name, decimal Price, int Quantity);

public class ShopException(string message, int code) : Exception(message)
{
    public int Code { get; } = code;

    public void CheckPrice(int? price){
        if(price == null){
            throw new ArgumentNullException(nameof(price));
        }
    }
}

