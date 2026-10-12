using System.Diagnostics;
using System.Diagnostics.Metrics;
using Shop.Api.Domain;

namespace Shop.Api.Telemetry;

public static class ShopTelemetry
{
    public const string ServiceName = "Shop.Api";
    public const string Version = "1.0.0";

    public static readonly ActivitySource Source = new(ServiceName, Version);
}

/// <summary>The business metrics of the shop: orders placed and cancelled, their totals, the duration of the handlers.</summary>
public sealed class ShopMetrics : IDisposable
{
    public const string MeterName = "Shop.Api.Orders";

    private readonly Meter _meter;
    private readonly Counter<long> _ordersPlaced;
    private readonly Counter<long> _ordersCancelled;
    private readonly Histogram<double> _orderTotal;
    private readonly Histogram<double> _handlerDuration;

    public ShopMetrics(IMeterFactory meterFactory)
    {
        _meter = meterFactory.Create(MeterName, ShopTelemetry.Version);
        _ordersPlaced = _meter.CreateCounter<long>("shop.orders.placed", unit: "{order}", description: "Orders placed");
        _ordersCancelled = _meter.CreateCounter<long>("shop.orders.cancelled", unit: "{order}", description: "Orders cancelled");
        _orderTotal = _meter.CreateHistogram<double>("shop.orders.total", unit: "RUB", description: "Totals of placed orders");
        _handlerDuration = _meter.CreateHistogram<double>("shop.handler.duration", unit: "ms", description: "Duration of MediatR handlers");
    }

    public void OrderPlaced(Order order)
    {
        var tags = new TagList { { "customer", order.CustomerId }, { "lines", order.Lines.Count } };
        _ordersPlaced.Add(1, tags);
        _orderTotal.Record((double)order.Total, tags);
    }

    public void OrderCancelled(OrderStatus from) => _ordersCancelled.Add(1, new KeyValuePair<string, object?>("from", from.ToString()));

    public void HandlerFinished(string request, TimeSpan elapsed, bool failed) =>
        _handlerDuration.Record(elapsed.TotalMilliseconds, new("request", request), new("failed", failed));

    public void Dispose() => _meter.Dispose();
}
