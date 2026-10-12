using System.Text;
using System.Text.RegularExpressions;

namespace Playground.Editor;

// Probe file for the Rider analysis (docs/rider-analysis): exists only in the robot's copy of the playground.
public enum OrderStatus { New, Paid, Shipped, Cancelled }

public interface IShape
{
    double Area();
    string Name { get; }
}

public abstract class ShapeBase : IShape
{
    public abstract double Area();
    public virtual string Name => GetType().Name;
    protected virtual void OnChanged(string reason) { }
}

public class Order
{
    public int Id { get; set; }
    public string Customer { get; set; } = "";
    public decimal Total { get; init; }
    public OrderStatus Status { get; set; }
    public List<string> Lines { get; } = new();
}

public static class StringExtensions
{
    public static bool IsBlank(this string? value) => string.IsNullOrWhiteSpace(value);
}

public class AnalysisSamples
{
    private const int MaxCount = 10;
    private static int _created;
    private readonly List<Order> _orders = new();
    private string _mutable = "x";
    public event EventHandler? Changed;

    public AnalysisSamples(string title, int count)
    {
    }

    public int Count => _orders.Count;

    public void Place(int quantity, string customer, bool express = false, OrderStatus status = OrderStatus.New) { }

    /// <summary>Highlighting samples.</summary>
    public async Task<string> Highlights(string input, int index, Order order)
    {
        var unused = 42;
        var local = input.Trim();
        _mutable += local;
        _created++;
        var text = $"Order {order.Id,5:D} for {order.Customer} at {DateTime.Now:yyyy-MM-dd}\t\n";
        var formatted = string.Format("{0} has {1:N2} items", order.Customer, order.Total);
        var regex = new Regex(@"^(?<name>\w+)\s*=\s*(\d{1,3})$");
        var blank = input.IsBlank();
        var query = from o in _orders where o.Total > 10 orderby o.Id select o.Customer;
        Place(3, "acme", true);
        Changed?.Invoke(this, EventArgs.Empty);
        if (order.Status == OrderStatus.Paid) return text + formatted + regex + blank + query.Count();
        string? nullable = null;
        var length = nullable.Length;
        await Task.Delay(MaxCount);
        object boxed = index;
        var str = (string)boxed;
        return str + length;
    }

    public void Completion(string name, List<int> items, Order order, OrderStatus status, CancellationToken token)
    {
        // P:c1

        // P:c2

        // P:c3
    }

    public int Returns(string name)
    {
        // P:r1

        return 0;
    }

    public async Task Awaiting(HttpClient client)
    {
        // P:a1

    }

    // P:m1

}

public class Circle : ShapeBase
{
    // P:o1

}

// P:t1

