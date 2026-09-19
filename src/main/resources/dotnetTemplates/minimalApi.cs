using Microsoft.AspNetCore.Builder;
using Microsoft.AspNetCore.Http;
using Microsoft.AspNetCore.Routing;
---
public static class ${NAME}
{
    public static IEndpointRouteBuilder Map${NAME}(this IEndpointRouteBuilder app)
    {
        var group = app.MapGroup("/${ROUTE}");

        group.MapGet("/", () => Results.Ok());

        return app;
    }
}
