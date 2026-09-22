using System;
using Microsoft.EntityFrameworkCore;
using Microsoft.EntityFrameworkCore.Design;
---
/// <summary>
/// Used by the EF Core tools (<c>dotnet ef</c>) only: creates the context without starting the application,
/// so migrations work even when the host needs secrets or services that are not there at design time.
/// </summary>
public class ${NAME} : IDesignTimeDbContextFactory<${BASE}>
{
    public ${BASE} CreateDbContext(string[] args)
    {
        // dotnet ef database update -- "<connection string>", then the variable the application reads too, then the local database
        var connectionString = (args.Length > 0 ? args[0] : null)
            ?? Environment.GetEnvironmentVariable("ConnectionStrings__${EF_CONNECTION_NAME}")
            ?? "${EF_CONNECTION_STRING}";

        var options = new DbContextOptionsBuilder<${BASE}>()
            .${EF_USE_PROVIDER}
            .Options;
        return new ${BASE}(options);
    }
}
