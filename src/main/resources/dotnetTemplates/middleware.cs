using System.Threading.Tasks;
using Microsoft.AspNetCore.Http;
---
public class ${NAME}
{
    private readonly RequestDelegate _next;

    public ${NAME}(RequestDelegate next)
    {
        _next = next;
    }

    public async Task InvokeAsync(HttpContext context)
    {
        await _next(context);
    }
}
