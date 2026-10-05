using System.Diagnostics;
using MediatR;
using Shop.Api.Telemetry;

namespace Shop.Api.Behaviors;

/// <summary>A request that can tell what is wrong with it before its handler runs.</summary>
public interface IValidatable
{
    IEnumerable<string> Validate();
}

public sealed class ValidationException(IReadOnlyList<string> errors) : Exception("The request is not valid: " + string.Join("; ", errors))
{
    public IReadOnlyList<string> Errors { get; } = errors;
}

public sealed class ValidationBehavior<TRequest, TResponse> : IPipelineBehavior<TRequest, TResponse> where TRequest : notnull
{
    public Task<TResponse> Handle(TRequest request, RequestHandlerDelegate<TResponse> next, CancellationToken cancellationToken)
    {
        if (request is IValidatable validatable)
        {
            var errors = validatable.Validate().ToList();
            if (errors.Count > 0) throw new ValidationException(errors);
        }
        return next(cancellationToken);
    }
}

public sealed class LoggingBehavior<TRequest, TResponse>(ILogger<LoggingBehavior<TRequest, TResponse>> logger) : IPipelineBehavior<TRequest, TResponse>
    where TRequest : notnull
{
    public async Task<TResponse> Handle(TRequest request, RequestHandlerDelegate<TResponse> next, CancellationToken cancellationToken)
    {
        var name = typeof(TRequest).Name;
        logger.LogInformation("Handling {Request}", name);
        try
        {
            var response = await next(cancellationToken);
            logger.LogInformation("Handled {Request}", name);
            return response;
        }
        catch (Exception exception)
        {
            logger.LogError(exception, "Failed {Request}", name);
            throw;
        }
    }
}

public sealed class TelemetryBehavior<TRequest, TResponse>(ShopMetrics metrics) : IPipelineBehavior<TRequest, TResponse> where TRequest : notnull
{
    public async Task<TResponse> Handle(TRequest request, RequestHandlerDelegate<TResponse> next, CancellationToken cancellationToken)
    {
        var name = typeof(TRequest).Name;
        using var activity = ShopTelemetry.Source.StartActivity(name);
        activity?.SetTag("request.type", typeof(TRequest).FullName);
        var started = Stopwatch.GetTimestamp();
        var failed = false;
        try
        {
            return await next(cancellationToken);
        }
        catch (Exception exception)
        {
            failed = true;
            activity?.SetStatus(ActivityStatusCode.Error, exception.Message);
            throw;
        }
        finally
        {
            metrics.HandlerFinished(name, Stopwatch.GetElapsedTime(started), failed);
        }
    }
}
