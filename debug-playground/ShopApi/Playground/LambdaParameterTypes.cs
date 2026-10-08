using System.Diagnostics;
using Microsoft.AspNetCore.Http.Json;
using Shop.Api.Domain;

namespace Shop.Api.Playground;

// The type of a lambda's parameter without a written type (0.1.147): it comes from the delegate the lambda converts to, as in Rider.
// Hover each marked parameter (or put the caret on it and press Ctrl+Q): the EXPECT says what the hover shows. Where the marker says
// to type, type on the empty line under it, look, then undo with Ctrl+Z: the file compiles as is.
public static class LambdaParameterTypes
{
    public static void MapLambdaParameterTypes(this WebApplication app, IServiceCollection services, ILoggingBuilder logging, List<Order> orders)
    {
        // ---- minimal APIs: a real delegate next to a `Delegate` overload (MapGet / MapPost / MapPut / MapDelete / Map / MapMethods)

        // TYPE:lambda-context — hover `context`. EXPECT: "parameter HttpContext context"; Ctrl+B on `MapGet` opens the overload with
        // `RequestDelegate requestDelegate`, not the one with `Delegate handler`; the inlay hint before the lambda says `requestDelegate:`
        app.MapGet("/lambda/context", context => context.Response.WriteAsync("context"));

        // TYPE:lambda-context-members — type `app.MapGet("/lambda/members", context => { context.` on the empty line below.
        // EXPECT: the list has Response, Request, Items, RequestServices (members of HttpContext), with `context` typed already while the body is unfinished

        // TYPE:lambda-context-paths — type `app.MapGet("/lambda/paths", context => { context.Abort(); });` below.
        // EXPECT: CS1643 "Not all code paths return a value in lambda expression of type 'RequestDelegate'" on `=>`; hover `context` still says HttpContext

        // TYPE:lambda-context-async — hover `context`. EXPECT: HttpContext (an async block body)
        app.MapPost("/lambda/async", async context => { await context.Response.WriteAsync("async"); });

        // TYPE:lambda-delegate-typed — hover `id`. EXPECT: "parameter int id"; Ctrl+B on `MapGet` opens the overload with `Delegate handler`
        // (typed parameters give the lambda a natural type, which is what `Delegate` takes)
        app.MapGet("/lambda/typed/{id}", (int id, HttpContext context) => Results.Ok(id));

        // TYPE:lambda-delegate-none — Ctrl+B on `MapGet`. EXPECT: the overload with `Delegate handler` (no parameters: a natural type too)
        app.MapGet("/lambda/none", () => Results.Ok());

        // ---- middleware: two `Use` overloads that agree on `context` and differ in `next`

        // TYPE:lambda-use-context — hover `context`. EXPECT: HttpContext (both overloads take it); hover `next`.
        // EXPECT: "Func<Task> next" (the body calls `next()` without arguments, which only `Use(Func<HttpContext, Func<Task>, Task>)` allows);
        // NOT CHECKED YET: the plugin may leave `next` untyped where the overloads differ in it — report what you see
        app.Use(async (context, next) => { context.Items["seen"] = true; await next(); });

        // TYPE:lambda-use-next-context — hover `next`. EXPECT: "RequestDelegate next" (the body calls `next(context)`)
        app.Use(async (context, next) => { await next(context); });

        // TYPE:lambda-run — hover `context`. EXPECT: HttpContext (`Run(RequestDelegate)`, a single overload)
        app.Run(context => context.Response.WriteAsync("run"));

        // TYPE:lambda-exception-handler — hover `errorApp`. EXPECT: "IApplicationBuilder errorApp"; hover the inner `context`. EXPECT: HttpContext
        app.UseExceptionHandler(errorApp => errorApp.Run(context => context.Response.WriteAsync("error")));

        // ---- endpoint filters: a generic extension method, the delegate's parameters are not the type parameter

        // TYPE:lambda-filter — hover `invocation`. EXPECT: "EndpointFilterInvocationContext invocation"; hover `next`. EXPECT: "EndpointFilterDelegate next"
        app.MapGroup("/lambda/filtered").AddEndpointFilter(async (invocation, next) => await next(invocation));

        // ---- dependency injection and options: the type argument says the parameter

        // TYPE:lambda-di-provider — hover `provider`. EXPECT: "IServiceProvider provider" (every `AddScoped` overload takes a `Func<IServiceProvider, …>`)
        services.AddScoped(provider => new Stopwatch());

        // TYPE:lambda-options — hover `options`. EXPECT: "JsonOptions options" (`Configure<TOptions>(Action<TOptions>)` with the type argument written)
        services.Configure<JsonOptions>(options => options.SerializerOptions.WriteIndented = true);

        // TYPE:lambda-http-client — hover `client`. EXPECT: "HttpClient client" (the overloads with one lambda parameter take `Action<HttpClient>`)
        services.AddHttpClient("lambda", client => client.Timeout = TimeSpan.FromSeconds(5));

        // TYPE:lambda-log-filter — hover `category`. EXPECT: "string? category"; hover `level`. EXPECT: "LogLevel level"
        // (`AddFilter(Func<string?, LogLevel, bool>)` is the only overload with two parameters)
        logging.AddFilter((category, level) => level >= LogLevel.Warning);

        // ---- the base class library

        // TYPE:lambda-timer — hover `state`. EXPECT: "object? state" (a constructor: `Timer(TimerCallback)`)
        using var timer = new Timer(state => Console.WriteLine(state), null, 0, 1000);

        // TYPE:lambda-thread — hover `argument`. EXPECT: "object? argument" (`Thread(ParameterizedThreadStart)` next to `Thread(ThreadStart)`)
        var thread = new Thread(argument => Console.WriteLine(argument));

        // TYPE:lambda-event — hover `sender`. EXPECT: "object? sender"; hover `e`. EXPECT: "ConsoleCancelEventArgs e"
        Console.CancelKeyPress += (sender, e) => e.Cancel = true;

        // TYPE:lambda-linq — hover `order` in each lambda. EXPECT: "Order order" in all three
        var totals = orders.Where(order => order.Total > 0).OrderBy(order => order.Id).Select(order => order.Total).ToList();

        // TYPE:lambda-foreach — hover `order`. EXPECT: "Order order" (`List<T>.ForEach(Action<T>)`)
        orders.ForEach(order => Console.WriteLine(order.Id));

        // TYPE:lambda-parallel — hover `order`. EXPECT: "Order order" (`Parallel.ForEach<TSource>(IEnumerable<TSource>, Action<TSource>)`)
        Parallel.ForEach(orders, order => Console.WriteLine(order.Id));

        // TYPE:lambda-sort — hover `a`. EXPECT: "Order a" (`List<T>.Sort(Comparison<T>)`)
        orders.Sort((a, b) => a.Id.CompareTo(b.Id));

        // TYPE:lambda-task-run — hover `token`. EXPECT: "CancellationToken token" (a declared `Func<CancellationToken, Task>`)
        Func<CancellationToken, Task> work = token => Task.Delay(10, token);

        // TYPE:lambda-dictionary — hover `pair`. EXPECT: "KeyValuePair<long, Order> pair"
        var byId = orders.ToDictionary(order => order.Id).Where(pair => pair.Value.Total > 0).ToList();

        // TYPE:lambda-string-join — hover `order`. EXPECT: "Order order"
        var names = string.Join(", ", orders.Select(order => order.Id));

        _ = (totals, thread, work, byId, names);
    }
}
