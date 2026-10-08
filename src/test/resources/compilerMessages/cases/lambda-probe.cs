// docs: -; codes: CS8917 CS1643 (the lambda probe of 0.1.147: a lambda without a natural type next to a `Delegate` overload, as minimal APIs have)
using System;
using System.Threading.Tasks;

class HttpContext { public Task Done() => Task.CompletedTask; public void Abort() { } }
delegate Task RequestDelegate(HttpContext context);

static class LambdaProbe
{
    static void MapGet(string pattern, RequestDelegate requestDelegate) { }
    static void MapGet(string pattern, Delegate handler) { }

    static void Probe()
    {
        MapGet("/a", context => context.Done());
        MapGet("/b", async context => { await context.Done(); });
        MapGet("/c", context => { context.Abort(); });
        MapGet("/d", () => 1);
        MapGet("/e", (string name) => name.Length);
        MapGet("/f", (HttpContext context, int id) => id);
        Delegate d = x => x;
        object o = (x, y) => x;
        Delegate ok = (int x) => x;
        Delegate none = () => 1;
    }
}
