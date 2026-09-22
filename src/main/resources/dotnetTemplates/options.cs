using Microsoft.Extensions.Configuration;
using Microsoft.Extensions.DependencyInjection;
---
public class ${NAME}
{
    public const string SectionName = "${BASE}";
}

public static class ${NAME}ServiceCollectionExtensions
{
    public static IServiceCollection Add${NAME}(this IServiceCollection services, IConfiguration configuration)
    {
        services.Configure<${NAME}>(configuration.GetSection(${NAME}.SectionName));
        return services;
    }
}
