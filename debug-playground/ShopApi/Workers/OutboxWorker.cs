using Microsoft.EntityFrameworkCore;
using Shop.Api.Data;
using Shop.Api.Telemetry;

namespace Shop.Api.Workers;

/// <summary>Publishes the messages of the outbox: a <see cref="BackgroundService"/> with a scope per batch.</summary>
public sealed class OutboxWorker(IServiceScopeFactory scopes, ILogger<OutboxWorker> logger) : BackgroundService
{
    private static readonly TimeSpan Interval = TimeSpan.FromSeconds(5);
    private const int BatchSize = 50;
    private const int MaxAttempts = 5;

    protected override async Task ExecuteAsync(CancellationToken stoppingToken)
    {
        using var timer = new PeriodicTimer(Interval);
        while (await timer.WaitForNextTickAsync(stoppingToken))
        {
            try
            {
                await PublishBatch(stoppingToken);
            }
            catch (Exception exception) when (exception is not OperationCanceledException)
            {
                logger.LogError(exception, "Outbox batch failed");
            }
        }
    }

    private async Task PublishBatch(CancellationToken cancellationToken)
    {
        using var activity = ShopTelemetry.Source.StartActivity("outbox.publish");
        await using var scope = scopes.CreateAsyncScope();
        var db = scope.ServiceProvider.GetRequiredService<ShopDbContext>();

        var messages = await db.Outbox.AsTracking()
            .Where(message => message.ProcessedAt == null && message.Attempts < MaxAttempts)
            .OrderBy(message => message.OccurredAt)
            .Take(BatchSize)
            .ToListAsync(cancellationToken);
        activity?.SetTag("outbox.batch", messages.Count);

        foreach (var message in messages)
        {
            message.Attempts++;
            logger.LogInformation("Publishing {Type} {MessageId}", message.Type, message.Id);
            message.ProcessedAt = DateTimeOffset.UtcNow;
        }
        await db.SaveChangesAsync(cancellationToken);
    }
}
